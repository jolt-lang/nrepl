(ns nrepl.middleware.interruptible-eval-test
  "Ported from nrepl.middleware.interruptible-eval-test — interrupt recovers a
  session. jolt can't kill a thread, so interrupt abandons the stuck eval; the
  test verifies the session is responsive again afterward."
  (:require [clojure.test :refer [deftest is]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

(deftest interrupt-recovers-session
  (let [t (h/conn)]
    (try
      (let [s (h/new-session t)]
        ;; start a long eval without waiting for it (raw send)
        (h/send-raw t {:op "eval" :code "(Thread/sleep 4000)" :session s :id "longrun"})
        (Thread/sleep 300)
        ;; interrupt it
        (let [r (h/combine (h/message t {:op "interrupt" :session s}))]
          (is (contains? (:status r) "done")))
        ;; the session is usable again immediately
        (is (= 2 (h/eval-value t "(+ 1 1)" :session s))))
      (finally (nrepl/close t)))))

(deftest interrupt-idle-session
  (let [t (h/conn)]
    (try
      (let [s (h/new-session t)
            r (h/combine (h/message t {:op "interrupt" :session s}))]
        (is (contains? (:status r) "done")))
      (finally (nrepl/close t)))))
