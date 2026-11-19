(ns nrepl.middleware.utf8
  "Request values arrive from jolt.nrepl as latin1 strings, one char per byte of
  the wire. This middleware decodes every string value in a request as UTF-8
  once, so the ops after it (lookup, completions, the cider ops, a project's own
  middleware) see the text the client sent, as on JVM nREPL.

  Left as they arrive: the values jolt.nrepl's built-in handler decodes itself
  when an eval or load-file falls through to it (`code`, `file`, `file-path`,
  `file-name`). Decoding those here would decode them twice."
  (:require [nrepl.bencode :as bencode]))

(def ^:private wire-keys
  "Values jolt.nrepl's built-in handler expects in wire form."
  #{"code" "file" "file-path" "file-name"})

(def ^:private decoded-key ::decoded)

(defn- decode-value [v]
  (cond
    (string? v) (bencode/wire-> v)
    (map? v) (update-vals v decode-value)
    (vector? v) (mapv decode-value v)
    (sequential? v) (map decode-value v)
    :else v))

(defn decode-request
  "`request` with every string value outside `wire-keys` UTF-8 decoded."
  [request]
  (reduce-kv (fn [m k v]
               (if (and (string? k) (not (wire-keys k)))
                 (assoc m k (decode-value v))
                 m))
             request request))

(defn wrap-utf8
  "Middleware: decode a request's string values as UTF-8 (see the ns doc). A
  request is decoded once, however many stacks list this middleware."
  [handler]
  (fn [request]
    (handler (if (get request decoded-key)
               request
               (assoc (decode-request request) decoded-key true)))))

(defn text
  "The text of `request`'s value at `k`, whether or not wrap-utf8 has decoded
  the request already."
  [request k]
  (when-let [v (get request k)]
    (if (and (get request decoded-key) (not (wire-keys k))) v (bencode/wire-> v))))
