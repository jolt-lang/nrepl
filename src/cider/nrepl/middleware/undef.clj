(ns cider.nrepl.middleware.undef
  "The `undef` and `undef-all` ops: take a definition (or a whole namespace's
  worth) back out of the running image, so a rename doesn't leave the old name
  behind."
  (:require [cider.nrepl.middleware.util :as util]))

(defn undef
  "Unmap `sym` from `ns`. A qualified symbol names a var in the namespace it
  qualifies (resolving an alias first); an unqualified one is unmapped as both a
  var and an alias in `ns`."
  [ns sym]
  (let [sym-ns (some-> (namespace sym) symbol)
        sym-name (symbol (name sym))]
    (if sym-ns
      (ns-unmap (get (ns-aliases ns) sym-ns sym-ns) sym-name)
      (do (ns-unalias ns sym-name)
          (ns-unmap ns sym-name)))
    sym))

(defn undef-all
  "Unmap every var and alias in `ns`."
  [ns]
  (doseq [[sym _] (ns-map ns)] (ns-unmap ns sym))
  (doseq [[sym _] (ns-aliases ns)] (ns-unalias ns sym))
  ns)

(defn undef-reply [request]
  {:undef (undef (util/request-ns request) (util/request-sym request))})

(defn undef-all-reply [request]
  {:undef-all (undef-all (util/request-ns request))})

(def ops
  {"undef" undef-reply
   "undef-all" undef-all-reply})

(def middleware (util/op-middleware ops))
