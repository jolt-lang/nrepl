(ns nrepl.middleware.session
  "Session middleware: stateful, isolated nREPL sessions. Each session keeps its
  own current namespace and runs its evals on a dedicated serialized worker
  thread (a core.async channel + a future), so a session's evals are ordered and
  a long eval doesn't block other ops on the connection.

  Note on jolt: the compile/eval namespace is process-global (load-string
  resolves against the current in-ns, not a thread-local *ns*), so the worker sets
  the session's ns before each eval. Concurrent ns-changing evals across sessions
  could therefore interleave; a normal editor (one eval session + a read-only
  tooling session) doesn't hit this."
  (:require [clojure.core.async :as a]
            [jolt.nrepl :as server]
            [nrepl.bencode :as bencode]))

(def ^:private sessions (atom {}))   ;; id -> session

(defn- run-eval [session code-wire ns-str reply]
  (let [code (bencode/wire-> code-wire)
        ns-atom (:ns session)
        {:keys [value out ns err]} (server/evaluate code (or ns-str @ns-atom))]
    (reset! ns-atom ns)
    (when (seq out) (reply {"out" out}))
    (if err
      (do (reply {"err" (str err "\n")})
          (reply {"ex" (str err) "status" ["eval-error" "done"]}))
      (reply {"value" value "ns" ns "status" ["done"]}))))

(defn- spawn-worker [session]
  (future
    (loop []
      (when-let [job (a/<!! (:chan session))]
        (let [{:keys [code ns reply id]} job]
          (reset! (:current session) {:id id :reply reply})
          (try (run-eval session code ns reply)
               (catch :default e
                 (reply {"err" (str (server/err-msg e) "\n")})
                 (reply {"status" ["eval-error" "done"]}))
               (finally (reset! (:current session) nil)))
          (recur))))))

(defn- make-session [clone-from]
  (let [base (get @sessions clone-from)
        session {:id (server/new-session)
                 :ns (atom (if base @(:ns base) "user"))
                 :chan (a/chan 64)
                 :current (atom nil)}]
    (spawn-worker session)
    (swap! sessions assoc (:id session) session)
    session))

(defn interrupt!
  "Best-effort interrupt of `session-id`'s running eval: reply :interrupted to the
  in-flight eval and give the session a fresh worker. jolt can't kill a running
  thread, so the stuck eval is abandoned (it keeps running until it returns); the
  session stays responsive. Returns :ok or :idle."
  [session-id]
  (if-let [session (get @sessions session-id)]
    (let [cur @(:current session)]
      (if cur
        (do ((:reply cur) {"status" ["interrupted" "done"]})
            ;; abandon the stuck worker; install a fresh chan + worker.
            (a/close! (:chan session))
            (let [fresh (assoc session :chan (a/chan 64) :current (atom nil))]
              (spawn-worker fresh)
              (swap! sessions assoc session-id fresh))
            :ok)
        :idle))
    :idle))

(defn- close-session! [session-id]
  (when-let [session (get @sessions session-id)]
    (a/close! (:chan session))
    (swap! sessions dissoc session-id)))

(defn session
  "Middleware: handles clone/close and routes eval/load-file for a session to its
  worker; other ops (and session-less evals) pass through to `handler`."
  [handler]
  (server/register-ops! "clone" "close" "eval" "load-file" "ls-sessions")
  (fn [request]
    (let [op (get request "op")
          sid (get request "session")
          session (get @sessions sid)]
      (cond
        (= op "clone")
        (let [s (make-session sid)]
          (server/respond request {"new-session" (:id s) "status" ["done"]}))

        (= op "ls-sessions")
        (server/respond request {"sessions" (vec (keys @sessions)) "status" ["done"]})

        (= op "close")
        (do (close-session! sid)
            (server/respond request {"status" ["session-closed" "done"]}))

        (and session (#{"eval" "load-file"} op))
        (a/>!! (:chan session)
               {:code (if (= op "load-file") (get request "file") (get request "code"))
                :ns (get request "ns")
                :id (get request "id")
                :reply (:reply request)})

        :else (handler request)))))
