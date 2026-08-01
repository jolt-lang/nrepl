(ns cider.nrepl.middleware.misc-test
  "macroexpand, undef, apropos, out-subscribe and the stacktrace op."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]))

;; --- macroexpand ------------------------------------------------------------

(deftest macroexpand-a-form
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "macroexpand" :code "(when true 1)" :ns "user"
                                       :expander "macroexpand-1"}))]
        (is (= "(if true (do 1))" (clojure.string/trim (:expansion r)))))
      (finally (nrepl/close t)))))

(deftest macroexpand-all
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "macroexpand" :code "(when true (when false 2))"
                                       :ns "user" :expander "macroexpand-all"}))]
        (is (= "(if true (do (if false (do 2))))" (clojure.string/trim (:expansion r)))))
      (finally (nrepl/close t)))))

(deftest macroexpand-resolves-in-the-requested-ns
  (testing "a macro that only that namespace refers to still expands"
    (let [t (h/conn)]
      (try
        (h/eval-code t "(ns macroexpand-test-target) (defmacro twice [x] (list (quote +) x x))" :ns "user")
        (let [r (h/combine (h/message t {:op "macroexpand" :code "(twice 21)"
                                         :ns "macroexpand-test-target"
                                         :expander "macroexpand-1"}))]
          (is (= "(+ 21 21)" (clojure.string/trim (:expansion r)))))
        (testing "and the session's own namespace is left where it was"
          (is (= "user" (h/eval-value t "(str (ns-name *ns*))" :ns "user"))))
        (finally (nrepl/close t))))))

(deftest macroexpand-display-namespaces
  (testing "'none' strips the namespace off a qualified symbol"
    (let [t (h/conn)]
      (try
        (let [r (h/combine (h/message t {:op "macroexpand" :code "(clojure.core/when true 1)"
                                         :ns "user" :expander "macroexpand-1"
                                         :display-namespaces "none"}))]
          (is (not (clojure.string/includes? (:expansion r) "clojure.core"))))
        (finally (nrepl/close t))))))

;; --- undef ------------------------------------------------------------------

(deftest undef-removes-a-var
  (let [t (h/conn)]
    (try
      (h/eval-code t "(ns undef-test-target) (def gone 1)" :ns "user")
      (is (= 1 (h/eval-value t "undef-test-target/gone" :ns "user")))
      (h/message t {:op "undef" :ns "undef-test-target" :sym "gone"})
      (let [r (h/combine (h/message t {:op "info" :ns "undef-test-target" :sym "gone"}))]
        (is (contains? (:status r) "no-info")))
      (finally (nrepl/close t)))))

;; --- apropos ----------------------------------------------------------------

(deftest apropos-finds-a-var-by-name
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "apropos" :query "reduce-kv"}))
            names (set (map #(get % "name") (:apropos-matches r)))]
        (is (contains? names "clojure.core/reduce-kv")))
      (finally (nrepl/close t)))))

(deftest apropos-is-case-insensitive-by-default
  (let [t (h/conn)]
    (try
      (let [r (h/combine (h/message t {:op "apropos" :query "REDUCE-KV"}))
            names (set (map #(get % "name") (:apropos-matches r)))]
        (is (contains? names "clojure.core/reduce-kv")))
      (finally (nrepl/close t)))))

;; --- stacktrace -------------------------------------------------------------

;; both read what jolt.nrepl recorded about the last failing eval — *e for the
;; cause chain, last-error-backtrace for the frames
(deftest analyze-last-stacktrace
  (when h/jolt-nrepl-seams?
    (let [t (h/conn)]
      (try
        (h/eval-code t "(throw (ex-info \"analyzed\" {:k :v}))" :ns "user")
        (let [r (h/combine (h/message t {:op "analyze-last-stacktrace"}))]
          (is (= "clojure.lang.ExceptionInfo" (:class r)))
          (is (= "analyzed" (:message r)))
          (is (= "{:k :v}" (:data r))))
        (finally (nrepl/close t))))))

(deftest analyze-last-stacktrace-frames
  (when h/jolt-nrepl-seams?
    (testing "frames carry the file and line jolt recorded at throw time"
      (let [t (h/conn)]
        (try
          (h/eval-code t "(ns stacktrace-test-target) (defn thrower [] (throw (ex-info \"framed\" {})))" :ns "user")
          (h/eval-code t "(stacktrace-test-target/thrower)" :ns "user")
          (let [r (h/combine (h/message t {:op "analyze-last-stacktrace"}))
                frames (:stacktrace r)]
            (is (= "framed" (:message r)))
            (is (some #(= "stacktrace-test-target/thrower" (get % "name")) frames)))
          (finally (nrepl/close t)))))))

;; --- out --------------------------------------------------------------------

(deftest out-subscribe-forwards-background-output
  (testing "output from a thread other than the eval's reaches a subscriber"
    (let [t (h/conn)]
      (try
        (let [session (h/new-session t)]
          (h/message t {:op "out-subscribe" :session session})
          ;; a future prints after its eval has already replied
          (h/eval-code t "(future (Thread/sleep 200) (println \"background line\"))"
                       :session session)
          (Thread/sleep 600)
          ;; the forwarded output rides the subscribing message's id, so read
          ;; every response, not just the ones answering the next request
          (let [responses (h/send-and-collect t {:op "eval" :code ":drain" :session session})
                out (apply str (keep #(get % "out") responses))]
            (is (clojure.string/includes? out "background line")))
          (h/message t {:op "out-unsubscribe" :session session}))
        (finally (nrepl/close t))))))

(deftest eval-output-is-not-forwarded-twice
  (testing "an eval's own output comes back once, with the eval"
    (let [t (h/conn)]
      (try
        (let [session (h/new-session t)]
          (h/message t {:op "out-subscribe" :session session})
          (let [responses (h/message t {:op "eval" :code "(println \"just once\")" :session session})
                out (apply str (keep :out responses))]
            (is (= 1 (count (re-seq #"just once" out)))))
          (h/message t {:op "out-unsubscribe" :session session}))
        (finally (nrepl/close t))))))
