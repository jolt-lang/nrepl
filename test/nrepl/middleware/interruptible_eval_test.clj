(ns nrepl.middleware.interruptible-eval-test
  "Ported from nrepl.middleware.interruptible-eval-test — interrupt aborts a
  running (compute-bound) eval and the session keeps serving on the SAME worker
  (the computation is not abandoned). A tight loop is used rather than
  Thread/sleep: a blocking foreign call only aborts when it returns to Scheme."
  (:require [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(deftest interrupt-aborts-running-eval
  (let [t (h/conn)]
    (try
      (let [s (h/new-session t)]
        ;; start a non-terminating compute loop without waiting (raw send)
        (h/send-raw t {:op "eval" :code "(loop [i 0] (recur (inc i)))" :session s :id "longrun"})
        (Thread/sleep 300)
        ;; interrupt it — the eval aborts at the next safe point
        (let [r (h/combine (h/message t {:op "interrupt" :session s}))]
          (is (contains? (:status r) "done")))
        ;; the SAME session/worker is usable again
        (is (= 2 (h/eval-value t "(+ 1 1)" :session s)))
        (is (= 6 (h/eval-value t "(* 2 3)" :session s))))
      (finally (nrepl/close t)))))

(deftest interrupt-idle-session
  (let [t (h/conn)]
    (try
      (let [s (h/new-session t)
            r (h/combine (h/message t {:op "interrupt" :session s}))]
        (is (contains? (:status r) "done")))
      (finally (nrepl/close t)))))
