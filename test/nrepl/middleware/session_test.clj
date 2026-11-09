(ns nrepl.middleware.session-test
  "Ported from nrepl.middleware.session-test — stateful, isolated sessions."
  (:require [clojure.test :refer [deftest is testing]]
            [nrepl.core :as nrepl]
            [nrepl.test-helpers :as h]
            [nrepl.transport :as transport]))

(defmacro with-conn [t & body]
  `(let [~t (h/conn)] (try ~@body (finally (nrepl/close ~t)))))

(deftest stateful-session
  (with-conn t
    (let [s (h/new-session t)]
      (h/eval-code t "(def counter 41)" :session s)
      (is (= 42 (h/eval-value t "(inc counter)" :session s))))))

(deftest per-session-current-ns
  (with-conn t
    (let [a (h/new-session t) b (h/new-session t)]
      (h/eval-code t "(ns aaa.alpha)" :session a)
      (is (= "aaa.alpha" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session a)))))
      (is (= "user" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session b))))))))

(deftest clone-copies-ns
  (with-conn t
    (let [a (h/new-session t)]
      (h/eval-code t "(ns bbb.beta)" :session a)
      (h/eval-code t "(+ 1 1)" :session a)            ;; record the ns
      (let [b (nrepl/new-session t :clone a)]
        (is (= "bbb.beta" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session b)))))))))

(deftest ls-and-close
  (with-conn t
    (let [s (h/new-session t)]
      (is (contains? (set (:sessions (h/combine (h/message t {:op "ls-sessions"})))) s))
      (h/message t {:op "close" :session s})
      (is (not (contains? (set (:sessions (h/combine (h/message t {:op "ls-sessions"})))) s))))))

(deftest eval-error-has-jvm-shape
  (when h/jvm-error-shape?
    (with-conn t
      (let [s (h/new-session t)
            rs (h/eval-code t "(/ 1 0)" :session s)
            c (h/combine rs)]
        (is (= "Execution error (ArithmeticException) at (REPL:1).\nDivide by zero\n" (:err c)))
        (is (= "class java.lang.ArithmeticException" (:ex c)))
        (is (= "class java.lang.ArithmeticException" (:root-ex c)))
        (is (contains? (:status c) "eval-error"))
        (is (contains? (:status c) "done"))
        (testing "done arrives on its own message, after eval-error, as on the JVM"
          (is (= ["done"] (get (last rs) :status))))))))

(deftest reader-error-has-no-stale-trace
  (when h/jvm-error-shape?
    (with-conn t
      (let [s (h/new-session t)
            c (h/combine (h/eval-code t "(defn broken [x]" :session s))]
        (is (re-find #"^Syntax error reading source at \(REPL:1:\d+\)\.\nEOF while reading\n$" (:err c)))
        (is (not (re-find #"trace:" (:err c))))))))

(deftest lazy-result-output-reaches-the-client
  (when h/jvm-error-shape?
    (with-conn t
      (let [s (h/new-session t)
            c (h/combine (h/eval-code t "(map (fn [x] (println \"side\" x) x) [1 2])" :session s))]
        (is (= "side 1\nside 2\n" (:out c)))
        (is (= ["(1 2)"] (:value c)))))))

(deftest eval-output-streams-as-it-is-flushed
  (with-conn t
    (let [s (h/new-session t)
          rs (h/eval-code t "(println \"first\") (Thread/sleep 200) (println \"second\") :ok" :session s)]
      (testing "each println is its own out message, not one batch at the end"
        (is (= ["first\n" "second\n"] (keep :out rs))))
      (testing "the output comes before the value"
        (is (< (.indexOf ^java.util.List (vec rs) (first (filter :out rs)))
               (.indexOf ^java.util.List (vec rs) (first (filter :value rs)))))))))

(deftest eval-err-output-reaches-the-client
  (with-conn t
    (let [s (h/new-session t)
          rs (h/eval-code t "(binding [*out* *err*] (println \"warned\")) :ok" :session s)]
      (is (= ["warned\n"] (keep :err rs)))
      (is (= [":ok"] (keep :value rs))))))

(defn- runtime-loads?
  "Whether this jolt's evaluate takes :load (0.8.24 on); an older one ignores
  the key, and its load-file then switches the session as an eval does."
  []
  (= "user" (:ns (jolt.nrepl/evaluate "(ns zzz.load-probe)" "user" {:load true}))))

(deftest load-file-keeps-the-session-ns
  ;; A load-file op is a load, as on JVM nREPL: the file's ns form switches only
  ;; while it loads, so the session's next eval is back where it was.
  (when (runtime-loads?)
   (with-conn t
    (let [s (h/new-session t)]
      (h/eval-code t "(ns ccc.gamma)" :session s)
      (let [r (h/combine (h/message t {:op "load-file" :session s
                                       :file "(ns ccc.loaded) (defn f [] 7)"
                                       :file-name "loaded.clj" :file-path "loaded.clj"}))]
        (is (= 7 (h/eval-value t "(ccc.loaded/f)" :session s)))
        (is (not= "ccc.loaded" (:ns r))))
      (is (= "ccc.gamma" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session s)))))))))

(defn- runtime-takes-file?
  "Whether this jolt's evaluate takes :file, the code's source path (after
  0.8.23); an older one ignores the key, and *file* stays unbound to a path."
  []
  (= "\"/tmp/zzz/file_probe.clj\""
     (:value (jolt.nrepl/evaluate "*file*" "user" {:file "/tmp/zzz/file_probe.clj"}))))

(deftest load-file-passes-its-file-path
  ;; As JVM nREPL compiles a load-file under its file-path: *file* is the path
  ;; while it loads, and a def records it as :file.
  (when (runtime-takes-file?)
   (with-conn t
    (let [s (h/new-session t)]
      (h/combine (h/message t {:op "load-file" :session s
                               :file "(ns ccc.pathed) (def here *file*) (defn g [] 1)"
                               :file-name "pathed.clj" :file-path "/tmp/ccc/pathed.clj"}))
      (is (= "/tmp/ccc/pathed.clj" (h/eval-value t "ccc.pathed/here" :session s)))
      (is (= "/tmp/ccc/pathed.clj" (h/eval-value t "(:file (meta #'ccc.pathed/g))" :session s)))))))

;; --- stdin --------------------------------------------------------------------

(defn- recv-until
  "Every response read up to and including the first one matching `pred`."
  [t pred]
  (loop [acc []]
    (let [r (transport/recv t)]
      (cond (nil? r) acc
            (pred r) (conj acc r)
            :else (recur (conj acc r))))))

(defn- status-on [id status]
  (fn [r] (and (= id (get r "id")) (some #{status} (get r "status")))))

(defn- value-on [rs id]
  (some #(when (= id (get % "id")) (get % "value")) rs))

(deftest read-line-reads-what-stdin-sends
  (with-conn t
    (let [s (h/new-session t)]
      (h/send-raw t {:op "eval" :code "(read-line)" :session s :id "rl"})
      (testing "a read with nothing pending asks for input on the eval's message"
        (is (some (status-on "rl" "need-input") (recv-until t (status-on "rl" "need-input")))))
      (h/send-raw t {:op "stdin" :stdin "hello\n" :session s :id "in"})
      (is (= "\"hello\"" (value-on (recv-until t (status-on "rl" "done")) "rl"))))))

(deftest an-empty-stdin-is-end-of-input
  (with-conn t
    (let [s (h/new-session t)]
      (h/send-raw t {:op "eval" :code "(read-line)" :session s :id "eof"})
      (recv-until t (status-on "eof" "need-input"))
      (h/send-raw t {:op "stdin" :stdin "" :session s :id "in"})
      (is (= "nil" (value-on (recv-until t (status-on "eof" "done")) "eof"))))))

(deftest input-sent-ahead-is-read-without-asking
  (with-conn t
    (let [s (h/new-session t)]
      (is (contains? (:status (h/combine (h/message t {:op "stdin" :stdin "(+ 1 2)\n" :session s})))
                     "done"))
      (h/send-raw t {:op "eval" :code "(read)" :session s :id "rd"})
      (let [rs (recv-until t (status-on "rd" "done"))]
        (is (= "(+ 1 2)" (value-on rs "rd")))
        (is (not-any? (status-on "rd" "need-input") rs))))))

(deftest interrupt-ends-a-read-waiting-for-input
  (with-conn t
    (let [s (h/new-session t)]
      (h/send-raw t {:op "eval" :code "(read-line)" :session s :id "wait"})
      (recv-until t (status-on "wait" "need-input"))
      (h/send-raw t {:op "interrupt" :session s :interrupt-id "wait" :id "int"})
      (is (some (status-on "wait" "interrupted") (recv-until t (status-on "wait" "done"))))
      (testing "the session's worker keeps serving"
        (is (= 2 (h/eval-value t "(+ 1 1)" :session s)))))))

(deftest explicit-ns-does-not-move-the-session
  ;; as JVM nREPL restores the session's *ns* after an eval that named one
  (with-conn t
    (let [s (h/new-session t)]
      (h/eval-code t "(ns ccc.gamma)" :session s)
      (is (= "clojure.string" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session s :ns "clojure.string")))))
      (h/eval-code t "(in-ns 'ccc.elsewhere)" :session s :ns "user")
      (is (= "ccc.gamma" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session s))))))))

(deftest namespace-not-found
  ;; JVM nREPL's answer for an ns that does not exist; the code is not run
  (with-conn t
    (let [s (h/new-session t)
          r (h/combine (h/eval-code t "(def ddd-stray 1)" :session s :ns "ddd.no-such-ns"))]
      (is (= #{"error" "namespace-not-found" "done"} (set (:status r))))
      (is (= "ddd.no-such-ns" (:ns r)))
      (is (nil? (find-ns 'ddd.no-such-ns)))
      (is (= "user" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session s))))))))

(deftest removed-session-ns-starts-in-user
  (with-conn t
    (let [s (h/new-session t)]
      (h/eval-code t "(ns eee.gone)" :session s)
      (h/eval-code t "(remove-ns 'eee.gone)" :session s :ns "user")
      (is (= "user" (:ns (h/combine (h/eval-code t "(+ 1 1)" :session s))))))))
