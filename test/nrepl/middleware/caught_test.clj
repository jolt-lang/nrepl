(ns nrepl.middleware.caught-test
  "nrepl.middleware.caught (upstream ships no caught test of its own; these
  follow the port's middleware-test conventions). A hook runs for eval errors,
  chosen per request by fully-qualified var symbol; what the hook prints
  reaches the client; print? returns the printed error in the response.

  Options ride as full STRING keys — the client bencode encodes a
  namespaced-keyword key as just its :name, and eval-code drops unknown
  kwargs, so these send raw message maps."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

;; namespace-level, not ^:dynamic: the hook runs on the session worker thread,
;; which no binding from the test thread reaches
(def seen (atom []))

(defn record-caught
  "A caught-fn for tests: record the error, and print a marker (a hook's
  printing is forwarded to the client as an out response)."
  [e]
  (swap! seen conj e)
  (print "::record-caught ran::")
  (flush))

(defn- err-eval
  "Send an eval of `code` for `session` with extra string-keyed `opts`."
  [t session code opts]
  (h/message t (merge {"op" "eval" "code" code "session" session} opts)))

(defmacro with-conn [[t] & body]
  `(let [~t (h/conn)]
     (try ~@body (finally (nrepl/close ~t)))))

(deftest caught-fn-runs-for-eval-error
  (with-conn [t]
    (let [s (h/new-session t)]
      (reset! seen [])
      (let [r (h/combine (err-eval t s "(assert false \"boom\")"
                                  {"nrepl.middleware.caught/caught"
                                   "nrepl.middleware.caught-test/record-caught"}))]
        (is (= 1 (count @seen)) "the hook received the error")
        (is (re-find #"boom" (str (ex-message (first @seen)))))
        (is (contains? (:status r) "eval-error") "the eval-error reply still arrives")
        (is (nil? (:nrepl.middleware.caught/throwable r)) "the error is elided by default")))))

(deftest hook-printing-reaches-client
  (with-conn [t]
    (let [s (h/new-session t)]
      (reset! seen [])
      (let [r (h/combine (err-eval t s "(assert false \"boom\")"
                                  {"nrepl.middleware.caught/caught"
                                   "nrepl.middleware.caught-test/record-caught"}))]
        (is (re-find #"record-caught ran" (str (:out r)))
            "what the hook prints arrives as an out response")))))

(deftest unresolved-caught-symbol-errors
  (with-conn [t]
    (let [s (h/new-session t)]
      (reset! seen [])
      (let [r (h/combine (err-eval t s "(+ 1 1)"
                                   {"nrepl.middleware.caught/caught" "no.such/ns"}))]
        (is (contains? (:status r) "error"))
        (is (re-find #"Couldn't resolve var no.such/ns"
                     (str (:nrepl.middleware.caught/error r))))
        (is (= 2 (h/eval-value t "(+ 1 1)" :session s)) "the eval itself still runs")))))

(deftest print-returns-error-in-response
  (with-conn [t]
    (let [s (h/new-session t)]
      (reset! seen [])
      (let [r (h/combine (err-eval t s "(assert false \"boom\")"
                                  {"nrepl.middleware.caught/caught"
                                   "nrepl.middleware.caught-test/record-caught"
                                   "nrepl.middleware.caught/print?" "true"}))]
        (is (= 1 (count @seen)) "the hook ran")
        (is (re-find #"boom" (str (:nrepl.middleware.caught/throwable r)))
            "print? puts the printed error in the response")))))

(deftest no-caught-for-successful-eval
  (with-conn [t]
    (let [s (h/new-session t)]
      (reset! seen [])
      (is (= 2 (h/eval-value t "(+ 1 1)" :session s)))
      (is (empty? @seen)))))
