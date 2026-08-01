(ns cider.nrepl.middleware.ns
  "The namespace ops: `ns-list`, `ns-vars`, `ns-vars-with-meta`, `ns-path`,
  `ns-aliases` and `ns-load-all` — the namespace browser, and how Calva resolves
  a namespace to the file to open."
  (:refer-clojure :exclude [ns-aliases])
  (:require [clojure.string :as str]
            [cider.nrepl.middleware.classpath :as cp]
            [cider.nrepl.middleware.info :as info]
            [cider.nrepl.middleware.util :as util]
            [orchard.namespace :as ns]))

(def relevant-meta-keys
  "The metadata keys worth sending for each var; :name and :ns are redundant
  with the entry itself."
  [:indent :deprecated :macro :arglists :test :doc :fn :style/indent])

(defn- relevant-meta [m]
  (reduce (fn [acc k] (if-let [v (get m k)] (assoc acc k (pr-str v)) acc))
          {} relevant-meta-keys))

(defn- vars-of
  "The vars of `ns-sym`: public ones, or every intern when :private? is set."
  [ns-sym private?]
  (when-let [n (find-ns ns-sym)]
    (vals (if private? (ns-interns n) (ns-publics n)))))

(defn- var-query [request]
  (or (get request "var-query") {}))

(defn ns-list-reply [request]
  {:ns-list (ns/loaded-namespaces (get request "filter-regexps"))})

(defn ns-list-vars-by-name-reply
  "Every var named `name`, in any loaded namespace."
  [request]
  (let [nm (util/as-sym (get request "name"))]
    {:var-list (pr-str (->> (all-ns)
                            (mapcat ns-interns)
                            (keep (fn [[s v]] (when (= s nm) v)))))}))

(defn ns-vars-reply [request]
  (let [private? (util/truthy? (get (var-query request) "private?"))]
    {:ns-vars (->> (vars-of (util/request-ns request) private?)
                   (map (comp str :name meta))
                   sort
                   vec)}))

(defn ns-vars-with-meta-reply [request]
  (let [private? (util/truthy? (get (var-query request) "private?"))]
    {:ns-vars-with-meta
     (into {} (for [v (vars-of (util/request-ns request) private?)
                    :let [m (meta v)]]
                [(str (:name m)) (relevant-meta m)]))}))

(defn ns-path-reply
  "The file a namespace lives in. A loaded namespace knows it from its own vars;
  otherwise fall back to the canonical source path on the roots."
  [request]
  (let [ns-sym (util/request-ns request)
        from-vars (some (comp :file meta) (vals (or (some-> (find-ns ns-sym) ns-interns) {})))
        path (or from-vars (some-> (ns/canonical-source ns-sym) str))
        resolved (:file (info/file-info path))]
    {:path resolved
     :url resolved}))

(defn ns-aliases-reply [request]
  {:ns-aliases (into {} (for [[a n] (clojure.core/ns-aliases (util/request-ns request))]
                          [(str a) (str (ns-name n))]))})

(defn ns-load-all-reply
  "Require every namespace in the project's own sources — how a client warms up
  the namespace browser."
  [_request]
  {:loaded-ns (->> (cp/project-namespaces)
                   (keep (fn [n] (try (require n) (str n) (catch :default _ nil))))
                   vec)})

(def ops
  {"ns-list" ns-list-reply
   "ns-list-vars-by-name" ns-list-vars-by-name-reply
   "ns-vars" ns-vars-reply
   "ns-vars-with-meta" ns-vars-with-meta-reply
   "ns-path" ns-path-reply
   "ns-aliases" ns-aliases-reply
   "ns-load-all" ns-load-all-reply})

(def middleware (util/op-middleware ops))
