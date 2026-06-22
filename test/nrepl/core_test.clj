(ns nrepl.core-test
  "Ported from nrepl.core-test — client/server eval round-trips."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(defmacro with-conn [t & body]
  `(let [~t (h/conn)] (try ~@body (finally (nrepl/close ~t)))))

(deftest eval-returns-value
  (with-conn t
    (is (= [3] (nrepl/response-values (h/eval-code t "(+ 1 2)"))))))

(deftest eval-multiple-forms-last-value
  (with-conn t
    (is (= 42 (h/eval-value t "(def a 6) (def b 7) (* a b)")))))

(deftest response-values-reads-data
  (with-conn t
    (is (= [{:a 1 :b [2 3]}] (nrepl/response-values (h/eval-code t "{:a 1 :b [2 3]}"))))))

(deftest combine-responses-merges-out-and-status
  (with-conn t
    (let [c (nrepl/combine-responses (h/eval-code t "(do (print \"hi\") (println \"!\") :ok)"))]
      (is (= "hi!\n" (:out c)))
      (is (contains? (:status c) "done")))))

(deftest eval-error-reports-status
  (with-conn t
    (let [c (nrepl/combine-responses (h/eval-code t "(throw (ex-info \"boom\" {}))"))]
      (is (contains? (:status c) "eval-error"))
      (is (contains? (:status c) "done")))))

(deftest code-macro
  (is (= "(+ 1 2)(* 3 4)" (nrepl/code (+ 1 2) (* 3 4)))))
