(ns cider.nrepl.middleware.stacktrace
  "The `analyze-last-stacktrace` op: break the last exception into its chain of
  causes for the editor's error view. jolt.nrepl records the last one in *e.

  Each cause is sent as its own message, the way cider-nrepl streams them.
  Frames come from Throwable->map's :trace, which jolt fills in only for
  exceptions it carries a trace for — the cause chain, message and ex-data are
  always there, and that is what an editor shows first."
  (:require [clojure.string :as str]
            [cider.nrepl.middleware.util :as util]))

(def ^:private last-error-backtrace
  "jolt.nrepl/last-error-backtrace if this jolt has it. On one that doesn't, the
  causes still come through — only the frames are missing."
  (resolve 'jolt.nrepl/last-error-backtrace))

(defn class-name
  "The exception class as CIDER prints it: (str (class e)) renders as
  \"class clojure.lang.ExceptionInfo\", and only the name belongs on the wire."
  [x]
  (let [s (str x)]
    (if (str/starts-with? s "class ") (subs s (count "class ")) s)))

(defn- frame
  "One stack frame in the shape CIDER reads. A JVM-shaped frame is
  [class method file line]."
  [f]
  (let [[cls method file line] (if (sequential? f) f [f nil nil nil])]
    (cond-> {:name (str cls (when method (str "/" method)))
             :type "clj"}
      file (assoc :file (str file))
      (integer? line) (assoc :line line))))

;; jolt renders a host backtrace as indented lines, each naming a frame and,
;; where it knows one, its source position:
;;     demo.core/greet (./src/demo/core.clj:4)
;;     demo.core/greet (./src/demo/core.clj:4) (x2)
;; The exception value itself carries no frames, so this is where an editor's
;; error view gets something to click on.
(def ^:private frame-pattern #"^\s+(\S+)(?:\s+\(([^:()]+):(\d+)\))?(?:\s+\(x\d+\))?$")

(defn parse-backtrace
  "The frames in a jolt backtrace string, in the shape CIDER reads."
  [bt]
  (when (seq (str bt))
    (vec (keep (fn [line]
                 (when-let [[_ nm file ln] (re-matches frame-pattern line)]
                   (when-not (= nm "trace:")
                     (cond-> {:name nm :type "clj"}
                       file (assoc :file file)
                       ln (assoc :line (parse-long ln))))))
               (str/split-lines (str bt))))))

(defn analyze
  "The causes of `e`, outermost first, as cider-nrepl-shaped maps. `frames` are
  attributed to the outermost cause — jolt's backtrace covers the whole throw,
  not one link of the chain."
  ([e] (analyze e nil))
  ([e frames]
   (when e
     (let [{:keys [via trace]} (Throwable->map e)
           causes (or (seq via) [{:type (class e) :message (ex-message e)}])
           top (or (seq (mapv frame (or trace []))) (seq frames) [])]
       (vec (map-indexed
             (fn [i {:keys [type message data]}]
               (cond-> {:class (class-name type)
                        :message (str message)
                        :stacktrace (if (zero? i) (vec top) [])}
                 data (assoc :data (pr-str data))))
             causes))))))

(defn analyze-last-stacktrace-reply [request]
  (if-let [e *e]
    (do (doseq [cause (analyze e (parse-backtrace (when last-error-backtrace
                                                    ((deref last-error-backtrace)))))]
          (util/respond request cause))
        (util/respond-status request :done)
        util/async)
    {:status :no-error}))

(def ops
  {"analyze-last-stacktrace" analyze-last-stacktrace-reply
   ;; the name older clients send for the same thing
   "stacktrace" analyze-last-stacktrace-reply})

(def middleware (util/op-middleware ops))
