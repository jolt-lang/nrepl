(ns cider.nrepl
  "The cider-nrepl op set for jolt — what an editor needs beyond eval: symbol
  info, completion, the namespace browser, macroexpansion, apropos, running
  tests, error analysis and server output.

  Add this library and list the middleware in the project's deps.edn:

      {:deps {jolt-lang/nrepl {:git/url \"https://github.com/jolt-lang/nrepl\"
                               :git/sha \"<full-sha>\"}}
       :nrepl/middleware [nrepl.middleware/default-middleware
                          cider.nrepl/cider-middleware]}

  Then `jolt nrepl-server` and connect CIDER, Calva or Cursive.

  The ops resolve their answers through cider/orchard and compliment/compliment,
  which run on jolt as published. What is NOT here, and why: the ops that exist
  to describe Java (`inspect` over Java objects, `javadoc`, `xref` via bytecode
  scanning) have nothing to describe on jolt; `format-code` needs cljfmt (Calva
  and CIDER both format in the editor by default); the `debug` ops instrument
  code through the JVM compiler's analyzer."
  (:require [cider.nrepl.middleware.apropos :as apropos]
            [cider.nrepl.middleware.classpath :as classpath]
            [cider.nrepl.middleware.clojuredocs :as clojuredocs]
            [cider.nrepl.middleware.complete :as complete]
            [cider.nrepl.middleware.info :as info]
            [cider.nrepl.middleware.macroexpand :as macroexpand]
            [cider.nrepl.middleware.ns :as ns]
            [cider.nrepl.middleware.out :as out]
            [cider.nrepl.middleware.stacktrace :as stacktrace]
            [cider.nrepl.middleware.test :as test]
            [cider.nrepl.middleware.undef :as undef]
            [jolt.nrepl :as server]))

(def version
  "The cider-nrepl protocol version these ops implement. CIDER reads this out of
  `describe` to decide whether the server speaks its dialect."
  {"major" 0 "minor" 57 "incremental" 0 "version-string" "0.57.0"})

;; register-version! arrived with the ops; on an older jolt the ops still work,
;; the server just doesn't advertise a cider-nrepl version (CIDER cares, Calva
;; goes by the op list).
(when-let [register (resolve 'jolt.nrepl/register-version!)]
  (register "cider-nrepl" version))

(def cider-middleware
  "Every cider op, as one entry for deps.edn's :nrepl/middleware."
  [info/middleware
   complete/middleware
   ns/middleware
   classpath/middleware
   macroexpand/middleware
   apropos/middleware
   undef/middleware
   test/middleware
   stacktrace/middleware
   out/middleware
   clojuredocs/middleware])
