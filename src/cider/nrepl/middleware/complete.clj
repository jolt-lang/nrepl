(ns cider.nrepl.middleware.complete
  "The `complete` op: context-aware completion through compliment, which runs on
  jolt unchanged.

  The source list leaves out the reflection-backed ones (class members, classes,
  resources on the classpath): jolt has no Java classes to complete against, and
  asking for them costs a classpath walk per keystroke for zero candidates."
  (:require [cider.nrepl.middleware.util :as util]
            [compliment.core :as complete]
            [compliment.utils :as complete-utils]))

(def sources
  "The compliment sources that mean something on jolt."
  [:compliment.sources.vars/vars
   :compliment.sources.namespaces/namespaces
   :compliment.sources.keywords/keywords
   :compliment.sources.local-bindings/local-bindings
   :compliment.sources.special-forms/special-forms
   :compliment.sources.special-forms/literals])

(defn- options [request]
  {:ns (util/request-ns request)
   :context (get request "context")
   :sort-order (or (some-> (get request "sort-order") keyword) :by-length)
   :extra-metadata (set (map keyword (or (get request "extra-metadata") [])))
   :sources sources})

(defn- prefix [request]
  (str (or (get request "prefix") (get request "symbol") "")))

(defn complete-reply [request]
  {:completions (complete/completions (prefix request) (options request))})

(defn complete-doc-reply [request]
  {:completion-doc (complete/documentation (str (util/request-sym request))
                                           (util/request-ns request)
                                           {:sources sources})})

(defn complete-flush-caches-reply [_request]
  (complete-utils/flush-caches)
  {})

(def ops
  {"complete" complete-reply
   "complete-doc" complete-doc-reply
   "complete-flush-caches" complete-flush-caches-reply})

(def middleware (util/op-middleware ops))
