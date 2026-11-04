(ns nrepl.middleware.lookup-test
  "Ported from nrepl.middleware.lookup-test — symbol metadata."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(deftest lookup-returns-name-and-ns
  (let [t (h/conn)]
    (try
      (let [info (:info (h/combine (h/message t {:op "lookup" :sym "map" :ns "clojure.core"})))]
        (is (= "map" (get info "name")))
        (is (= "clojure.core" (get info "ns"))))
      (finally (nrepl/close t)))))

(deftest lookup-unknown-symbol
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "lookup" :sym "nope-not-a-var" :ns "clojure.core"}))]
        (is (contains? (:status r) "done"))
        (is (nil? (:info r))))
      (finally (nrepl/close t)))))

(deftest lookup-says-where-the-var-was-defined
  ;; a jump-to-definition client reads these; JVM nREPL's lookup sends them
  (let [t (h/conn)]
    (try
      (let [info (:info (h/combine (h/message t {:op "lookup" :sym "wire->" :ns "nrepl.bencode"})))]
        (testing "a classpath-relative :file comes back as its resource URL"
          (is (= (str (io/resource "nrepl/bencode.clj")) (get info "file")))
          (is (str/starts-with? (str (get info "file")) "file:")))
        (testing "with the line and column of the def"
          (is (pos-int? (get info "line")))
          (is (pos-int? (get info "column")))))
      (finally (nrepl/close t)))))
