(ns nrepl.middleware.interruptible-eval
  "Adds the `interrupt` op. Eval itself runs on the session's worker (see
  nrepl.middleware.session); interrupt abandons a stuck eval and gives the session
  a fresh worker. jolt can't kill a running thread, so this is best-effort: the
  in-flight eval is replied to as :interrupted and the session stays responsive,
  but the abandoned computation runs to completion in the background."
  (:require [jolt.nrepl :as server]
            [nrepl.middleware.session :as session]))

(defn interruptible-eval
  [handler]
  (server/register-ops! "interrupt")
  (fn [request]
    (if (= "interrupt" (get request "op"))
      (let [sid (or (get request "interrupt-id") (get request "session"))
            r (session/interrupt! (get request "session"))]
        (server/respond request {"status" (if (= r :ok) ["done"] ["session-idle" "done"])}))
      (handler request))))
