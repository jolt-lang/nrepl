(ns nrepl.middleware
  "The default middleware stack this library contributes. A consuming project
  lists it in deps.edn:

      :nrepl/middleware [nrepl.middleware/default-middleware]

  and jolt.nrepl composes it over the built-in handler. Order matters — the first
  is outermost; session must wrap eval routing."
  (:require [nrepl.middleware.session :refer [session]]
            [nrepl.middleware.interruptible-eval :refer [interruptible-eval]]
            [nrepl.middleware.completion :refer [completion]]
            [nrepl.middleware.lookup :refer [lookup]]))

(def default-middleware
  [session interruptible-eval completion lookup])
