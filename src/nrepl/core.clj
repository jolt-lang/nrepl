(ns nrepl.core
  "nREPL client for jolt — connect to a running server and exchange messages.
  Mirrors the official nrepl.core client surface (connect / message / new-session
  / combine-responses / response-values / code)."
  (:require [nrepl.transport :as transport]))

(def ^{:doc "Open a client connection. (connect host port) -> transport."}
  connect transport/connect)
(def close transport/close)

(defn- kw-keys [m] (into {} (map (fn [[k v]] [(keyword k) v]) m)))

(def ^:private id-counter (atom 0))
(defn- gen-id [] (str "msg-" (swap! id-counter inc)))

(defn message
  "Send `msg` (a map; :op required, :id optional) and collect the responses for
  that id until one carries a \"done\" status. Returns a vector of response maps
  (keyword keys). Blocking."
  [transport msg]
  (let [id (or (:id msg) (gen-id))]
    (transport/send transport (assoc msg :id id))
    (loop [acc []]
      (let [resp (transport/recv transport)]
        (if (nil? resp)
          acc
          (let [r (kw-keys resp)]
            (if (= id (:id r))
              (let [acc (conj acc r)]
                (if (some #{"done"} (:status r)) acc (recur acc)))
              (recur acc))))))))

(defn new-session
  "Open a new session, optionally cloning `clone`. Returns the session id."
  [transport & {:keys [clone]}]
  (let [resp (first (message transport (cond-> {:op "clone"} clone (assoc :session clone))))]
    (or (:new-session resp)
        (throw (ex-info (str "Could not open session; clone response: " resp) {:resp resp})))))

(defn combine-responses
  "Combine a seq of response maps into one: last :ns/:id wins, :value accumulates
  into a vector, :status/:session into sets, string slots (:out/:err) concatenate."
  [responses]
  (reduce
   (fn [m [k v]]
     (case k
       (:id :ns) (assoc m k v)
       :value (update m k (fnil conj []) v)
       :status (update m k (fnil into #{}) v)
       :session (update m k (fnil conj #{}) v)
       (if (string? v) (update m k #(str % v)) (assoc m k v))))
   {} (apply concat responses)))

(defn code*
  "pr-str the expressions into one string (for an \"eval\" :code)."
  [& expressions] (apply str (map pr-str expressions)))

(defmacro code
  "Expand to a string of the body forms, for use as an eval :code."
  [& body] (apply code* body))

(defn read-response-value
  "Replace a response's :value string with its read value (unchanged if absent)."
  [{:keys [value] :as msg}]
  (if-not (string? value)
    msg
    (try (assoc msg :value (read-string value))
         (catch :default e (throw (ex-info (str "Could not read response value: " value) {:value value}))))))

(defn response-values
  "Seq of read :value's across `responses`."
  [responses]
  (->> responses (map read-response-value) combine-responses :value))
