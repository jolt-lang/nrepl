(ns nrepl.test-runner
  "Runs the library's test suite under jolt (joltc -M:test). Requiring the test
  namespaces registers their deftests; clojure.test/run-tests runs them. The
  server (built-in handler + this library's middleware) is started lazily by
  nrepl.test-helpers the first time a test connects."
  (:require [clojure.test :as t]
            [nrepl.bencode-test]
            [nrepl.core-test]
            [nrepl.middleware.session-test]
            [nrepl.middleware.completion-test]
            [nrepl.middleware.lookup-test]
            [nrepl.middleware.interruptible-eval-test]))

(defn -main [& _]
  (let [r (t/run-tests)]
    (println (str "\n========== TOTAL =========="))
    (println (str "tests=" (:test r) " pass=" (:pass r) " fail=" (:fail r) " error=" (:error r)))
    (when (or (pos? (:fail r)) (pos? (:error r)))
      (throw (ex-info "test failures" (select-keys r [:fail :error]))))))
