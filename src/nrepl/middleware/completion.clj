(ns nrepl.middleware.completion
  "Adds the `completions` op: symbol candidates for a prefix in a namespace, for
  editor autocomplete. An unscoped prefix matches the namespace's mappings
  (interns + refers), its aliases (as `alias/`) and loaded namespace names. A
  scoped prefix, `str/jo` or `clojure.string/jo`, matches the public vars of the
  namespace the part before the `/` names, through an alias of the request's
  namespace first and then as a namespace name, as JVM nREPL's does."
  (:require [clojure.string :as str]
            [jolt.nrepl :as server]))

(defn- starting-with [prefix names]
  (->> names (filter #(str/starts-with? % prefix)) distinct sort))

(defn- scoped-candidates
  "Candidates for `scope/part`: the public vars of the namespace `scope` names,
  written the way the prefix was, so `str/jo` offers `str/join`."
  [the-ns scope part]
  (when-let [target (or (get (ns-aliases the-ns) (symbol scope)) (find-ns (symbol scope)))]
    (map (fn [s] {"candidate" (str scope "/" s) "type" "var" "ns" (str (ns-name target))})
         (starting-with part (map name (keys (ns-publics target)))))))

(defn- unscoped-candidates [the-ns prefix]
  (concat
   (map (fn [s] {"candidate" s "type" "var"})
        (starting-with prefix (map name (keys (ns-map the-ns)))))
   (map (fn [s] {"candidate" (str s "/") "type" "namespace"})
        (starting-with prefix (map name (keys (ns-aliases the-ns)))))
   (map (fn [s] {"candidate" s "type" "namespace"})
        (starting-with prefix (map (comp name ns-name) (all-ns))))))

(defn- candidates [ns-str prefix]
  (let [the-ns (or (and ns-str (find-ns (symbol ns-str))) (find-ns 'user) (find-ns 'clojure.core))
        ;; the last `/` splits the scope off; a leading one is the `/` fn itself
        slash (str/last-index-of prefix "/")]
    (vec (when the-ns
           (if (and slash (pos? slash))
             (scoped-candidates the-ns (subs prefix 0 slash) (subs prefix (inc slash)))
             (unscoped-candidates the-ns prefix))))))

(defn completion
  [handler]
  (server/register-ops! "completions")
  (fn [request]
    (if (= "completions" (get request "op"))
      (server/respond request {"completions" (candidates (get request "ns")
                                                         (or (get request "prefix") (get request "symbol") ""))
                               "status" ["done"]})
      (handler request))))
