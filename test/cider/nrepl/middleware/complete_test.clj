(ns cider.nrepl.middleware.complete-test
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(defn- candidates [r]
  (set (map #(get % "candidate") (:completions r))))

(deftest complete-vars
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "complete" :prefix "redu" :ns "clojure.core"}))
            cs (candidates r)]
        (is (contains? cs "reduce"))
        (is (contains? cs "reduce-kv"))
        (is (every? #(clojure.string/starts-with? % "redu") cs)))
      (finally (nrepl/close t)))))

(deftest complete-carries-metadata
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "complete" :prefix "reduce-kv" :ns "clojure.core"}))
            entry (first (:completions r))]
        (is (= "reduce-kv" (get entry "candidate")))
        (is (= "clojure.core" (get entry "ns"))))
      (finally (nrepl/close t)))))

(deftest complete-namespaces
  (let [t (h/conn)]
    (try
      (let [cs (candidates (h/combine (h/message t {:op "complete" :prefix "clojure.stri" :ns "user"})))]
        (is (contains? cs "clojure.string")))
      (finally (nrepl/close t)))))

(deftest complete-special-forms
  (let [t (h/conn)]
    (try
      (let [cs (candidates (h/combine (h/message t {:op "complete" :prefix "recu" :ns "user"})))]
        (is (contains? cs "recur")))
      (finally (nrepl/close t)))))

(deftest complete-locals-from-context
  (testing "a local binding in the surrounding form is a candidate"
    (let [t (h/conn)]
      (try
        (let [cs (candidates (h/combine
                              (h/message t {:op "complete" :prefix "local"
                                            :ns "user"
                                            :context "(let [local-thing 1] __prefix__)"})))]
          (is (contains? cs "local-thing")))
        (finally (nrepl/close t))))))

(deftest complete-no-matches
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "complete" :prefix "zzz-no-such-prefix" :ns "user"}))]
        (is (empty? (:completions r)))
        (is (contains? (:status r) "done")))
      (finally (nrepl/close t)))))

(deftest complete-doc
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "complete-doc" :sym "clojure.string/join" :ns "user"}))]
        (is (string? (:completion-doc r))))
      (finally (nrepl/close t)))))
