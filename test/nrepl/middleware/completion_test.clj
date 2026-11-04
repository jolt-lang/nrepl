(ns nrepl.middleware.completion-test
  "Ported from nrepl.middleware.completion-test — prefix candidates."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(deftest completions-prefix
  (let [t (h/conn)]
    (try
      (let [cands (set (map #(get % "candidate")
                            (:completions (h/combine (h/message t {:op "completions"
                                                                   :prefix "redu"
                                                                   :ns "clojure.core"})))))]
        (is (contains? cands "reduce"))
        (is (contains? cands "reductions"))
        (is (not (contains? cands "map"))))
      (finally (nrepl/close t)))))

(deftest completions-empty-prefix-nonempty
  (let [t (h/conn)]
    (try
      (is (pos? (count (:completions (h/combine (h/message t {:op "completions" :prefix "ma" :ns "clojure.core"}))))))
      (finally (nrepl/close t)))))

(defn- candidates [t prefix ns]
  (set (map #(get % "candidate")
            (:completions (h/combine (h/message t {:op "completions" :prefix prefix :ns ns}))))))

(deftest completions-scoped-by-alias-and-namespace
  (let [t (h/conn)]
    (try
      (h/eval-value t "(ns completion-alias-probe (:require [clojure.string :as str]))")
      (testing "through an alias of the request's namespace"
        (let [cands (candidates t "str/jo" "completion-alias-probe")]
          (is (contains? cands "str/join"))
          (is (every? #(str/starts-with? % "str/") cands))))
      (testing "through a namespace name, from a namespace with no alias for it"
        (is (contains? (candidates t "clojure.string/jo" "user") "clojure.string/join")))
      (testing "an alias only means something in the namespace that has it"
        (is (empty? (candidates t "str/jo" "user"))))
      (testing "a scope naming nothing has no candidates"
        (is (empty? (candidates t "no.such.ns/x" "user"))))
      (testing "the `/` fn itself is not a scope"
        (is (contains? (candidates t "/" "clojure.core") "/")))
      (finally (nrepl/close t)))))

(deftest completions-offer-aliases-and-namespaces
  (let [t (h/conn)]
    (try
      (h/eval-value t "(ns completion-alias-probe (:require [clojure.string :as str]))")
      (is (contains? (candidates t "st" "completion-alias-probe") "str/"))
      (is (contains? (candidates t "clojure.str" "user") "clojure.string"))
      (finally (nrepl/close t)))))
