(ns nrepl.test-helpers
  "Shared harness: one in-process server (built-in handler + this library's
  default middleware), and client helpers, for the ported tests."
  (:require [clojure.test :as test]
            [jolt.nrepl :as server]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport]))

(def port 7903)

;; --- host capabilities ------------------------------------------------------
;; A few ops answer better on a jolt carrying seams that 0.5.13 does not have.
;; The library degrades there rather than failing to load, so the tests covering
;; the better answers are skipped rather than failed — asserting them everywhere
;; would just mean a red suite on the released jolt the library supports.

(def jolt-nrepl-seams?
  "True when jolt.nrepl carries `register-version!`, the REPL history vars and
  `last-error-backtrace`. They arrived together, so one of them answers for all
  three: `describe` advertising a cider-nrepl version, `*e` holding the last
  exception, and the frames in `analyze-last-stacktrace`."
  (boolean (resolve 'jolt.nrepl/last-error-backtrace)))

(def report-values?
  "True when this jolt's clojure.test puts `:expected` / `:actual` on the report
  map instead of folding them into a rendered `:message`. The test ops report
  what the map carries, and retain a throwable `:actual` for `test-stacktrace`,
  so on a jolt that folds them there is nothing for either to find."
  (try
    (let [captured (atom nil)]
      (binding [test/report (fn [m] (reset! captured m))]
        (test/is (= 1 2)))
      (contains? @captured :expected))
    (catch :default _ false)))

(defn report-capabilities!
  "Print which tier the suite is running against, so a green run doesn't hide
  what it skipped."
  []
  (println (str ";; host capabilities: jolt.nrepl seams " (if jolt-nrepl-seams? "yes" "NO — those tests skip")
                ", clojure.test report values " (if report-values? "yes" "NO — those tests skip"))))

(defonce ^:private server-up
  (delay
    (future (server/start port ['nrepl.middleware/default-middleware
                                'cider.nrepl/cider-middleware]))
    :started))

(defn conn
  "Connect to the harness server, waiting for it to come up. The wait is a retry
  loop rather than a fixed sleep: `start` loads the middleware (orchard and
  compliment among it) before it binds the socket, which takes seconds on a cold
  cache and no fixed sleep is both fast and safe."
  []
  @server-up
  (loop [attempts 200]
    (let [t (try (nrepl/connect "127.0.0.1" port) (catch :default e (if (pos? attempts) nil (throw e))))]
      (or t (do (Thread/sleep 100) (recur (dec attempts)))))))

(defn eval-code
  [t code & {:keys [session ns]}]
  (nrepl/message t (cond-> {:op "eval" :code code} session (assoc :session session) ns (assoc :ns ns))))

(defn eval-value
  "Return the single read value of evaluating `code`."
  [t code & opts]
  (first (nrepl/response-values (apply eval-code t code opts))))

(defn send-and-collect
  "Send `msg` and return EVERY response read until that message is done — the
  unsolicited ones too. `message` keeps only the responses carrying the id it
  sent, but server output pushed by an out-subscribe arrives on the subscribing
  message's id, long after that message was done, so a test for it has to watch
  the socket itself. Responses come back with string keys, as they arrive."
  [t msg]
  (let [id (str "collect-" (rand-int 1000000))]
    (transport/send t (assoc msg :id id))
    (loop [acc []]
      (let [r (transport/recv t)]
        (cond
          (nil? r) acc
          (and (= id (get r "id")) (some #{"done"} (get r "status"))) (conj acc r)
          :else (recur (conj acc r)))))))

(def message nrepl/message)
(def new-session nrepl/new-session)
(def combine nrepl/combine-responses)
(def values nrepl/response-values)
(def send-raw transport/send)
