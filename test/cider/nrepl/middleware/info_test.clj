(ns cider.nrepl.middleware.info-test
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(deftest info-op-is-advertised
  ;; the probe every editor makes to decide whether the server speaks
  ;; cider-nrepl at all
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "describe"}))]
        (is (contains? (get r :ops) "info"))
        (is (contains? (get r :ops) "eldoc"))
        (is (contains? (get r :ops) "complete"))
        ;; CIDER also wants a cider-nrepl version, which needs jolt.nrepl's
        ;; register-version!; Calva goes by the op list alone
        (when h/jolt-nrepl-seams?
          (is (get-in r [:versions "cider-nrepl"]))))
      (finally (nrepl/close t)))))

(deftest info-for-a-var
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "info" :sym "join" :ns "clojure.string"}))]
        (is (= "join" (:name r)))
        (is (= "clojure.string" (:ns r)))
        (is (= "[coll]\n[separator coll]" (:arglists-str r)))
        (testing "a stdlib var still reports where it is defined"
          (is (= "clojure/string.clj" (:resource r)))
          (is (integer? (:line r))))
        (is (not (contains? (set (:status r)) "no-info"))))
      (finally (nrepl/close t)))))

(deftest info-for-a-special-form
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "info" :sym "if" :ns "user"}))]
        (is (= "if" (:name r)))
        (is (seq (:doc r)))
        (is (= "(if test then else?)" (:forms-str r))))
      (finally (nrepl/close t)))))

(deftest info-for-a-namespace
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "info" :sym "clojure.string" :ns "user"}))]
        (is (= "clojure.string" (:name r))))
      (finally (nrepl/close t)))))

(deftest info-for-an-alias
  (let [t (h/conn)]
    (try
      (h/eval-code t "(require '[clojure.string :as info-test-str])" :ns "user")
      (let [r (h/combine (h/message t {:op "info" :sym "info-test-str" :ns "user"}))]
        (is (= "clojure.string" (:name r))))
      (finally (nrepl/close t)))))

(deftest info-for-an-unknown-symbol
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "info" :sym "no-such-thing-here" :ns "user"}))]
        (is (contains? (set (:status r)) "no-info")))
      (finally (nrepl/close t)))))

(deftest info-locates-a-var-defined-over-the-connection
  ;; what go-to-definition needs: a path that exists, and the line it's on
  (let [t (h/conn)]
    (try
      (h/eval-code t "(ns info-test-target) (defn located \"doc\" [x] x)" :ns "user")
      (let [r (h/combine (h/message t {:op "info" :sym "located" :ns "info-test-target"}))]
        (is (= "doc" (:doc r)))
        (is (= "[x]" (:arglists-str r))))
      (finally (nrepl/close t)))))

(deftest eldoc-for-a-var
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "eldoc" :sym "join" :ns "clojure.string"}))]
        (is (= "join" (:name r)))
        (is (= [["coll"] ["separator" "coll"]] (:eldoc r))))
      (finally (nrepl/close t)))))

(deftest eldoc-for-an-unknown-symbol
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "eldoc" :sym "no-such-thing-here" :ns "user"}))]
        (is (contains? (set (:status r)) "no-eldoc")))
      (finally (nrepl/close t)))))

(deftest classify-symbols
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "classify-symbols" :ns "clojure.core"
                                       :symbols ["when" "map" "if" "no-such-thing-here"]}))
            c (:classification r)]
        (is (= "macro" (get c "when")))
        (is (= "function" (get c "map")))
        (is (= "special" (get c "if")))
        (testing "a symbol that doesn't resolve is left out"
          (is (nil? (get c "no-such-thing-here")))))
      (finally (nrepl/close t)))))
