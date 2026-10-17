(ns nrepl.middleware.session-test
  "Ported from nrepl.middleware.session-test — stateful, isolated sessions."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(defmacro with-conn [t & body]
  `(let [~t (h/conn)] (try ~@body (finally (nrepl/close ~t)))))

(deftest stateful-session
  (with-conn t
    (let [s (h/new-session t)]
      (h/eval-code t "(def counter 41)" :session s)
      (is (= 42 (h/eval-value t "(inc counter)" :session s))))))

(deftest per-session-current-ns
  (with-conn t
    (let [a (h/new-session t) b (h/new-session t)]
      (h/eval-code t "(ns aaa.alpha)" :session a)
      (is (= "aaa.alpha" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session a)))))
      (is (= "user" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session b))))))))

(deftest clone-copies-ns
  (with-conn t
    (let [a (h/new-session t)]
      (h/eval-code t "(ns bbb.beta)" :session a)
      (h/eval-code t "(+ 1 1)" :session a)            ;; record the ns
      (let [b (nrepl/new-session t :clone a)]
        (is (= "bbb.beta" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session b)))))))))

(deftest ls-and-close
  (with-conn t
    (let [s (h/new-session t)]
      (is (contains? (set (:sessions (h/combine (h/message t {:op "ls-sessions"})))) s))
      (h/message t {:op "close" :session s})
      (is (not (contains? (set (:sessions (h/combine (h/message t {:op "ls-sessions"})))) s))))))

(deftest eval-error-has-jvm-shape
  (when h/jvm-error-shape?
    (with-conn t
      (let [s (h/new-session t)
            rs (h/eval-code t "(/ 1 0)" :session s)
            c (h/combine rs)]
        (is (= "Execution error (ArithmeticException) at (REPL:1).\nDivide by zero\n" (:err c)))
        (is (= "class java.lang.ArithmeticException" (:ex c)))
        (is (= "class java.lang.ArithmeticException" (:root-ex c)))
        (is (contains? (:status c) "eval-error"))
        (is (contains? (:status c) "done"))
        (testing "done arrives on its own message, after eval-error, as on the JVM"
          (is (= ["done"] (get (last rs) :status))))))))

(deftest reader-error-has-no-stale-trace
  (when h/jvm-error-shape?
    (with-conn t
      (let [s (h/new-session t)
            c (h/combine (h/eval-code t "(defn broken [x]" :session s))]
        (is (re-find #"^Syntax error reading source at \(REPL:1:\d+\)\.\nEOF while reading\n$" (:err c)))
        (is (not (re-find #"trace:" (:err c))))))))

(deftest lazy-result-output-reaches-the-client
  (when h/jvm-error-shape?
    (with-conn t
      (let [s (h/new-session t)
            c (h/combine (h/eval-code t "(map (fn [x] (println \"side\" x) x) [1 2])" :session s))]
        (is (= "side 1\nside 2\n" (:out c)))
        (is (= ["(1 2)"] (:value c)))))))

(deftest eval-output-streams-as-it-is-flushed
  (with-conn t
    (let [s (h/new-session t)
          rs (h/eval-code t "(println \"first\") (Thread/sleep 200) (println \"second\") :ok" :session s)]
      (testing "each println is its own out message, not one batch at the end"
        (is (= ["first\n" "second\n"] (keep :out rs))))
      (testing "the output comes before the value"
        (is (< (.indexOf ^java.util.List (vec rs) (first (filter :out rs)))
               (.indexOf ^java.util.List (vec rs) (first (filter :value rs)))))))))

(deftest eval-err-output-reaches-the-client
  (with-conn t
    (let [s (h/new-session t)
          rs (h/eval-code t "(binding [*out* *err*] (println \"warned\")) :ok" :session s)]
      (is (= ["warned\n"] (keep :err rs)))
      (is (= [":ok"] (keep :value rs))))))
