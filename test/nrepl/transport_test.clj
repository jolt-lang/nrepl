(ns nrepl.transport-test
  "The client transport's reads are interruptible. jolt's Thread.interrupt does
  not reach a thread inside a blocking syscall, so a `recv` parked on a silent
  server used to run its whole receive timeout after an interrupt; it now waits
  in `interrupt-slice-ms` slices and checks the flag between them."
  (:require [clojure.test :refer [deftest is]]
            [nrepl.transport :as transport]))

(defn- silent-server
  "A listener that accepts and never answers: what a runaway eval looks like
  from the client's side."
  []
  (let [ss (java.net.ServerSocket. 0)
        held (atom [])]
    (future (try (loop [] (swap! held conj (.accept ss)) (recur))
                 (catch Throwable _ nil)))
    {:ss ss :port (.getLocalPort ss) :held held}))

(defn- stop [{:keys [ss]}] (try (.close ss) (catch Throwable _ nil)))

(deftest an-interrupt-unblocks-a-parked-recv
  (let [srv (silent-server)]
    (try
      (let [t (transport/connect "127.0.0.1" (:port srv) {:recv-timeout-secs 10})
            outcome (promise)
            th (Thread. (fn []
                          (let [t0 (System/currentTimeMillis)]
                            (deliver outcome
                                     (try [:returned (transport/recv t) 0]
                                          (catch Throwable e
                                            [(class e) nil (- (System/currentTimeMillis) t0)]))))))]
        (.start th)
        (Thread/sleep 300)
        (.interrupt th)
        (let [[cls _ elapsed] (deref outcome 5000 [:still-parked nil nil])]
          (is (= java.lang.InterruptedException cls)
              (str "the recv came back as an interrupt: " cls))
          (is (and elapsed (< elapsed 2000))
              (str "within a slice, not the 10 s receive timeout; took " elapsed "ms")))
        (transport/close t))
      (finally (stop srv)))))

(deftest a-sliced-recv-still-honours-the-receive-timeout
  (let [srv (silent-server)]
    (try
      (let [t (transport/connect "127.0.0.1" (:port srv) {:recv-timeout-secs 1})
            t0 (System/currentTimeMillis)
            r (transport/recv t)
            elapsed (- (System/currentTimeMillis) t0)]
        (is (nil? r) "a silent server still reads as closed at the timeout")
        (is (< 900 elapsed 4000) (str "near the 1 s timeout; took " elapsed "ms"))
        (transport/close t))
      (finally (stop srv)))))
