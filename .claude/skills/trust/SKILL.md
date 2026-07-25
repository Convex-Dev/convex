---
name: trust
description: Trust monitors — Convex's composable on-chain authorisation model. Use when writing access control, restricting actor functions, defining who may mint or upgrade, or reviewing authorisation logic.
---

# Trust Monitors

Trust monitors are Convex's authorisation primitive: composable, sandboxed,
on-chain modules that grant or deny access. Anywhere an actor asks "may this
caller do this?", the answer should come from a trust monitor rather than
hand-rolled logic.

Normative spec: `https://docs.convex.world/docs/cad/trustmon`. Reference
implementation: `convex-core/src/main/cvx/convex/core/trust.cvx` and
`convex/trust/monitors.cvx`.

## The Model

Every check is a triple:

- **Subject** — who is acting, almost always an account, usually `*caller*`
- **Action** — what they are doing, a short keyword such as `:update`
- **Object** — what they are acting on, typically an address or ID

This is the reference monitor model. Keeping the triple explicit is what makes
monitors reusable across unrelated contracts.

## Referencing a Monitor

A monitor reference is an account address, optionally **scoped**:

```clojure
#45              ;; an account
[#78 1467476]    ;; a scoped account — same actor, different rule
nil              ;; never authorises anything
```

`nil` is a valid monitor that always denies — useful as a safe default.

A bare address trusts only itself: `(trusted? #13 #13)` is `true`, anything
else `false`. That makes "the owner" expressible without deploying anything.

## Checking Trust

```clojure
(@convex.trust/trusted? monitor subject)
(@convex.trust/trusted? monitor subject action)
(@convex.trust/trusted? monitor subject action object)
```

Omitted action and object are passed as `nil`.

Inside an actor, the usual shape is:

```clojure
(when-not (trust/trusted? minter *caller* :mint)
  (fail :TRUST "No rights to mint"))
```

Use `:TRUST` for authorisation failures — that is what callers expect.

## Fail Closed

A monitor MUST return `true` or `false`, but a defective or malicious one may
throw or return something else. A checker MUST treat any error or non-`true`
result as **denial**, and must not let it propagate — an error-propagating
checker is itself a denial-of-service vector, since an actor holding an
attacker-supplied monitor would throw on every check.

`trusted?` implements this from **protocol version 1**: the monitor call is
wrapped in `query` against re-entrancy, errors are caught as `false`, and the
result is `boolean`-coerced. Write against that behaviour — it is the target
semantics, and `MigrationFixesTest` pins it.

```clojure
;; what trusted? does from v1
(boolean (try (query (call monitor (check-trusted? subject action object))) false))
```

**Before v1 activates**, the genesis `trusted?` in `core/trust.cvx` keeps the
`query` guard but does *not* catch the error or coerce the result — so a
defective monitor can throw through it, or grant on a truthy non-boolean. If
you are deploying an actor that accepts **caller-supplied** monitors onto a
network still at version 0, apply the wrapper yourself. For monitors you
control, the plain call is fine either way. See the `protocol-versions` skill.

## Writing a Monitor

Implement `check-trusted?` as a callable taking exactly three arguments:

```clojure
(defn ^:callable check-trusted?
  [subject action object]
  (boolean (and (= subject object) (= action :examine-self))))
```

Requirements that are not optional:

- **No side effects.** A monitor MUST work correctly inside `query`, because
  callers wrap it in one to block re-entrancy.
- **Return a strict boolean** for every possible argument combination.
- **Be O(1)** in computation and stack depth, with a small constant. Use
  pre-computed sets and maps for lookups.
- **Never scan arbitrary data structures.** An unbounded scan inside a monitor
  is a denial-of-service vector, since the monitor runs on every check.

## Standard Monitors

`convex.trust.monitors` provides composable monitors that need **no deployment**
— each returns a scoped address evaluated inline.

| Constructor | Grants when |
|-------------|-------------|
| `(mon/permit-subjects #3 #14)` | subject is in the set |
| `(mon/permit-actions :open :close)` | action is in the set |
| `(mon/all m1 m2 …)` | every listed monitor grants |
| `(mon/any m1 m2 …)` | any listed monitor grants |
| `(mon/everyone)` | always |
| `(mon/before end)` / `(mon/after start)` / `(mon/between start end)` | within the timestamp window |
| `(mon/rule (fn [s a o] …))` | the function returns truthy |
| `(mon/owns asset)` | subject owns the asset |
| `(mon/delegate allow deny base)` | `deny` first, then `allow`, else `base` |

Compose rather than write bespoke logic:

```clojure
(@convex.trust.monitors/all
  (@convex.trust.monitors/permit-subjects #13 #17)
  (@convex.trust.monitors/permit-actions :open :close))
```

`delegate` checks **deny before allow**, which is the ordering you want for
revocation.

## Guidance

**Hard-code actions.** An action keyword SHOULD NOT come from, or be
influenced by, untrusted input — otherwise a caller can select which
authorisation branch to be checked against. Keep actions literal at the call
site.

**Keep actions simple.** They may be any CVM value, but complex action
structures make security bugs easy. A keyword is almost always right.

**Let users supply the monitor.** The point of the pluggable design is that
whoever controls a resource chooses its access rules. Take a monitor reference
as configuration rather than baking a whitelist into an actor — this is exactly
how `add-mint` takes `:minter`; see the `token` skill.

**Related libraries:** `convex.trust.whitelist`, `convex.trust.ownership-monitor`,
`convex.trust.delegate` and `convex.trust.governance`.
