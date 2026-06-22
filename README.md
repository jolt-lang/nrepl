# jolt-lang/nrepl

nREPL for [Jolt](https://github.com/jolt-lang/jolt) — the server-side middleware
that grows jolt's built-in nREPL into the full feature set, plus an nREPL client.

Jolt ships a small, extensible nREPL server in core (`joltc nrepl`): bencode over
a socket, with `clone` / `describe` / `eval` / `load-file` / `close`. This library
adds the heavier features as **middleware** — they're optional, so they don't
bloat core:

| Feature | op(s) | ns |
| --- | --- | --- |
| Stateful, isolated sessions | `clone` `close` `ls-sessions` `eval` | `nrepl.middleware.session` |
| Interruptible eval | `interrupt` | `nrepl.middleware.interruptible-eval` |
| Autocomplete | `completions` | `nrepl.middleware.completion` |
| Docs / eldoc | `lookup` | `nrepl.middleware.lookup` |

## Server: use the middleware

Add the dependency and list the middleware in your project's `deps.edn`; jolt
composes them over the built-in handler:

```clojure
{:deps {jolt-lang/nrepl {:git/url "https://github.com/jolt-lang/nrepl"
                         :git/sha "<full-sha>"}}
 :nrepl/middleware [nrepl.middleware/default-middleware]}
```

Then:

```
joltc nrepl          # default port 7888; writes .nrepl-port
joltc nrepl 12345    # explicit port
```

Connect your editor (CIDER / Calva / Cursive) to the port in `.nrepl-port`. Your
project's deps are on the source roots and its native libs are loaded, so
`(require '[some.lib])` works in the session.

Each session keeps its own current namespace and runs evals on a dedicated
serialized worker thread, so a long eval doesn't block other ops, and `interrupt`
keeps the session responsive.

## Client

```clojure
(require '[nrepl.core :as nrepl])

(let [t (nrepl/connect "127.0.0.1" 7888)
      s (nrepl/new-session t)]
  (nrepl/response-values (nrepl/message t {:op "eval" :code "(+ 1 2)" :session s}))  ;=> [3]
  (nrepl/close t))
```

`message` sends a message (an `:id` is added) and returns the responses for it up
to `"done"`. `combine-responses`, `response-values`, `new-session`, and the
`code` macro mirror the official `nrepl.core`.

## Notes for jolt

- **Interrupt is best-effort.** Chez Scheme can't kill a running thread, so
  `interrupt` replies `:interrupted` to the in-flight eval and gives the session a
  fresh worker; the abandoned computation runs to completion in the background.
- **Eval namespace is process-global.** `load-string` resolves against the
  current `in-ns`, not a thread-local `*ns*`, so the worker sets the session's ns
  before each eval. A normal editor (one eval session + a read-only tooling
  session) won't see interleaving; heavy concurrent ns-changing evals across
  sessions could.

The official nREPL implementation can't run unchanged on jolt — its core is tied
to `java.util.concurrent` executors, compiled Java helper classes, a dynamic
classloader, Compiler internals and a JVMTI agent. This library implements the
same wire protocol and behaviours on jolt-native threads.

## Tests

`joltc -M:test` runs the suite (bencode, client, session, completion, lookup,
interrupt) against an in-process server with the middleware installed.
