(ns nrepl.middleware.lookup
  "Adds the `lookup` op: metadata (name, ns, doc, arglists) for a symbol, for
  editor docs / eldoc. Returns whatever var metadata jolt carries."
  (:require [jolt.nrepl :as server]))

(defn- info [ns-str sym-str]
  (let [the-ns (or (and ns-str (find-ns (symbol ns-str))) (find-ns 'user))
        v (when (and the-ns sym-str (seq sym-str))
            (try (ns-resolve the-ns (symbol sym-str)) (catch :default _ nil)))]
    (when (var? v)
      (let [m (meta v)]
        (cond-> {"name" (str (or (:name m) sym-str))
                 "ns" (str (:ns m))}
          (:doc m) (assoc "doc" (str (:doc m)))
          (:arglists m) (assoc "arglists-str" (pr-str (:arglists m))))))))

(defn lookup
  [handler]
  (server/register-ops! "lookup")
  (fn [request]
    (if (= "lookup" (get request "op"))
      (let [i (info (get request "ns") (or (get request "sym") (get request "symbol")))]
        (server/respond request (cond-> {"status" ["done"]} i (assoc "info" i))))
      (handler request))))
