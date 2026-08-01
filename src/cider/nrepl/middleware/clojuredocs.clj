(ns cider.nrepl.middleware.clojuredocs
  "The `clojuredocs-lookup` op: community examples and see-alsos for a var, from
  the ClojureDocs export orchard bundles. `clojuredocs-refresh-cache` re-fetches
  that export, which needs an HTTP client jolt doesn't ship — the bundled copy
  is what serves lookups."
  (:require [cider.nrepl.middleware.util :as util]
            [orchard.clojuredocs :as docs]))

(defn clojuredocs-lookup-reply [request]
  (try
    (if-let [doc (docs/resolve-and-find-doc (util/request-ns request)
                                            (util/request-sym request))]
      {:clojuredocs doc}
      {:status :no-doc})
    (catch :default _ {:status :no-doc})))

(defn clojuredocs-refresh-cache-reply [request]
  (docs/clean-cache!)
  (if-let [url (not-empty (str (or (get request "export-edn-url") "")))]
    (docs/update-cache! url)
    (docs/update-cache!))
  {:status :ok})

(def ops
  {"clojuredocs-lookup" clojuredocs-lookup-reply
   "clojuredocs-refresh-cache" clojuredocs-refresh-cache-reply})

(def middleware (util/op-middleware ops))
