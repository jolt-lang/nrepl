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
            [nrepl.middleware.caught :as caught]
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

;; --- message plumbing (issue #6, seancorfield) ------------------------------
;;
;; Upstream wrap-caught assocs the hook onto the message under ::caught-fn
;; before calling the inner handler, so middleware further in (e.g.
;; org.corfield.rephrase, which sets ::caught and then calls the hook itself)
;; sees a callable — whether or not the request named a var. These hold the
;; port to that contract, plus upstream's rule that options may also ride the
;; RESPONSES when the request didn't set them.

(defn- reply-into [a]
  (fn [resp] (swap! a conj resp)))

(deftest caught-symbol-plumbs-fn-onto-message
  (let [inner-fn (atom nil)
        ;; rephrase's shape: outer middleware assocs ::caught, then the handler
        ;; inside wrap-caught reads ::caught-fn and calls it itself
        stack (caught/wrap-caught
               (fn [request]
                 (let [f (get request :nrepl.middleware.caught/caught-fn)]
                   (reset! inner-fn f)
                   (f (ex-info "boom" {}))
                   {"status" ["done"]})))]
    (reset! seen [])
    (stack {:reply (reply-into (atom []))
            :nrepl.middleware.caught/caught
            'nrepl.middleware.caught-test/record-caught})
    (is (or (var? @inner-fn) (fn? @inner-fn))
        "wrap-caught assocs the resolved hook (a var) onto the message")
    (is (= 1 (count @seen)) "the inner handler's call ran the resolved hook")))

(deftest caught-fn-value-and-default-plumb-onto-message
  (let [f (fn [_])
        seen-fn (atom nil)
        run (caught/wrap-caught
             (fn [request]
               (reset! seen-fn (get request :nrepl.middleware.caught/caught-fn))
               {"status" ["done"]}))]
    (run {:reply (reply-into (atom []))
          :nrepl.middleware.caught/caught-fn f})
    (is (identical? f @seen-fn) "a request-level caught-fn value passes through")
    (run {:reply (reply-into (atom []))})
    (is (fn? @seen-fn) "with no options the default hook is still assoc'd on")))

(deftest response-caught-fn-used-when-request-has-none
  (let [replies (atom [])
        ;; an inner middleware assocs ::caught-fn onto the eval-error response
        handler (caught/wrap-caught
                 (fn [request]
                   ((:reply request)
                    {:nrepl.middleware.caught/caught-fn record-caught
                     caught/throwable-key (ex-info "resp-hook" {})
                     "status" ["eval-error" "done"]})))
        wire (reply-into replies)]
    (reset! seen [])
    (handler {:reply wire})
    (is (= 1 (count @seen)) "the response's caught-fn ran (request set none)")
    (is (re-find #"resp-hook" (str (ex-message (first @seen)))))
    (is (not-any? #(contains? % :nrepl.middleware.caught/caught-fn) @replies)
        "the fn never goes on the wire")))

(deftest request-caught-wins-over-response-caught-fn
  (let [wrong-ran (atom 0)
        handler (caught/wrap-caught
                 (fn [request]
                   ((:reply request)
                    {:nrepl.middleware.caught/caught-fn (fn [_] (swap! wrong-ran inc))
                     caught/throwable-key (ex-info "locked" {})
                     "status" ["eval-error" "done"]})))]
    (reset! seen [])
    (handler {:reply (fn [_])
              :nrepl.middleware.caught/caught
              'nrepl.middleware.caught-test/record-caught})
    (is (zero? @wrong-ran) "the response's fn is ignored when the request named a var")
    (is (= 1 (count @seen)) "the request's resolved hook ran")))

(deftest response-print-returns-error-in-response
  (let [replies (atom [])
        handler (caught/wrap-caught
                 (fn [request]
                   ((:reply request)
                    {caught/throwable-key (ex-info "resp-print" {})
                     :nrepl.middleware.caught/print? true
                     "status" ["eval-error" "done"]})))
        wire (reply-into replies)]
    (handler {:reply wire})
    (let [r (some #(when (some #{"eval-error"} (get % "status")) %) @replies)]
      ;; raw replies keep string keys (h/combine keywordizes; reply-into doesn't)
      (is (re-find #"resp-print" (get r "nrepl.middleware.caught/throwable"))
          "print? on the response returns the printed error")
      (is (not-any? #(contains? % :nrepl.middleware.caught/print?) @replies)
          "the option key never goes on the wire"))))
