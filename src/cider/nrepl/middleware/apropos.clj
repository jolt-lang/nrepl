(ns cider.nrepl.middleware.apropos
  "The `apropos` op: search var names (or docstrings) across the loaded
  namespaces by regex. orchard does the searching; this coerces the bencoded
  query into the shape it expects."
  (:require [cider.nrepl.middleware.util :as util]
            [orchard.apropos :as apropos]))

(defn- var-query [request]
  (let [query (get request "query")
        case-sensitive? (util/truthy? (get request "case-sensitive?"))
        docs? (util/truthy? (get request "docs?"))]
    (cond-> {:private? (util/truthy? (get request "privates?"))}
      query (assoc :search (re-pattern (if case-sensitive? query (str "(?i:" query ")"))))
      docs? (assoc :search-property :doc))))

(defn apropos-reply [request]
  (let [ns (some-> (get request "ns") not-empty symbol find-ns)]
    {:apropos-matches (apropos/find-symbols
                       (cond-> {:var-query (assoc (var-query request) :ns-query {})
                                :full-doc? (util/truthy? (get request "docs?"))}
                         ns (assoc :ns ns)))}))

(def ops
  {"apropos" apropos-reply})

(def middleware (util/op-middleware ops))
