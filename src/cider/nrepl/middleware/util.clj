(ns cider.nrepl.middleware.util
  "Shared plumbing for the cider ops: wire coercion, op dispatch, error replies.

  A cider op is a plain (fn [request] response-map). `op-middleware` turns a map
  of op name -> that fn into one jolt.nrepl middleware: it registers the ops so
  `describe` advertises them, coerces the response for bencode, appends the
  \"done\" status, and turns a thrown exception into the error response CIDER and
  Calva expect instead of killing the connection.

  An op that replies itself (several messages, or a reply from another thread)
  returns ::async and calls `respond` as often as it likes."
  (:require [clojure.string :as str]
            [jolt.nrepl :as server]
            [nrepl.middleware.session :as session]))

(def async
  "Return this from an op fn that has already replied (or will reply later)."
  ::async)

(defn transform-value
  "Coerce `v` into something bencode can carry. Integers survive; other numbers,
  keywords, symbols, vars and everything unrecognized become strings; maps become
  string-keyed maps; sequentials and sets become lists. Mirrors cider-nrepl's
  `transform-value` without leaning on JVM class dispatch."
  [v]
  (cond
    (nil? v)        nil
    (string? v)     v
    (integer? v)    v
    (number? v)     (str v)
    (keyword? v)    (if-let [n (namespace v)] (str n "/" (name v)) (name v))
    (symbol? v)     (str v)
    ;; a nil map value is dropped: bencode has no nil, so it would arrive as an
    ;; empty string, and a client reads a missing key more reliably than an
    ;; empty one
    (map? v)        (reduce-kv (fn [m k val]
                                 (if (some? val)
                                   (assoc m (str (transform-value k)) (transform-value val))
                                   m))
                               {} (into {} v))
    (or (sequential? v) (set? v)) (mapv transform-value v)
    :else           (str v)))

(defn- status-list
  "nREPL statuses ride as a list of strings, always ending in \"done\"."
  [status]
  (let [->s (fn [s] (if (keyword? s) (name s) (str s)))
        given (cond (nil? status) []
                    (or (keyword? status) (string? status)) [(->s status)]
                    :else (mapv ->s status))]
    (if (some #{"done"} given) given (conj (vec given) "done"))))

(defn- error-response
  "The response CIDER/Calva read when an op throws: the message on :err, the
  exception class + message on :ex, and an :error status."
  [e]
  (let [msg (server/err-msg e)]
    {"status" ["done" "error"]
     "ex" (str msg)
     "err" (str msg "\n")}))

(defn respond
  "Send `m` (coerced for the wire) as a response to `request`, without a status."
  [request m]
  (server/respond request (transform-value m)))

(defn respond-status
  "Send just a status (e.g. :done) for `request`."
  [request status]
  (server/respond request {"status" (status-list status)}))

(defn- run-op [f request]
  (let [result (try (f request)
                    (catch :default e (error-response e)))]
    (when-not (= result async)
      ;; transform-value has already turned :status into "status" and its
      ;; keywords into strings, so the status is read back off the coerced map
      ;; rather than the raw one.
      (let [body (transform-value result)]
        (server/respond request (assoc body "status" (status-list (get body "status"))))))))

(defn op-middleware
  "Build a jolt.nrepl middleware from `ops`: a map of op name -> (fn [request]).
  Each op name is also accepted in its \"cider/\"-prefixed form, which newer
  CIDER versions send.

  An op runs on its session's worker thread when it has one, so a slow op (a test
  run) neither blocks the connection's reader — which serves every other op,
  `interrupt` included — nor escapes being interrupted."
  [ops]
  (let [table (reduce-kv (fn [m op f] (assoc m op f (str "cider/" op) f)) {} ops)]
    (apply server/register-ops! (keys ops))
    (fn [handler]
      (fn [request]
        (if-let [f (get table (get request "op"))]
          (when-not (session/submit! (get request "session")
                                     (get request "id")
                                     (:reply request)
                                     (fn [] (run-op f request)))
            (run-op f request))
          (handler request))))))

;; --- request coercion -------------------------------------------------------
;; Everything arrives as a string (or a list of strings) over bencode.

(defn as-sym [x]
  (cond (symbol? x) x
        (and (string? x) (seq x)) (symbol x)
        :else nil))

(defn request-ns
  "The namespace symbol a request is about, defaulting to the current one."
  [request]
  (or (as-sym (get request "ns")) (ns-name *ns*)))

(defn request-sym
  "The symbol a request is about. CIDER sends \"sym\", older clients \"symbol\"."
  [request]
  (as-sym (or (not-empty (str (or (get request "sym") "")))
              (not-empty (str (or (get request "symbol") ""))))))

(defn truthy?
  "bencode has no booleans: clients send \"true\"/1 for a set flag."
  [v]
  (boolean (and v (not= v "false") (not= v 0) (not= v ""))))
