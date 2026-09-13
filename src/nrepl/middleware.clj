(ns nrepl.middleware
  "The default middleware stack this library contributes. A consuming project
  lists it in deps.edn:

      :nrepl/middleware [nrepl.middleware/default-middleware]

  and jolt.nrepl composes it over the built-in handler. Order matters — the first
  is outermost; session must wrap eval routing.

  Also provides `set-descriptor!`, the nREPL middleware-descriptor convention:
  libraries ported from JVM nREPL (rephrase's wrap-rephrase among them) require
  it at load. jolt.nrepl composes middleware positionally, so the descriptor is
  documentation here, not a sort key."
  (:require [nrepl.middleware.session :refer [session]]
            [nrepl.middleware.caught :refer [wrap-caught]]
            [nrepl.middleware.interruptible-eval :refer [interruptible-eval]]
            [nrepl.middleware.completion :refer [completion]]
            [nrepl.middleware.lookup :refer [lookup]]))

(defn set-descriptor!
  "Set `descriptor` as the ::descriptor metadata on middleware var `v` (adds
  :implemented-by, the var's qualified name)."
  [v descriptor]
  (alter-meta! v assoc ::descriptor
               (assoc descriptor :implemented-by (symbol (str (ns-name (:ns (meta v))) "/" (:name (meta v)))))))

(def default-middleware
  ;; wrap-caught first: it must wrap :reply before session (which replies from
  ;; its worker thread) ever sees the request.
  [wrap-caught session interruptible-eval completion lookup])
