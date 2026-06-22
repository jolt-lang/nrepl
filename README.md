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

- **Interrupt is real.** Each eval runs under `jolt.host/run-interruptible`;
  `interrupt` sets the in-flight eval's token and it aborts at the next safe point
  (Chez's engine timer is polled at call/loop back-edges), then the worker is
  reused. A compute-bound eval — even a tight loop — is interrupted promptly; one
  blocked in a foreign call (socket recv, sleep) aborts when it returns to Scheme.
- **`*ns*` is thread-local.** Each session worker is its own thread with its own
  current namespace (jolt's `chez-current-ns` is a thread-parameter), so sessions
  eval concurrently in different namespaces without clobbering each other.

Both require a jolt that provides `jolt.host/run-interruptible` and a thread-local
`*ns*` (jolt-lang/jolt#172 and later).

The official nREPL implementation can't run unchanged on jolt — its core is tied
to `java.util.concurrent` executors, compiled Java helper classes, a dynamic
classloader, Compiler internals and a JVMTI agent. This library implements the
same wire protocol and behaviours on jolt-native threads.

## Tests

`joltc -M:test` runs the suite (bencode, client, session, completion, lookup,
interrupt) against an in-process server with the middleware installed.
