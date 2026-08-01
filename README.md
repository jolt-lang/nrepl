# jolt-lang/nrepl

nREPL for [Jolt](https://github.com/jolt-lang/jolt) — the server-side middleware
that grows jolt's built-in nREPL into the full feature set, plus an nREPL client.

Jolt ships a small, extensible nREPL server in core (`jolt nrepl-server`): bencode
over a socket, with `clone` / `describe` / `eval` / `load-file` / `close`. This
library adds the rest as **middleware** — the base nREPL features, and the
cider-nrepl op set editors expect:

| Feature | op(s) | ns |
| --- | --- | --- |
| Stateful, isolated sessions | `clone` `close` `ls-sessions` `eval` | `nrepl.middleware.session` |
| Interruptible eval | `interrupt` | `nrepl.middleware.interruptible-eval` |
| Autocomplete | `completions` | `nrepl.middleware.completion` |
| Docs / eldoc | `lookup` | `nrepl.middleware.lookup` |
| Symbol info, docs, definition | `info` `eldoc` `classify-symbols` | `cider.nrepl.middleware.info` |
| Context-aware completion | `complete` `complete-doc` `complete-flush-caches` | `cider.nrepl.middleware.complete` |
| Namespace browser | `ns-list` `ns-vars` `ns-vars-with-meta` `ns-path` `ns-aliases` `ns-load-all` | `cider.nrepl.middleware.ns` |
| Classpath | `classpath` | `cider.nrepl.middleware.classpath` |
| Macroexpansion | `macroexpand` | `cider.nrepl.middleware.macroexpand` |
| Search | `apropos` | `cider.nrepl.middleware.apropos` |
| Undefining | `undef` `undef-all` | `cider.nrepl.middleware.undef` |
| Tests | `test-var-query` `test` `test-all` `retest` `test-stacktrace` | `cider.nrepl.middleware.test` |
| Error analysis | `analyze-last-stacktrace` `stacktrace` | `cider.nrepl.middleware.stacktrace` |
| Server output | `out-subscribe` `out-unsubscribe` | `cider.nrepl.middleware.out` |
| ClojureDocs | `clojuredocs-lookup` `clojuredocs-refresh-cache` | `cider.nrepl.middleware.clojuredocs` |

## Server: use the middleware

Add the dependency and list the middleware in your project's `deps.edn`; jolt
composes them over the built-in handler:

```clojure
{:deps {jolt-lang/nrepl {:git/url "https://github.com/jolt-lang/nrepl"
                         :git/sha "<full-sha>"}}
 :nrepl/middleware [nrepl.middleware/default-middleware
                    cider.nrepl/cider-middleware]}
```

Then:

```
jolt nrepl-server          # default port 7888; writes .nrepl-port
jolt nrepl-server 12345    # explicit port
```

Connect your editor (CIDER / Calva / Cursive) to the port in `.nrepl-port`. Your
project's deps are on the source roots and its native libs are loaded, so
`(require '[some.lib])` works in the session.

Each session keeps its own current namespace and runs evals on a dedicated
serialized worker thread, so sessions are isolated and a long eval doesn't block
other ops. `interrupt` aborts the running eval and the session keeps serving.

Leave `cider.nrepl/cider-middleware` out if you only want eval: the cider ops are
what pull in orchard and compliment.

The ops run on jolt 0.5.13, and three of them get better on a jolt built after
it: `describe` advertises a cider-nrepl version (CIDER checks for one),
`out-subscribe` stops echoing an eval's own output back a second time, and the
frames in `analyze-last-stacktrace` are filled in.

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

`interrupt` aborts a compute-bound eval at the next safe point, but it can't
preempt one blocked in a foreign call (socket recv, sleep) — that aborts only once
the call returns to Scheme.

The official nREPL implementation can't run unchanged on jolt — its core is tied
to `java.util.concurrent` executors, compiled Java helper classes, a dynamic
classloader, Compiler internals and a JVMTI agent. This library implements the
same wire protocol and behaviours on jolt-native threads.

The cider ops answer from [orchard](https://github.com/clojure-emacs/orchard) and
[compliment](https://github.com/alexander-yakushev/compliment), which run on jolt
as published — the same libraries cider-nrepl uses, so an editor gets the data it
already knows how to read. What is deliberately missing, and why:

* the ops that exist to describe **Java** — `inspect` over Java objects,
  `javadoc`, `xref` (it scans JVM bytecode) — have nothing to describe here;
* `format-code` needs cljfmt; CIDER and Calva both format in the editor by default;
* the `debug` ops instrument code through the JVM compiler's analyzer.

Two things read differently from the JVM:

* **`clojure.core` has no docstrings.** jolt's core functions carry `:ns` and
  `:name` but no `:doc` or `:arglists`, so hover on `map` is thin where hover on
  your own code is complete. `clojuredocs-lookup` fills the gap for core.
* **Go-to-definition covers your code and your dependencies, not the stdlib.**
  jolt bakes `clojure.core` and the stdlib into its binary; those vars report
  their `:resource` (`clojure/string.clj`) and line, but there is no file on disk
  for an editor to open.
* **Exceptions carry no stack of their own.** The frames in
  `analyze-last-stacktrace` come from the backtrace jolt recorded where the
  exception was thrown, which covers the last error in the session.

## Tests

`jolt -M:test` runs the suite (bencode, client, session, completion, lookup,
interrupt, and the cider ops) against an in-process server with the middleware
installed.

The suite probes for the jolt seams named above and skips the tests covering
them when they are absent, the same way the library itself degrades. It says
which tier it ran at on the first line, so a green run doesn't hide what it
skipped.
