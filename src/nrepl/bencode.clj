(ns nrepl.bencode
  "bencode encode/decode for the nREPL wire protocol. Values flow as latin1
  strings (1 char = 1 byte); text that may carry unicode is converted to/from
  UTF-8 at the boundary. Used by the client transport (and available to anyone
  speaking nREPL on jolt)."
  (:require [clojure.string :as str]))

(defn ->wire "string -> latin1 wire form (each byte one char)." [s]
  (String. (.getBytes (str s) "UTF-8") "ISO-8859-1"))
(defn wire-> "latin1 wire form -> string (UTF-8 decode)." [s]
  (String. (byte-array (map int s)) "UTF-8"))

(defn encode
  "Encode `v` (int / string / keyword / map / sequential / nil) to a bencode
  latin1 string."
  [v]
  (cond
    (integer? v) (str "i" v "e")
    (string? v)  (let [w (->wire v)] (str (count w) ":" w))
    (keyword? v) (let [w (->wire (name v))] (str (count w) ":" w))
    (map? v)     (str "d" (apply str (mapcat (fn [[k val]] [(encode (name k)) (encode val)])
                                             (sort-by #(name (first %)) v))) "e")
    (or (seq? v) (vector? v)) (str "l" (apply str (map encode v)) "e")
    (nil? v)     "0:"
    :else        (let [w (->wire (str v))] (str (count w) ":" w))))

(defn decode
  "Decode one bencode value from latin1 string `s` at index `i`. Returns
  [value next-index], or nil if `s` doesn't yet hold a complete value (so a
  caller can accumulate more bytes). Dict string keys are UTF-8 decoded."
  ([s] (decode s 0))
  ([s i]
   (when (< i (count s))
     (let [c (nth s i)]
       (cond
         (= c \i) (let [e (str/index-of s "e" i)]
                    (when e [(parse-long (subs s (inc i) e)) (inc e)]))
         (= c \l) (loop [j (inc i) acc []]
                    (cond (>= j (count s)) nil
                          (= (nth s j) \e) [acc (inc j)]
                          :else (let [r (decode s j)] (when r (recur (second r) (conj acc (first r)))))))
         (= c \d) (loop [j (inc i) acc {}]
                    (cond (>= j (count s)) nil
                          (= (nth s j) \e) [acc (inc j)]
                          :else (let [k (decode s j)]
                                  (when k (let [v (decode s (second k))]
                                            (when v (recur (second v) (assoc acc (first k) (first v)))))))))
         (and (char? c) (>= (int c) 48) (<= (int c) 57))   ; string: <len>:<bytes>
         (let [colon (str/index-of s ":" i)]
           (when colon
             (let [n (parse-long (subs s i colon)) start (inc colon) end (+ start n)]
               ;; UTF-8 decode the byte run so encode/decode round-trip on unicode.
               (when (<= end (count s)) [(wire-> (subs s start end)) end]))))
         :else nil)))))
