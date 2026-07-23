---
name: convex-lisp
description: Convex Lisp language reference — CVM conventions, calling library code, actor definitions, juice and error codes. Use when writing or debugging CVM source for queries, transactions or actors.
---

# Convex Lisp

Shared conventions for all CVM source. The `query`, `transact`, `deploy`,
`token` and `transfer` skills assume what is written here.

## Units and Notation

- Coin amounts are in **copper**. 1 CVM coin = 1,000,000,000 copper (10^9).
  Convert for display: report "1.5 CVM", not "1500000000".
- Account addresses take a `#` prefix: `#13`, `#1337`.
- CNS names take an `@` prefix when resolved in source: `@convex.fungible`.

## Calling Library Code

Resolve libraries through CNS rather than hardcoding addresses:

```clojure
(@convex.fungible/balance #128 #13)
```

This is the idiom used throughout the codebase — match it. A `let`-bound
address also works (`(let [f @convex.fungible] (f/balance …))`) and is
occasionally tidier for repeated calls, but is rare in practice.

**Never use `import` in query or transaction source.** It mutates the account
environment and costs extra juice on every transaction that carries it. Use the
`@name/fn` form instead.

Library and actor *bodies* are different: they are deployed once, so an
`import` at the top of a `.cvx` library resolves once and is idiomatic — the
core libraries do it, for instance `convex/trust/monitors.cvx`. Do not "fix"
those.

## Actors

Actors are on-chain accounts with their own address, balance and state.

```clojure
(deploy
  '(do
     (def counter 0)

     (defn ^:callable increment []
       (set! counter (+ counter 1))
       counter)))
```

- **`^:callable` is the only export mechanism.** There is no `export` form.
  The map form `^{:callable true}` is equivalent and equally common in the
  core libraries.
- `def`s in the actor body that are not `^:callable` are private state.
  Update them from inside with `set!`.
- `deploy` returns the new address. `deploy` also accepts a **vector** of
  code forms, which is how library builders are composed:
  `(deploy [(build-token …) (add-mint …)])`.
- `(set-controller #ADDR)` sets who may upgrade the actor.

## Juice and Memory

Transactions consume juice, paid in copper by the origin account, and consume
memory allowance if they grow the state. Queries are free — they execute
against current state and are discarded, so prefer a query whenever you only
need to read.

See the `juice` skill for execution costs and `memory` for storage — including
how to minimise and reclaim on-chain storage, which is worth reading before
designing an actor that stores anything.

## Error Codes

Errors surface as keywords. The ones you will actually hit:

| Code | Meaning |
|------|---------|
| `:UNDECLARED` | Symbol does not exist — usually a wrong function name |
| `:CAST` | Wrong type passed to a function |
| `:ARGUMENT` | Right type, invalid value |
| `:ARITY` | Wrong number of arguments |
| `:STATE` | Operation invalid for current state (e.g. missing callable) |
| `:TRUST` | Caller lacks rights for the operation |
| `:FUNDS` | Insufficient coin balance |
| `:JUICE` | Ran out of juice — transaction too expensive |
| `:MEMORY` | Insufficient memory allowance |
| `:NOBODY` | Target account does not exist |
| `:COMPILE` | Source did not compile |

`:UNDECLARED` on a library call almost always means the function name is
wrong. Check the library source in `convex-core/src/main/cvx/` rather than
guessing — several plausible names (`quantity`, `supply`) do not exist.

## Protocol Version

Write against **protocol version 1** semantics — see the `protocol-versions`
skill. Several core behaviours are fixed there rather than at genesis, so a
network still at version 0 differs:

- `update` and `update-in` drop an argument in their variadic (5+ arg) arities
- quasiquote of a set or map containing an unquote yields a call form, `~false`
  does not unquote, `define` evaluates its value twice, and `call` with too
  many arguments silently does nothing

If you hit one of these, it is a known genesis bug fixed by v1 — not something
to work around in new code.
