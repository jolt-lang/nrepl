(ns cider.nrepl.middleware.ns-test
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(deftest ns-list-includes-loaded-namespaces
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "ns-list"}))]
        (is (contains? (set (:ns-list r)) "clojure.string"))
        (is (contains? (set (:ns-list r)) "cider.nrepl")))
      (finally (nrepl/close t)))))

(deftest ns-vars-lists-publics
  (let [t (h/conn)]
    (try
      (let [vs (set (:ns-vars (h/combine (h/message t {:op "ns-vars" :ns "clojure.string"}))))]
        (is (contains? vs "join"))
        (is (contains? vs "split")))
      (finally (nrepl/close t)))))

(deftest ns-vars-with-meta
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "ns-vars-with-meta" :ns "clojure.string"}))
            join (get (:ns-vars-with-meta r) "join")]
        (is (= "([coll] [separator coll])" (get join "arglists"))))
      (finally (nrepl/close t)))))

(deftest ns-path-of-a-loaded-namespace
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "ns-path" :ns "nrepl.bencode"}))]
        (is (clojure.string/ends-with? (:path r) "src/nrepl/bencode.clj"))
        (testing "the path is one the editor can open"
          (is (clojure.string/starts-with? (:path r) "/"))
          (is (not (clojure.string/includes? (:path r) "/./")))))
      (finally (nrepl/close t)))))

(deftest ns-aliases
  (let [t (h/conn)]
    (try
      (h/eval-code t "(ns ns-test-aliased (:require [clojure.string :as s]))" :ns "user")
      (let [r (h/combine (h/message t {:op "ns-aliases" :ns "ns-test-aliased"}))]
        (is (= "clojure.string" (get (:ns-aliases r) "s"))))
      (finally (nrepl/close t)))))

(deftest classpath-lists-the-source-roots
  (let [t (h/conn)]
    (try
      (let [cp (:classpath (h/combine (h/message t {:op "classpath"})))]
        (is (seq cp))
        (is (some #(clojure.string/ends-with? % "/src") cp))
        (testing "project roots are absolute, so a client can stat them"
          (is (some #(clojure.string/starts-with? % "/") cp))))
      (finally (nrepl/close t)))))
