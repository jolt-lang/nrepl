(ns nrepl.middleware.interruptible-eval
  "Adds the `interrupt` op. Eval itself runs on the session's worker (see
  nrepl.middleware.session), which evaluates under jolt.host/run-interruptible;
  interrupt sets the in-flight eval's interrupt token, so it aborts at the next
  safe point (Chez's engine timer, polled at call/loop back-edges) and the worker
  is reused — the computation is not abandoned. A compute-bound eval (even a tight
  loop) is interrupted promptly; one blocked in a foreign call (socket recv,
  sleep) aborts when it returns to Scheme."
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
