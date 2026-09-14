(ns nrepl.middleware.caught
  "Support for a hook for conveying errors interactively, akin to the `:caught`
  option of `clojure.main/repl`. Ported from nrepl.middleware.caught for jolt's
  middleware model: there is no Transport type to reify here, so `wrap-caught`
  wraps the request's `:reply` fn instead, and it must sit OUTERMOST — the
  session middleware replies from its worker thread through whatever :reply the
  request carries, so the wrap has to be in place before session sees the
  request.

  The session middleware attaches the error of a failed eval to the eval-error
  response under `throwable-key` (only when this middleware is in the stack —
  see `enabled-key`); this middleware passes it to the hook and elides it from
  what goes on the wire. The eval-error response itself already carries `err`
  and `ex`, so the DEFAULT hook prints to the server's own stderr rather than
  the client — a hook's print reaching the client is the job of the writer
  binding below, and the default must not duplicate what the response carries."
  (:require [jolt.nrepl :as server]))

;; the process stderr as of load: during a hook *err* is bound to a replying
;; writer (below), so the default hook writes here to reach the terminal
;; without echoing to the client, which already has the error in the response.
(def ^:private server-err *err*)

(defn repl-caught
  "The default hook: print the error's message to the server's stderr.
  `clojure.main/repl-caught` has no jolt equivalent (there is no clojure.main),
  so it lives here."
  [e]
  (binding [*out* server-err]
    (print (str (server/err-msg e) "\n"))
    (flush)))

(def ^:dynamic *caught-fn*
  "Function to use to convey interactive errors. Takes one argument, the error."
  repl-caught)

(def ^:private caught-key :nrepl.middleware.caught/caught)
(def ^:private caught-fn-key :nrepl.middleware.caught/caught-fn)
(def ^:private print?-key :nrepl.middleware.caught/print?)

(def throwable-key
  "The response slot carrying the error to convey: set by the session
  middleware on eval-error responses, dissoc'd from what goes on the wire
  (unless `print?`, which puts the printed message back as a string)."
  :nrepl.middleware.caught/throwable)

(def enabled-key
  "The request flag this middleware sets; the session middleware attaches
  `throwable-key` only when it is present, so a stack that omits wrap-caught
  never leaks the unencodable error object into a response."
  :nrepl.middleware.caught/enabled)

(defn- req-get
  "Options arrive from bencode clients as string keys, or keyword keys when a
  server-side middleware assoc's them onto the request (how wrap-rephrase
  does it)."
  [request kw]
  (or (get request kw)
      ;; (name kw) would strip the namespace — the wire key is the FULL
      ;; "some.namespace/name" string
      (get request (subs (str kw) 1))))

(defn- truthy?
  "bencode has no booleans: clients send \"true\"/1, and an empty list is
  logical false."
  [v]
  (not (or (nil? v) (= "" v) (= "false" v) (= "0" v)
           (= false v) (= [] v))))

(defn- req-contains?
  "Whether `kw` (or its full string form — see req-get) is present in
  `request`, regardless of value: distinguishes an option the request set
  from one it left to the response."
  [request kw]
  (or (contains? request kw)
      (contains? request (subs (str kw) 1))))

(defn- resolve-caught
  "The caught option names a var by fully-qualified symbol (a string over the
  wire, a symbol from server-side middleware). Replies an error status when it
  doesn't resolve; evaluation continues with the default hook."
  [request]
  (let [v (req-get request caught-key)
        var-sym (cond (symbol? v) v
                      (and (string? v) (seq v)) (symbol v))]
    (when var-sym
      (let [caught-var (try (requiring-resolve var-sym) (catch :default _ nil))]
        (when-not caught-var
          ;; no "done" here — the eval's own reply terminates the exchange
          ;; (upstream sends ::error without :done likewise)
          (server/respond request {"nrepl.middleware.caught/error"
                                   (str "Couldn't resolve var " var-sym)
                                   "status" ["error"]}))
        caught-var))))

(defn- replying-writer
  "A Writer whose writes go to the client as `kind` (\"out\"/\"err\")
  responses — how a hook's printing reaches the editor, jolt's analogue of the
  session-bound replying PrintWriter upstream hooks print to."
  [reply kind]
  (reify java.io.Writer
    (write [_ s] (reply {kind (str s)}))
    (flush [_] nil)
    (close [_] nil)))

(defn- strip-config
  "Remove this middleware's option slots from a response: a caught-fn is a
  function and would crash bencode, and the raw print? flag is internal.
  Upstream dissocs its configuration-keys from every response likewise."
  [resp]
  (dissoc resp caught-fn-key print?-key
          "nrepl.middleware.caught/caught-fn" "nrepl.middleware.caught/print?"))

(defn- caught-reply
  "Wrap `request`'s :reply: a response carrying `throwable-key` passes the
  error to the hook with *out*/*err* bound to replying writers, then goes on
  without it (with the printed message, when print?).

  Hook choice per response: `request-fn` (resolved from the request's options)
  wins; else a caught-fn the RESPONSE carries (upstream lets options ride the
  responses when the request set none); else the *caught-fn* default. print?
  follows the same request-over-response rule."
  [request {:keys [request-fn request-print?]}]
  (let [reply (:reply request)]
    (fn [resp]
      (if-let [e (get resp throwable-key)]
        (let [caught-fn (or request-fn
                            (let [v (get resp caught-fn-key)] (when (fn? v) v))
                            *caught-fn*)
              print? (if (nil? request-print?)
                       (truthy? (get resp print?-key))
                       request-print?)]
          (binding [*out* (replying-writer reply "out")
                    *err* (replying-writer reply "err")]
            (try (caught-fn e)
                 (catch :default ex
                   ;; a broken hook must not eat the eval-error response
                   (reply {"err" (str "caught hook error: " (server/err-msg ex) "\n")}))))
          (reply (cond-> (strip-config (dissoc resp throwable-key))
                    print? (assoc "nrepl.middleware.caught/throwable" (server/err-msg e)))))
        (reply (strip-config resp))))))

(defn wrap-caught
  "Middleware that provides a hook for any error that should be conveyed
  interactively (generally by printing).

  Returns a handler which calls the hook on the `throwable-key` slot of
  responses sent via the request's reply fn. While the hook runs, `*out*` and
  `*err*` are bound to writers that send what the hook prints to the client as
  out/err responses — so a hook like org.corfield.rephrase/repl-caught, which
  prints a friendlier message, works unchanged.

  Supports the following options, given in the request (string keys over the
  wire, keywords for server-side middleware):

  * `nrepl.middleware.caught/caught` — a fully-qualified symbol naming a var
  whose function to use to convey interactive errors. Must point to a function
  that takes one argument, the error. A symbol that doesn't resolve gets an
  error response and the default hook.

  * `nrepl.middleware.caught/caught-fn` — the function to use to convey
  interactive errors (server-side middleware only; functions can't ride
  bencode). Resolved from the above option if provided. Defaults to
  `*caught-fn*` (repl-caught).

  * `nrepl.middleware.caught/print?` — if logical true, the printed message of
  the error is returned in the response under
  \"nrepl.middleware.caught/throwable\" (otherwise the error is elided).
  Defaults to false.

  The caught-fn and print? options may also ride the RESPONSES sent via the
  request's reply fn (keyword keys) when the request didn't set them — how an
  inner middleware swaps in its own hook. Options in the request are preferred.
  Whatever hook is chosen, wrap-caught assocs it onto the request under
  `nrepl.middleware.caught/caught-fn` before calling the inner handler, so
  middleware further in (e.g. org.corfield.rephrase's wrap-rephrase pattern)
  sees a callable — mirroring upstream nREPL."
  [handler]
  (fn [request]
    (let [caught-var (resolve-caught request)
          fn-val (req-get request caught-fn-key)
          ;; nil = the request set no hook, so a response-level caught-fn wins
          request-fn (or caught-var
                         (when (fn? fn-val) fn-val))
          ;; the request always carries a callable for inner middleware
          caught-fn (or request-fn *caught-fn*)
          ;; nil = the request didn't set print?, so a response may
          request-print? (when (req-contains? request print?-key)
                           (truthy? (req-get request print?-key)))]
      (handler (assoc request
                      enabled-key true
                      caught-fn-key caught-fn
                      :reply (caught-reply request {:request-fn request-fn
                                                    :request-print? request-print?}))))))

;; alter-meta! with the same key nrepl.middleware/set-descriptor! writes,
;; rather than requiring nrepl.middleware: this ns is part of its
;; default-middleware, so the require would be a load cycle.
(alter-meta! #'wrap-caught
             assoc :nrepl.middleware/descriptor
             {:requires #{"clone"}
              :expects #{}
              :handles {}})
