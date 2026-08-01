(ns cider.nrepl.middleware.out
  "The `out-subscribe` / `out-unsubscribe` ops: forward the server's own output
  to connected editors.

  An eval's output already comes back with its result; this covers everything
  else — a background thread's println, a log line, output from a request being
  served while you sit at the REPL. A subscribing session gets it as `out`
  messages until it unsubscribes or disconnects.

  The forking writer is a reify of java.io.Writer installed as the root of
  *out* / *err*. jolt's printer routes through any value carrying a `write`
  method, so this needs no host support; the original writers stay in the fork,
  so the process's own terminal output is unchanged."
  (:require [cider.nrepl.middleware.util :as util]))

(def ^:private capturing-thread
  "jolt.nrepl/*capturing-thread* if this jolt has it. Resolved rather than
  referred: on a jolt that predates it there is nothing to compare against, and
  the cost is that an eval's own output is also forwarded (so a subscriber sees
  it twice) rather than the namespace failing to load."
  (resolve 'jolt.nrepl/*capturing-thread*))

(defonce ^:private subscribers
  ;; session id -> (fn [response-map]) — the subscribing request's reply
  (atom {}))

(defonce ^:private original
  {:out *out* :err *err*})

(defonce ^:private installed? (atom false))

(def ^:private ^:dynamic *forwarding*
  "True while this thread is inside a broadcast. Sending a response can itself
  print (a failing connection reports the error), which lands back in the forking
  writer — without this the forward would call itself until the stack ran out."
  false)

(defn- broadcast! [kind s]
  ;; an eval's own output already comes back with its result — jolt.nrepl names
  ;; the thread whose output it is capturing, so forwarding that would show it
  ;; twice. A future the eval started writes from a different thread, and its
  ;; output is exactly what a subscriber is here for.
  (when-not (or *forwarding*
                (identical? (some-> capturing-thread deref) (Thread/currentThread)))
    (binding [*forwarding* true]
      (doseq [[session reply] @subscribers]
        (try (reply {(name kind) s})
             (catch :default _
               ;; a dead connection: stop writing to it rather than failing the
               ;; print that is passing through
               (swap! subscribers dissoc session)))))))

(defn- forking-writer [kind]
  (let [orig (get original kind)]
    (reify java.io.Writer
      (write [_ s]
        (.write orig s)
        (broadcast! kind (str s)))
      (flush [_] (.flush orig))
      (close [_] nil))))

(defn- install! []
  (when (compare-and-set! installed? false true)
    (alter-var-root (var clojure.core/*out*) (constantly (forking-writer :out)))
    (alter-var-root (var clojure.core/*err*) (constantly (forking-writer :err)))))

(defn subscribe [session reply]
  (install!)
  (swap! subscribers assoc session reply)
  session)

(defn unsubscribe [session]
  (swap! subscribers dissoc session)
  session)

(defn out-subscribe-reply [request]
  (let [session (get request "session")]
    (subscribe session (:reply request))
    {:out-subscribe session}))

(defn out-unsubscribe-reply [request]
  {:out-unsubscribe (unsubscribe (get request "session"))})

(def ops
  {"out-subscribe" out-subscribe-reply
   "out-unsubscribe" out-unsubscribe-reply})

(def middleware (util/op-middleware ops))
