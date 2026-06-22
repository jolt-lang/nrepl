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
