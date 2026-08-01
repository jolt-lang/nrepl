(ns cider.nrepl.middleware.macroexpand
  "The `macroexpand` op: expand a form and hand back the printed expansion.

  cider-nrepl routes this through an eval message on the JVM; here the expansion
  runs in the op, which is simpler and gets the same result — jolt's
  macroexpand-1 / macroexpand / clojure.walk/macroexpand-all all work.

  The :display-namespaces option is honoured: 'qualified' leaves symbols as they
  expand, 'none' strips every namespace, 'tidy' (the default) rewrites a
  qualified symbol to the alias or plain name it is known by in the requesting
  namespace."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cider.nrepl.middleware.util :as util]))

(defn macroexpand-step
  "Expand the next subform only — CIDER's `macroexpand-step` command."
  [form]
  (let [expanded? (atom false)]
    (walk/prewalk (fn [x]
                    (if @expanded?
                      x
                      (let [x' (macroexpand-1 x)]
                        (when (not= x x') (reset! expanded? true))
                        x')))
                  form)))

(defn- expander [name]
  (case (str name)
    "macroexpand-1" macroexpand-1
    "macroexpand-all" walk/macroexpand-all
    "macroexpand-step" macroexpand-step
    macroexpand))

(defn- tidy-sym
  "The name `sym` goes by in `ns`: its alias, or bare when it is defined in or
  referred into that namespace."
  [sym ns aliases refers]
  (let [ns-sym (symbol (namespace sym))
        name-sym (symbol (name sym))
        alias (get aliases ns-sym)]
    (cond
      alias (symbol (str alias) (str name-sym))
      (= ns-sym ns) name-sym
      (= sym (get refers name-sym)) name-sym
      :else sym)))

(defn- display-walker [mode ns]
  (let [aliases (into {} (for [[a n] (ns-aliases ns)] [(ns-name n) a]))
        refers (into {} (for [[s v] (ns-refers ns)
                              :let [m (meta v)]
                              :when (:ns m)]
                          [s (symbol (str (:ns m)) (str (:name m)))]))]
    (fn [x]
      (if (and (symbol? x) (namespace x))
        (case (str mode)
          "none" (symbol (name x))
          "qualified" x
          (tidy-sym x ns aliases refers))
        x))))

(defn- expand-in-ns
  "Expand `form` with `ns-sym` current. jolt resolves a macro against the current
  in-ns rather than a `binding` of *ns*, so this switches for the expansion and
  switches back — the session's own namespace is not the caller's to change."
  [ns-sym expand form]
  (let [previous (ns-name *ns*)]
    (try
      (when (find-ns ns-sym) (in-ns ns-sym))
      (expand form)
      (finally (in-ns previous)))))

(defn macroexpand-reply [request]
  (let [ns (util/request-ns request)
        code (get request "code")
        form (read-string code)
        expand (expander (get request "expander"))
        expanded (expand-in-ns ns expand form)
        displayed (walk/prewalk (display-walker (or (get request "display-namespaces") "tidy") ns)
                                expanded)
        expansion (with-out-str
                    (binding [*print-meta* (util/truthy? (get request "print-meta"))]
                      (pp/pprint displayed)))]
    {:expansion (str/trim-newline expansion)}))

(def ops
  {"macroexpand" macroexpand-reply})

(def middleware (util/op-middleware ops))
