(ns nrepl.middleware.completion-test
  "Ported from nrepl.middleware.completion-test — prefix candidates."
  (:require [clojure.test :refer [deftest is]]
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
