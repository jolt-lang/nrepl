(ns nrepl.middleware.lookup-test
  "Ported from nrepl.middleware.lookup-test — symbol metadata."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.middleware.lookup]
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

(deftest lookup-file-is-what-jvm-nrepl-sends
  ;; JVM nREPL's lookup resolves a classpath-relative :file to its resource URL
  ;; and sends any other value as it is
  (let [file-str #'nrepl.middleware.lookup/file-str
        abs (str (.getAbsolutePath (io/file "src/nrepl/bencode.clj")))]
    (is (= (str (io/resource "nrepl/bencode.clj")) (file-str "nrepl/bencode.clj")))
    (is (= abs (file-str abs)))
    (is (= "NO_SOURCE_PATH" (file-str "NO_SOURCE_PATH")))))

(deftest lookup-says-where-the-var-was-defined
  ;; a jump-to-definition client reads these; JVM nREPL's lookup sends them
  (let [t (h/conn)]
    (try
      (let [info (:info (h/combine (h/message t {:op "lookup" :sym "wire->" :ns "nrepl.bencode"})))
            file (str (get info "file"))]
        ;; jolt through v0.8.23 records a loaded file's :file as an absolute
        ;; path, later ones classpath-relative as JVM Clojure does, so either
        ;; shape may come back; both name the source and both can be opened
        (testing "the file names the var's source, as a URL or a path a client can open"
          (is (str/ends-with? file "nrepl/bencode.clj"))
          (is (or (str/starts-with? file "file:") (.exists (io/file file)))))
        (testing "with the line and column of the def"
          (is (pos-int? (get info "line")))
          (is (pos-int? (get info "column")))))
      (finally (nrepl/close t)))))
