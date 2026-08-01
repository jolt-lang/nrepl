(ns cider.nrepl.middleware.info
  "The `info` and `eldoc` ops: what an editor shows on hover, in signature help,
  and jumps to on go-to-definition. `info` is also the op a client probes to
  decide whether it is talking to a cider-nrepl-capable server, so this is the
  one that makes Calva/CIDER treat a jolt nREPL as a full REPL.

  Resolution follows orchard's clj path — special form, var, ns alias, namespace —
  through `orchard.meta`, which runs on jolt. The Java branches don't apply:
  there are no Java classes to reflect on.

  Two jolt adjustments to what orchard returns:

  * a var defined in a namespace baked into the jolt image (clojure.core and the
    stdlib) carries :line but no :file, so the namespace's own source path fills
    it in and go-to-definition still lands in the right file;
  * :file is resolved to something the editor can open — an absolute path when
    the file exists on disk, since a project var's :file is recorded relative to
    the directory jolt was started in."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cider.nrepl.middleware.util :as util]
            [orchard.eldoc :as eldoc]
            [orchard.meta :as m]))

;; --- file resolution --------------------------------------------------------

(defn- existing-file
  "The absolute path of `path` if it names a file on disk, else nil. A project
  var records its file relative to where jolt was started (\"./src/app.clj\"),
  so the \"/./\" that leaves in the absolute path is squashed."
  [path]
  (when (and path (seq (str path)))
    (let [f (io/file (str path))]
      (when (.exists f) (str/replace (.getAbsolutePath f) "/./" "/")))))

(defn- resource-file
  "The absolute path of `path` looked up as a resource on the source roots — how
  a var in a library (or the stdlib) records its file: \"clojure/string.clj\"."
  [path]
  (when (and path (seq (str path)))
    (try (when-let [url (io/resource (str path))]
           (let [s (str url)]
             (if (str/starts-with? s "file:") (subs s 5) s)))
         (catch :default _ nil))))

(defn file-info
  "The :file / :resource pair for a var's recorded source path. :resource is the
  path as recorded (what it is called on the source roots); :file is something
  the editor can open."
  [path]
  (when (and path (seq (str path)))
    (let [path (str path)]
      (merge {:file (or (existing-file path) (resource-file path) path)}
             (when-not (str/starts-with? path "/") {:resource path})))))

(defn- ns-source-path
  "The source path recorded for namespace `ns-sym`, used to fill in :file for a
  var whose own metadata has none (every var in an image-baked namespace)."
  [ns-sym]
  (when-let [n (and ns-sym (find-ns ns-sym))]
    (or (:file (meta n))
        (:file (m/ns-meta ns-sym))
        ;; every other var in the namespace knows where it came from
        (some (comp :file meta) (vals (ns-interns n))))))

;; --- resolution -------------------------------------------------------------

(defn- var-info
  "orchard's metadata for `v`, with the position restored. When a var records no
  :file — every var in a namespace baked into the jolt image does — orchard
  substitutes the namespace's source file and drops :line with it, on the JVM
  reasoning that the line belonged to a file it no longer names. Here the
  substituted file IS the file the line came from, so the line is put back."
  [v]
  (let [m (m/var-meta v)
        raw (meta v)]
    (cond-> m
      (and (nil? (:line m)) (:line raw)) (assoc :line (:line raw)))))

(defn info
  "The info map for `sym` resolved in `ns`, or nil. Tries, in order: special
  form, var, ns alias, namespace — orchard's clj order minus the Java branches."
  [ns sym]
  (when sym
    (let [ns (or (when (and ns (find-ns ns)) ns) 'clojure.core)
          unqualified (symbol (name sym))]
      (or (m/special-sym-meta sym)
          (some-> (m/resolve-var ns sym) (var-info))
          (some-> (m/resolve-aliases ns) (get sym) (m/ns-meta))
          (some-> (find-ns unqualified) (m/ns-meta))))))

(defn- with-file
  "Fill in :file from the namespace when the var itself has none (image-baked
  namespaces record the position but not the path), then resolve it."
  [{:keys [file ns] :as m}]
  (if (nil? m)
    nil
    (let [file (or file (when (:line m) (ns-source-path (some-> ns str symbol))))]
      (merge (dissoc m :file) (file-info file)))))

(defn- dissoc-nil-keys
  "Drop nil values: bencode has no nil, so they would arrive as empty lists."
  [m]
  (reduce-kv (fn [acc k v] (if (some? v) (assoc acc k v) acc)) {} m))

(defn format-response
  "Shape an info map the way CIDER/Calva read it: :ns as a string, :arglists and
  :forms pre-rendered (the raw forms are dropped — metadata can hold anything),
  :file resolved."
  [info]
  (when info
    (let [join-forms (fn [forms] (str/join "\n" (map pr-str forms)))]
      (-> info
          with-file
          (merge (when-let [n (:ns info)] {:ns (str n)})
                 (when-let [args (:arglists info)] {:arglists-str (join-forms args)})
                 (when-let [forms (:forms info)] {:forms-str (join-forms forms)}))
          (dissoc :arglists :forms)
          dissoc-nil-keys))))

;; --- ops --------------------------------------------------------------------

(defn info-reply [request]
  (or (format-response (info (util/request-ns request) (util/request-sym request)))
      {:status :no-info}))

(defn eldoc-reply [request]
  (if-let [i (info (util/request-ns request) (util/request-sym request))]
    (eldoc/eldoc i)
    {:status :no-eldoc}))

(defn classify-symbols-reply
  "Classify each symbol in :symbols as macro / inline / special / function —
  CIDER colours symbols in the editor with this."
  [request]
  (let [ns (util/request-ns request)]
    {:classification
     (reduce (fn [acc s]
               (if-let [kind (try (m/classify-symbol ns (symbol (str s)))
                                  (catch :default _ nil))]
                 (assoc acc (str s) (name kind))
                 acc))
             {} (or (get request "symbols") []))}))

(def ops
  {"info" info-reply
   "eldoc" eldoc-reply
   "classify-symbols" classify-symbols-reply})

(def middleware (util/op-middleware ops))
