(ns nrepl.test-runner
  "Runs the library's test suite under jolt (joltc -M:test). Requiring the test
  namespaces registers their deftests; clojure.test/run-tests runs them. The
  server (built-in handler + this library's middleware) is started lazily by
  nrepl.test-helpers the first time a test connects."
  (:require [clojure.test :as t]
            [nrepl.test-helpers :as h]
            [nrepl.bencode-test]
            [nrepl.core-test]
            [nrepl.transport-test]
            [nrepl.middleware.session-test]
            [nrepl.middleware.completion-test]
            [nrepl.middleware.lookup-test]
            [nrepl.middleware.interruptible-eval-test]
            [nrepl.middleware.caught-test]
            [cider.nrepl.middleware.info-test]
            [cider.nrepl.middleware.complete-test]
            [cider.nrepl.middleware.ns-test]
            [cider.nrepl.middleware.test-test]
            [cider.nrepl.middleware.misc-test]))

(def ^:private suites
  ;; named: a bare (t/run-tests) runs only *ns*, which is this runner, so the
  ;; suite reported 0 tests and exited clean
  ['nrepl.bencode-test 'nrepl.core-test 'nrepl.transport-test
   'nrepl.middleware.session-test 'nrepl.middleware.completion-test
   'nrepl.middleware.lookup-test 'nrepl.middleware.interruptible-eval-test
   'nrepl.middleware.caught-test 'cider.nrepl.middleware.info-test
   'cider.nrepl.middleware.complete-test 'cider.nrepl.middleware.ns-test
   'cider.nrepl.middleware.test-test 'cider.nrepl.middleware.misc-test])

(defn -main [& _]
  (h/report-capabilities!)
  (let [r (apply t/run-tests suites)]
    (println (str "\n========== TOTAL =========="))
    (println (str "tests=" (:test r) " pass=" (:pass r) " fail=" (:fail r) " error=" (:error r)))
    ;; exit like clojure test runners: the test server and its sessions would
    ;; otherwise hold the process for the agent pool's keep-alive
    (System/exit (if (or (zero? (:test r)) (pos? (:fail r)) (pos? (:error r))) 1 0))))
