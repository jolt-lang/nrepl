(ns nrepl.bencode-test
  "Ported from nrepl.bencode-test — adapted to jolt's string-based encode/decode."
  (:require [clojure.test :refer [deftest is are testing]]
            [nrepl.bencode :as bencode]))

(defn- roundtrip [v] (first (bencode/decode (bencode/encode v))))

(deftest integer-roundtrip
  (are [n] (= n (roundtrip n))
    0 1 -1 42 1234567890))

(deftest string-roundtrip
  (are [s] (= s (roundtrip s))
    "" "a" "hello" "spaces and stuff" "with:colons:and1234numbers"))

(deftest unicode-string-roundtrip
  (is (= "naïve ☃ ßÒ" (roundtrip "naïve ☃ ßÒ"))))

(deftest list-roundtrip
  (is (= [] (roundtrip [])))
  (is (= ["a" "b"] (roundtrip ["a" "b"])))
  (is (= [1 2 3] (roundtrip [1 2 3]))))

(deftest map-roundtrip
  (is (= {"a" "b"} (roundtrip {"a" "b"})))
  (is (= {"op" "eval" "id" "7" "code" "(+ 1 2)"}
         (roundtrip {"op" "eval" "id" "7" "code" "(+ 1 2)"})))
  (is (= {"status" ["done"]} (roundtrip {"status" ["done"]}))))

(deftest partial-decode-returns-nil
  (testing "a buffer without a complete value decodes to nil"
    (is (nil? (bencode/decode "5:ab")))      ;; string length 5, only 2 bytes
    (is (nil? (bencode/decode "i42")))       ;; integer without terminator
    (is (nil? (bencode/decode "d2:op")))))   ;; dict missing the value

(deftest decode-returns-next-index
  (let [s (str (bencode/encode {"a" 1}) (bencode/encode {"b" 2}))
        [m1 i] (bencode/decode s 0)
        [m2 _] (bencode/decode s i)]
    (is (= {"a" 1} m1))
    (is (= {"b" 2} m2))))
