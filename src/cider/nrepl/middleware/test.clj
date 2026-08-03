(ns cider.nrepl.middleware.test
  "The test ops: `test-var-query`, `test`, `test-all`, `retest` and
  `test-stacktrace` — running clojure.test from the editor and getting the
  results back per assertion, so failures land on the right line.

  Results are collected by rebinding clojure.test/report, the same extension
  point clojure.test itself documents, and are kept per namespace and var so a
  later `retest` can re-run only what didn't pass and `test-stacktrace` can look
  up the exception an erroring assertion threw.

  The test execution below re-implements test-var / test-vars / test-ns rather
  than calling clojure.test's: the report events an editor needs
  (:begin-test-var, :end-test-var, per-var timing) are emitted here, and jolt
  keeps its fixtures in registries rather than in namespace metadata."
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [cider.nrepl.middleware.classpath :as cp]
            [cider.nrepl.middleware.stacktrace :as stacktrace]
            [cider.nrepl.middleware.util :as util]))

;; --- report collection ------------------------------------------------------

(def current-report
  "The run in progress."
  (atom nil))

(def results
  "The most recent result for each namespace/var, for retest and test-stacktrace."
  (atom {}))

(defn report-reset! []
  (reset! current-report {:summary {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0}
                          :results {}
                          :testing-ns nil}))

(def ^:private unknown-var ::unknown)

(defn- print-object [x]
  (str/trim-newline (pr-str x)))

(defn- test-result
  "One assertion's result, in the shape cider-nrepl sends: the report map plus
  where it happened (ns, var, index within the var, `testing` context)."
  [ns v m]
  (let [{:keys [actual expected] t :type} m
        v-name (or (:name (meta v)) unknown-var)
        context (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        index (count (get-in (:results @current-report {}) [ns v-name]))]
    (merge (dissoc m :expected :actual :error :form)
           {:ns ns :var v-name :index index :context context}
           (when (#{:fail :error} t) {:expected (print-object expected)})
           (when (= :fail t) {:actual (print-object actual)})
           (when (= :error t)
             {:error (if (instance? Throwable actual)
                       (str (stacktrace/class-name (class actual)) ": "
                            (or (ex-message actual) actual))
                       (str actual))})
           ;; the throwable itself never goes on the wire; test-stacktrace looks
           ;; it up here afterwards
           (when (and (= :error t) (instance? Throwable actual))
             {::throwable actual}))))

(defn- record-result! [m]
  (let [ns (or (some-> (:ns m) ns-name) (:testing-ns @current-report))
        ;; innermost: *testing-vars* is built with conj, so the var under test
        ;; is at the front
        v (first test/*testing-vars*)
        v-name (or (:name (meta v)) unknown-var)]
    (swap! current-report
           #(-> %
                (update-in [:summary :test] inc)
                (update-in [:summary (:type m)] (fnil inc 0))
                (update-in [:results ns v-name] (fnil conj []) (test-result ns v m))))))

(defn report
  "The clojure.test reporter for a run driven from the editor."
  [m]
  (case (:type m)
    :begin-test-ns (let [ns (or (some-> (:ns m) ns-name) (:testing-ns @current-report))]
                     (swap! current-report #(-> % (assoc :testing-ns ns)
                                                (update-in [:summary :ns] inc))))
    :begin-test-var (swap! current-report update-in [:summary :var] inc)
    :end-test-var nil
    :end-test-ns nil
    (:pass :fail :error) (record-result! m)
    nil))

;; --- execution --------------------------------------------------------------

(defn- fixtures
  "jolt's clojure.test keeps fixtures in registries keyed by namespace symbol
  (the JVM keeps them in namespace metadata); read both so a namespace loaded
  either way runs with its fixtures."
  [ns-sym kind]
  (let [registry-var (case kind
                       :once (resolve 'clojure.test/once-fixtures)
                       :each (resolve 'clojure.test/each-fixtures))
        registry (when registry-var (deref (deref registry-var)))
        meta-key (case kind :once :clojure.test/once-fixtures :each :clojure.test/each-fixtures)]
    (or (seq (get registry ns-sym))
        (seq (get (meta (find-ns ns-sym)) meta-key))
        [])))

(defn test-var
  "Run the test attached to `v`, reporting when it starts, when it ends, and how
  long it took. An exception escaping the test body (rather than an assertion)
  is reported as an error against the var."
  [v]
  (when-let [t (:test (meta v))]
    ;; A FRESH var stack, not a conj onto whatever the calling thread had. This
    ;; runs a test on a client's behalf, so the client's own test context is not
    ;; an enclosing scope — and under a runtime that conveys dynamic bindings to
    ;; the session thread, inheriting it put the client's var in this list and
    ;; every result was filed under that name.
    (binding [test/*testing-vars* (list v)]
      (test/do-report {:type :begin-test-var :var v})
      (let [started (System/currentTimeMillis)
            result (try (t) ::ok (catch :default e e))
            elapsed (- (System/currentTimeMillis) started)]
        (when-not (= result ::ok)
          (test/do-report {:type :error :fault true :expected nil :actual result
                           :message "Uncaught exception, not in assertion"}))
        (test/do-report {:type :end-test-var :var v
                         :var-elapsed-time {:ms elapsed
                                            :humanized (str "Completed in " elapsed " ms")}})))))

(defn- wrap-fixtures [fs body-fn]
  (if (empty? fs)
    (body-fn)
    ((first fs) (fn [] (wrap-fixtures (rest fs) body-fn)))))

(defn test-ns
  "Run `vars` (the test vars of namespace `ns-sym`) inside that namespace's
  fixtures, collecting into the current report."
  [ns-sym vars]
  (binding [test/report report]
    (test/do-report {:type :begin-test-ns :ns (find-ns ns-sym)})
    (try
      (wrap-fixtures (fixtures ns-sym :once)
                     (fn [] (doseq [v vars]
                              (wrap-fixtures (fixtures ns-sym :each) (fn [] (test-var v))))))
      (catch :default e
        ;; a throwing fixture takes the whole namespace down; report it against
        ;; the namespace rather than losing the run
        (binding [test/*testing-vars* (list)]
          (report {:type :error :fault true :expected nil :actual e
                   :message "Uncaught exception in test fixture"}))))
    (test/do-report {:type :end-test-ns :ns (find-ns ns-sym)})
    @current-report))

(defn- test-vars-of
  "The test vars of `ns-sym` — those carrying :test metadata — optionally
  narrowed to `only` (a set of var name strings) and filtered by metadata keys."
  [ns-sym {:keys [only include exclude]}]
  (when-let [n (find-ns ns-sym)]
    (->> (ns-interns n)
         (keep (fn [[s v]]
                 (let [m (meta v)]
                   (when (and (:test m)
                              (or (empty? only) (contains? only (str s)))
                              (or (empty? include) (some #(get m (keyword %)) include))
                              (not (some #(get m (keyword %)) exclude)))
                     v))))
         (sort-by (comp str :name meta))
         vec)))

(defn- run-tests!
  "Run `corpus` (a seq of [ns-sym vars]) as one report."
  [corpus]
  (report-reset!)
  (let [started (System/currentTimeMillis)]
    (doseq [[ns-sym vars] corpus] (test-ns ns-sym vars))
    (let [elapsed (- (System/currentTimeMillis) started)]
      (swap! current-report assoc :elapsed-time {:ms elapsed
                                                 :humanized (str "Completed in " elapsed " ms")}))
    (reset! results (:results @current-report))
    @current-report))

(defn- strip-throwables
  "The report as it goes on the wire: the retained exceptions stay behind in
  `results` for test-stacktrace."
  [report]
  (update report :results
          (fn [by-ns]
            (into {} (for [[ns by-var] by-ns]
                       [ns (into {} (for [[v assertions] by-var]
                                      [v (mapv #(dissoc % ::throwable) assertions)]))])))))

;; --- ops --------------------------------------------------------------------

(defn- ns-query-namespaces
  "The namespaces a var-query selects: the ones named exactly, or every project
  namespace that has tests."
  [var-query]
  (let [ns-query (get var-query "ns-query" {})
        exactly (seq (get ns-query "exactly"))]
    (if exactly
      (map symbol exactly)
      (->> (cp/project-namespaces)
           (keep (fn [n]
                   (when (util/truthy? (get ns-query "load-project-ns?")) (try (require n) (catch :default _ nil)))
                   (when-let [loaded (find-ns n)]
                     (when (some (comp :test meta) (vals (ns-interns loaded))) n))))))))

(defn- var-selection [var-query]
  {:only (set (map #(last (str/split (str %) #"/")) (get var-query "exactly" [])))
   :include (get var-query "include-meta-key" [])
   :exclude (get var-query "exclude-meta-key" [])})

(defn test-var-query-reply [request]
  (let [var-query (or (get request "var-query") {})
        selection (var-selection var-query)
        corpus (for [ns-sym (ns-query-namespaces var-query)
                     :let [vars (test-vars-of ns-sym selection)]
                     :when (seq vars)]
                 [ns-sym vars])]
    (strip-throwables (run-tests! corpus))))

(defn test-reply
  "The older `test` op: every test in one namespace."
  [request]
  (test-var-query-reply
   (assoc request "var-query"
          {"ns-query" {"exactly" [(str (util/request-ns request))]}
           "exactly" (or (get request "tests") [])
           "include-meta-key" (or (get request "include") [])
           "exclude-meta-key" (or (get request "exclude") [])})))

(defn test-all-reply [request]
  (test-var-query-reply
   (assoc request "var-query"
          {"ns-query" {"project?" "true" "load-project-ns?" (get request "load?")}
           "include-meta-key" (or (get request "include") [])
           "exclude-meta-key" (or (get request "exclude") [])})))

(defn retest-reply
  "Re-run only the vars that failed or errored in the last run."
  [_request]
  (let [corpus (for [[ns-sym by-var] @results
                     :let [failed (->> by-var
                                       (keep (fn [[v assertions]]
                                               (when (some (comp #{:fail :error} :type) assertions) v)))
                                       set)
                           vars (test-vars-of ns-sym {:only (set (map str failed))})]
                     :when (seq vars)]
                 [ns-sym vars])]
    (strip-throwables (run-tests! corpus))))

(defn test-stacktrace-reply
  "The causes of the exception a given erroring assertion threw, one message per
  cause — the same shape `analyze-last-stacktrace` sends."
  [request]
  (let [ns-sym (util/as-sym (get request "ns"))
        v (util/as-sym (get request "var"))
        index (or (get request "index") 0)
        e (get-in @results [ns-sym v index ::throwable])]
    (if e
      (do (doseq [cause (stacktrace/analyze e)]
            (util/respond request cause))
          (util/respond-status request :done)
          util/async)
      {:status :no-error})))

(def ops
  {"test-var-query" test-var-query-reply
   "test" test-reply
   "test-all" test-all-reply
   "retest" retest-reply
   "test-stacktrace" test-stacktrace-reply})

(def middleware (util/op-middleware ops))
