(ns nrepl.middleware.session
  "Session middleware: stateful, isolated nREPL sessions. Each session keeps its
  own current namespace and runs its evals on a dedicated serialized worker
  thread (a core.async channel + a future), so a session's evals are ordered and
  a long eval doesn't block other ops on the connection.

  Interrupt is real (not best-effort): each eval runs under
  jolt.host/run-interruptible with a per-eval token, and `interrupt!` sets that
  token so the running eval aborts at the next safe point (Chez's engine timer is
  polled at call/loop back-edges). The worker is reused — the computation is not
  abandoned. A computation blocked in a foreign call (socket recv, sleep) only
  aborts when it returns to Scheme.

  Note on jolt: the compile/eval namespace is process-global (load-string
  resolves against the current in-ns, not a thread-local *ns*), so the worker sets
  the session's ns before each eval. Concurrent ns-changing evals across sessions
  could therefore interleave; a normal editor (one eval session + a read-only
  tooling session) doesn't hit this."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [jolt.nrepl :as server]
            [nrepl.bencode :as bencode]
            [nrepl.middleware.caught :as caught]))

(def ^:private sessions (atom {}))   ;; id -> session

(defn- last-eval-exception
  "*e holds the exception server/evaluate just reported — the only handle on
  the error object itself, which its return map doesn't carry. Validated
  against the reported :err so a concurrent session's eval overwriting *e in
  the window between the two reads can't substitute a different error. nil on
  a jolt whose evaluate doesn't set *e (the hook then doesn't fire — degraded,
  not broken)."
  [err]
  (let [e *e]
    (when (and e err (str/starts-with? err (server/err-msg e)))
      e)))

;; --- stdin -------------------------------------------------------------------
;; A session's evals read *in* from what the client sends with the `stdin` op,
;; as on JVM nREPL, not from the server process's own stdin (EOF under a
;; daemon, or the terminal of a program that embeds the server, which the read
;; would then compete with for keystrokes). (:input session) is an atom holding
;; a vector of pending chars (as ints), with :eof where an empty `stdin` ended
;; the input.

(defn- take-input!
  "The next pending char or :eof, removed from the queue; nil when none waits."
  [input]
  (first (first (swap-vals! input (fn [q] (if (seq q) (subvec q 1) q))))))

(defn- read-char!
  "A read() on the session's *in*: the next char sent with `stdin`, -1 at an
  :eof. With nothing pending it says need-input once on the running eval's
  message, as JVM nREPL does, then waits. The wait polls with short sleeps,
  because a sleeping thread doesn't reach the engine timer run-interruptible
  aborts at: once the eval's token is set it spins instead, so `interrupt`
  lands and the worker replies interrupted."
  [session]
  (loop [asked? false]
    (let [c (take-input! (:input session))]
      (cond
        (= :eof c) -1
        c c
        :else
        (let [{:keys [reply token]} @(:current session)]
          (when (and (not asked?) reply) (reply {"status" ["need-input"]}))
          (if (and token (jolt.host/interrupted? token))
            (loop [] (recur))
            (do (Thread/sleep 10) (recur true))))))))

(defn- session-in
  "*in* for `session`'s evals. jolt's read and read-line go through a
  LineNumberingPushbackReader, which calls only the Reader's read()."
  [session]
  (clojure.lang.LineNumberingPushbackReader.
   (proxy [java.io.Reader] []
     (read ([] (read-char! session)))
     (close [] nil))))

(defn- add-input!
  "The `stdin` op: `text` joins the session's pending input; an empty one is
  end of input, so a read waiting on it returns EOF."
  [session text]
  (swap! (:input session) (fn [q] (if (empty? text) (conj q :eof) (into q (map int text))))))

(defn- user-ns-name
  "\"user\", created with clojure.core referred when the process has none."
  []
  (when-not (find-ns 'user)
    (binding [*ns* (create-ns 'user)] (refer-clojure)))
  "user")

(defn- start-ns-name
  "Where an eval that names no ns starts: the session's ns, or user when that
  ns has since been removed (rather than whatever *ns* the worker has)."
  [session]
  (let [n @(:ns session)]
    (if (find-ns (symbol n)) n (user-ns-name))))

(defn- run-eval* [session code-wire ns-str load? file reply caught? token]
  (let [code (bencode/wire-> code-wire)
        ns-atom (:ns session)
        ;; the actual evaluation runs interruptibly: interrupt! on `token` aborts
        ;; it (throws {:jolt/interrupted true}), which the worker catches below.
        ;; Output streams: evaluate calls :out / :err with each chunk as it is
        ;; flushed, while the eval runs, as upstream's session writers do. :out
        ;; then comes back empty.
        {:keys [value out ns err ex err-msg ex-class root-ex-class]}
        ;; *in* is the session's, fed by the `stdin` op.
        (jolt.host/run-interruptible token (fn [] (binding [*in* (:in session)] (server/evaluate code (or ns-str (start-ns-name session))
                                                                   {:out (fn [s] (reply {"out" s}))
                                                                    :err (fn [s] (reply {"err" s}))
                                                                    ;; a load-file op is a load: the
                                                                    ;; file's ns form doesn't switch
                                                                    ;; the session, as on JVM nREPL
                                                                    :load load?
                                                                    ;; its file-path, which JVM nREPL
                                                                    ;; compiles a load-file under: a def
                                                                    ;; records it, and *file* is the path
                                                                    :file file}))))]
    ;; the session keeps where the eval left it, unless the eval named its
    ;; ns: JVM nREPL restores the session's *ns* after one of those
    (when-not ns-str (reset! ns-atom ns))
    (when (seq out) (reply {"out" out}))
    (cond
      (nil? err)
      (reply {"value" value "ns" ns "status" ["done"]})

      ;; a jolt whose evaluate renders the error the way JVM nREPL sends it:
      ;; err in clojure.main/err->msg form, ex/root-ex as class strings with
      ;; eval-error, then done on its own message
      err-msg
      (let [e (or ex (last-eval-exception err))]
        (reply {"err" err-msg})
        ;; the throwable rides the eval-error response only, so a hook runs
        ;; once per error; wrap-caught dissocs it before the wire
        (reply (cond-> {"ex" ex-class "root-ex" root-ex-class "status" ["eval-error"]}
                 (and caught? e) (assoc caught/throwable-key e)))
        (reply {"status" ["done"]}))

      :else
      (let [e (last-eval-exception err)]
        (reply {"err" (str err "\n")})
        (reply (cond-> {"ex" (str err) "status" ["eval-error" "done"]}
                 (and caught? e) (assoc caught/throwable-key e)))))))

(defn- run-eval [session code-wire ns-str load? file reply caught? token]
  (let [ns-str (when (and (string? ns-str) (not (str/blank? ns-str))) ns-str)]
    ;; an ns the request names that does not exist: no eval, as JVM nREPL
    ;; answers it, rather than running the code wherever *ns* happens to be
    (if (and ns-str (not (find-ns (symbol ns-str))))
      (reply {"ns" ns-str "status" ["error" "namespace-not-found" "done"]})
      (run-eval* session code-wire ns-str load? file reply caught? token))))

(defn- spawn-worker [session]
  ;; A daemon, as nREPL's session threads are on the JVM: the worker waits on
  ;; the session's channel for as long as the session exists, and that must not
  ;; keep the process up once the program's own threads are done.
  (doto (Thread.
          (bound-fn []
            (loop []
              (when-let [job (a/<!! (:chan session))]
                (let [{:keys [code ns load? file reply id thunk caught?]} job
                      token (jolt.host/make-interrupt)]
                  (reset! (:current session) {:id id :reply reply :token token})
                  (try (if thunk
                         (jolt.host/run-interruptible token thunk)
                         (run-eval session code ns load? file reply caught? token))
                       (catch :default e
                         (if (:jolt/interrupted (ex-data e))
                           (reply {"status" ["interrupted" "done"]})
                           (if-let [err->msg (resolve 'jolt.nrepl/err->msg)]
                             (do (reply {"err" (err->msg e)})
                                 (reply {"ex" (str (class e)) "status" ["eval-error"]})
                                 (reply {"status" ["done"]}))
                             (do (reply {"err" (str (server/err-msg e) "\n")})
                                 (reply {"status" ["eval-error" "done"]})))))
                       (finally (reset! (:current session) nil)))
                  (recur))))))
    (.setDaemon true)
    (.start)))

(defn- make-session [clone-from]
  (let [base (get @sessions clone-from)
        session {:id (server/new-session)
                 :ns (atom (if base @(:ns base) "user"))
                 :chan (a/chan 64)
                 :current (atom nil)
                 :input (atom [])}
        session (assoc session :in (session-in session))]
    (spawn-worker session)
    (swap! sessions assoc (:id session) session)
    session))

(defn submit!
  "Queue `thunk` on `session-id`'s worker, to run like an eval would: serialized
  with that session's other work, under an interrupt token, with the same error
  reply if it throws. `reply` and `id` are the requesting message's, so an
  `interrupt` while it runs answers on the right message.

  This is how an op that can take a while — running a test suite — stays off the
  connection's reader thread. Left there it would block every other op on the
  connection for the duration, `interrupt` included.

  Returns true if it was queued, false when there is no such session (the caller
  then runs it inline)."
  [session-id id reply thunk]
  (if-let [session (get @sessions session-id)]
    (do (a/>!! (:chan session) {:thunk thunk :id id :reply reply}) true)
    false))

(defn interrupt!
  "Interrupt `session-id`'s running eval by setting its interrupt token; the eval
  aborts at the next safe point and the worker replies :interrupted, then keeps
  serving. Returns :ok if an eval was in flight, else :idle."
  [session-id]
  (if-let [session (get @sessions session-id)]
    (let [cur @(:current session)]
      (if cur
        (do (jolt.host/interrupt! (:token cur)) :ok)
        :idle))
    :idle))

(defn- close-session! [session-id]
  (when-let [session (get @sessions session-id)]
    (a/close! (:chan session))
    (swap! sessions dissoc session-id)))

(defn session
  "Middleware: handles clone/close and stdin, and routes eval/load-file for a
  session to its worker; other ops (and session-less evals) pass through to
  `handler`."
  [handler]
  (server/register-ops! "clone" "close" "eval" "load-file" "ls-sessions" "stdin")
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

        ;; answered here, not on the worker: the eval waiting for this input
        ;; is what occupies the worker
        (and session (= op "stdin"))
        (do (add-input! session (bencode/wire-> (or (get request "stdin") "")))
            (server/respond request {"status" ["done"]}))

        (and session (#{"eval" "load-file"} op))
        (a/>!! (:chan session)
               {:code (if (= op "load-file") (get request "file") (get request "code"))
                :ns (get request "ns")
                :load? (= op "load-file")
                :file (when (= op "load-file") (some-> (get request "file-path") bencode/wire->))
                :id (get request "id")
                :reply (:reply request)
                :caught? (get request caught/enabled-key)})

        :else (handler request)))))
