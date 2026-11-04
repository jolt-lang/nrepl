(ns nrepl.middleware.lookup
  "Adds the `lookup` op: metadata (name, ns, doc, arglists, and where the var
  was defined) for a symbol, for editor docs / eldoc / jump-to-definition.
  Returns whatever var metadata jolt carries."
  (:require [clojure.java.io :as io]
            [jolt.nrepl :as server]))

(defn- file-str
  "A var's :file as JVM nREPL's lookup sends it: a classpath-relative path
  (`liq/buffer.cljc`) resolved to its resource URL, so a client can open it;
  any other value (an absolute path, a REPL def's) as it is."
  [file]
  (str (or (and (string? file) (io/resource file)) file)))

(defn- info [ns-str sym-str]
  (let [the-ns (or (and ns-str (find-ns (symbol ns-str))) (find-ns 'user))
        v (when (and the-ns sym-str (seq sym-str))
            (try (ns-resolve the-ns (symbol sym-str)) (catch :default _ nil)))]
    (when (var? v)
      (let [m (meta v)]
        (cond-> {"name" (str (or (:name m) sym-str))
                 "ns" (str (:ns m))}
          (:doc m) (assoc "doc" (str (:doc m)))
          (:arglists m) (assoc "arglists-str" (pr-str (:arglists m)))
          (:file m) (assoc "file" (file-str (:file m)))
          (:line m) (assoc "line" (:line m))
          (:column m) (assoc "column" (:column m)))))))

(defn lookup
  [handler]
  (server/register-ops! "lookup")
  (fn [request]
    (if (= "lookup" (get request "op"))
      (let [i (info (get request "ns") (or (get request "sym") (get request "symbol")))]
        (server/respond request (cond-> {"status" ["done"]} i (assoc "info" i))))
      (handler request))))
