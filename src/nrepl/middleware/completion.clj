(ns nrepl.middleware.completion
  "Adds the `completions` op: symbol candidates for a prefix in a namespace, for
  editor autocomplete. Candidates come from the namespace's mappings (interns +
  refers)."
  (:require [clojure.string :as str]
            [jolt.nrepl :as server]))

(defn- candidates [ns-str prefix]
  (let [the-ns (or (and ns-str (find-ns (symbol ns-str))) (find-ns 'user) (find-ns 'clojure.core))
        names (when the-ns (map name (keys (ns-map the-ns))))]
    (->> (or names [])
         (filter #(str/starts-with? % prefix))
         distinct sort
         (map (fn [s] {"candidate" s "type" "var"}))
         vec)))

(defn completion
  [handler]
  (server/register-ops! "completions")
  (fn [request]
    (if (= "completions" (get request "op"))
      (server/respond request {"completions" (candidates (get request "ns")
                                                         (or (get request "prefix") (get request "symbol") ""))
                               "status" ["done"]})
      (handler request))))
