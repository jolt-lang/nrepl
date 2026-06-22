(ns nrepl.middleware.lookup-test
  "Ported from nrepl.middleware.lookup-test — symbol metadata."
  (:require [clojure.test :refer [deftest is]]
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
