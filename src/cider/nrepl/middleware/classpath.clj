(ns cider.nrepl.middleware.classpath
  "The `classpath` op, plus the source-root scanning the ns ops build on.

  jolt's answer to the JVM classpath is its resolved source roots: the project's
  :paths, every dependency's root (a Maven jar is unpacked to a directory, so
  everything here is a directory), and the roots jolt itself ships. Calva asks
  for this right after connecting to find `calva.exports/config.edn` files.

  orchard's own classpath namespace can't serve this — it reaches for
  java.util.stream to walk jars — so the roots are read from jolt.host and walked
  with file-seq."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cider.nrepl.middleware.util :as util]))

(defn absolutize
  "`path` as an absolute path when that names something that exists. jolt's own
  install roots are recorded relative to its install directory, not to the
  project, so absolutizing those against the project would invent paths that
  aren't there; they stay as given."
  [path]
  (let [f (io/file (str path))
        abs (.getAbsolutePath f)]
    (if (.exists (io/file abs))
      ;; io/file keeps the path as given, so a root of "./src" absolutizes with
      ;; the "/./" still in it
      (str/replace abs "/./" "/")
      (str path))))

(defn classpath
  "The source roots — jolt's classpath."
  []
  (->> (jolt.host/source-roots)
       (map absolutize)
       distinct
       vec))

(defn- under?
  "True when `path` is inside directory `dir`."
  [path dir]
  (let [dir (if (str/ends-with? dir "/") dir (str dir "/"))]
    (str/starts-with? path dir)))

(defn project-roots
  "The roots that belong to the project being developed — the ones inside the
  directory jolt was started in. Dependency roots live in the Maven/git caches
  and jolt's own roots resolve against its install directory, so both drop out."
  []
  (let [cwd (.getAbsolutePath (io/file (or (System/getProperty "user.dir") ".")))]
    (->> (jolt.host/source-roots)
         (keep (fn [root]
                 (let [f (io/file root)]
                   (when (and (.exists f) (.isDirectory f)
                              (under? (.getAbsolutePath f) cwd))
                     root))))
         distinct
         vec)))

(def ^:private source-extensions [".clj" ".cljc"])

(defn- source-file? [path]
  (some #(str/ends-with? path %) source-extensions))

(defn- path->ns
  "The namespace a source file under `root` defines, by the canonical naming
  convention: src/my_app/core.clj -> my-app.core."
  [root path]
  (let [root (str root (when-not (str/ends-with? (str root) "/") "/"))
        rel (if (str/starts-with? path root) (subs path (count root)) path)
        base (subs rel 0 (str/last-index-of rel "."))]
    (-> base (str/replace "/" ".") (str/replace "_" "-") symbol)))

(defn namespaces-on-roots
  "Every namespace defined by a source file under `roots`, sorted."
  [roots]
  (->> roots
       (mapcat (fn [root]
                 (let [dir (io/file root)]
                   (when (.isDirectory dir)
                     (->> (file-seq dir)
                          (map str)
                          (filter source-file?)
                          (map #(path->ns (str root) %)))))))
       distinct
       (sort-by str)
       vec))

(defn project-namespaces
  "Every namespace defined in the project's own sources."
  []
  (namespaces-on-roots (project-roots)))

(defn classpath-reply [_request]
  {:classpath (classpath)})

(def ops
  {"classpath" classpath-reply})

(def middleware (util/op-middleware ops))
