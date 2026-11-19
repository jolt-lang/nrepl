(ns nrepl.middleware.utf8-test
  "Request values are UTF-8 text by the time a middleware sees them (#26)."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.bencode :as bencode]
            [nrepl.core :as nrepl]
            [nrepl.middleware.utf8 :as utf8]
            [nrepl.test-helpers :as h]))

(deftest decodes-every-string-value-once
  (let [seen (atom nil)
        handler (utf8/wrap-utf8 (fn [req] (reset! seen req)))
        thai "สวัสดี"]
    (handler {"op" "lookup" "sym" (bencode/->wire thai) "ns" "user"
              "list" [(bencode/->wire "é") 1] "n" 3})
    (is (= thai (get @seen "sym")))
    (is (= "user" (get @seen "ns")))
    (is (= ["é" 1] (get @seen "list")))
    (is (= 3 (get @seen "n")))
    (testing "wrapping twice does not decode twice"
      ((utf8/wrap-utf8 (utf8/wrap-utf8 (fn [req] (reset! seen req))))
       {"op" "lookup" "sym" (bencode/->wire thai)})
      (is (= thai (get @seen "sym"))))
    (testing "the values jolt's built-in handler decodes itself stay on the wire"
      (let [w (bencode/->wire "(def é 1)")]
        (handler {"op" "eval" "code" w "file" w "file-path" w "file-name" w})
        (is (= [w w w w] (map @seen ["code" "file" "file-path" "file-name"])))))))

(deftest lookup-finds-a-non-ascii-symbol
  (let [t (h/conn)]
    (try
      (h/eval-code t "(def สวัสดี 42)" :ns "user")
      (let [info (:info (h/combine (h/message t {:op "lookup" :sym "สวัสดี" :ns "user"})))]
        (is (= "สวัสดี" (get info "name"))))
      (finally (nrepl/close t)))))

(deftest completions-take-a-non-ascii-prefix
  (let [t (h/conn)]
    (try
      (h/eval-code t "(def สวัสดี-ครับ 1)" :ns "user")
      (let [cs (:completions (h/combine (h/message t {:op "completions" :prefix "สวัส" :ns "user"})))]
        (is (some #(= "สวัสดี-ครับ" (get % "candidate")) cs)))
      (finally (nrepl/close t)))))

(deftest cider-info-finds-a-non-ascii-symbol
  (let [t (h/conn)]
    (try
      (h/eval-code t "(def ยินดี 1)" :ns "user")
      (let [r (h/combine (h/message t {:op "info" :sym "ยินดี" :ns "user"}))]
        (is (= "ยินดี" (:name r))))
      (finally (nrepl/close t)))))

(deftest session-eval-still-carries-unicode
  (let [t (h/conn)
        s (h/new-session t)]
    (try
      (is (= "สวัสดี" (first (h/values (h/eval-code t "\"สวัสดี\"" :session s)))))
      (finally (nrepl/close t)))))
