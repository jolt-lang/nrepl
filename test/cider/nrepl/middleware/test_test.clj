(ns cider.nrepl.middleware.test-test
  "The test ops, run against a namespace defined over the connection."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(def ^:private fixture-ns
  "A namespace with one passing, one failing and one erroring test, plus an
  :each fixture, defined through the REPL like an editor would."
  (str "(ns cider-test-fixture (:require [clojure.test :refer [deftest is testing use-fixtures]]))"
       "(def fixture-runs (atom 0))"
       "(use-fixtures :each (fn [f] (swap! fixture-runs inc) (f)))"
       "(deftest passing-test (testing \"context here\" (is (= 1 1))))"
       "(deftest failing-test (is (= 5 (+ 2 2))))"
       "(deftest erroring-test (is (= 1 (throw (ex-info \"kaboom\" {:why :test})))))"))

(defn- run-fixture-tests [t]
  (h/eval-code t fixture-ns :ns "user")
  (h/combine (h/message t {:op "test-var-query"
                           :var-query {"ns-query" {"exactly" ["cider-test-fixture"]}}})))

(deftest test-var-query-reports-each-assertion
  (let [t (h/conn)]
    (try
      (let [r (run-fixture-tests t)
            summary (:summary r)
            results (get-in (:results r) ["cider-test-fixture"])]
        (is (= 1 (get summary "pass")))
        (is (= 1 (get summary "fail")))
        (is (= 1 (get summary "error")))
        (is (= 3 (get summary "var")))

        (testing "a pass carries its testing context"
          (is (= "context here" (get (first (get results "passing-test")) "context"))))

        (testing "a failure carries the form and the evaluated values"
          (let [f (first (get results "failing-test"))]
            (is (= "fail" (get f "type")))
            (is (= "(= 5 (+ 2 2))" (get f "expected")))
            (is (= "(not (= 5 4))" (get f "actual")))))

        (testing "an error carries the exception class and message"
          (let [e (first (get results "erroring-test"))]
            (is (= "error" (get e "type")))
            (is (clojure.string/includes? (get e "error") "kaboom")))))
      (finally (nrepl/close t)))))

(deftest test-runs-fixtures
  (let [t (h/conn)]
    (try
      (run-fixture-tests t)
      (is (= 3 (h/eval-value t "@cider-test-fixture/fixture-runs")))
      (finally (nrepl/close t)))))

(deftest retest-runs-only-what-failed
  (let [t (h/conn)]
    (try
      (run-fixture-tests t)
      (let [r (h/combine (h/message t {:op "retest"}))
            results (get-in (:results r) ["cider-test-fixture"])]
        (is (contains? results "failing-test"))
        (is (contains? results "erroring-test"))
        (is (not (contains? results "passing-test"))))
      (finally (nrepl/close t)))))

(deftest test-stacktrace-finds-the-exception
  (let [t (h/conn)]
    (try
      (run-fixture-tests t)
      (let [r (h/combine (h/message t {:op "test-stacktrace" :ns "cider-test-fixture"
                                       :var "erroring-test" :index 0}))]
        (is (= "clojure.lang.ExceptionInfo" (:class r)))
        (is (= "kaboom" (:message r)))
        (is (= "{:why :test}" (:data r))))
      (finally (nrepl/close t)))))

(deftest test-stacktrace-without-an-error
  (let [t (h/conn)]
    (try
      (run-fixture-tests t)
      (let [r (h/combine (h/message t {:op "test-stacktrace" :ns "cider-test-fixture"
                                       :var "passing-test" :index 0}))]
        (is (contains? (:status r) "no-error")))
      (finally (nrepl/close t)))))

(deftest test-op-narrows-to-named-tests
  (let [t (h/conn)]
    (try
      (h/eval-code t fixture-ns :ns "user")
      (let [r (h/combine (h/message t {:op "test" :ns "cider-test-fixture"
                                       :tests ["passing-test"]}))]
        (is (= 1 (get (:summary r) "pass")))
        (is (= 1 (get (:summary r) "var"))))
      (finally (nrepl/close t)))))
