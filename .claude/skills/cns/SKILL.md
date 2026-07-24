---
name: cns
description: Resolve or register Convex Name System (CNS) names. Use when the user wants to look up a CNS name, register a new name, or update a name's target.
argument-hint: "[resolve|register|update] <name>"
---

# Convex Name System (CNS)

CNS maps human-readable names (like `convex.fungible`) to on-chain addresses.

See the `convex-lisp` skill for CVM conventions.

## Resolve a Name

In CVM source, `@convex.fungible` resolves the name directly — this is the
normal way to reach a library.

To inspect a name's record (value, controller, metadata, child node), use a
Convex MCP server's `resolveCNS` tool if one is configured. Otherwise query it:

```bash
java -jar convex.jar client query '@convex.fungible'
```

## Register or Update a Name

The registry actor is `#9`, available in CVM code as `*registry*`. It has no
`cns-update` function — use `create` for a new name and `update` for an
existing one:

```clojure
;; Create a new name pointing at a target, with *address* as controller
(*registry*/create 'my.name #TARGET *address*)

;; Update an existing name you control
(*registry*/update 'my.name #NEW-TARGET)

;; Change who controls a name
(*registry*/change-control 'my.name #NEW-CONTROLLER)
```

These are transactions (they change state). Notes:

- **Creating requires controlling the parent namespace.** `(*registry*/create
  'my.name ...)` needs the `my` node to exist and to trust you — otherwise it
  fails with `:TRUST` "Forbidden to create CNS node". Top-level names require
  special authority.
- `create` also accepts optional controller, metadata and child arguments:
  `(*registry*/create 'my.name target controller metadata)`.
- Note `(*registry*/register {:name "..."})` is unrelated — it registers
  metadata for the *caller's own account*, not a CNS name.

## CNS in Code

- `@convex.fungible` in CVM source resolves to the actor address at runtime
- Use CNS paths instead of hardcoded addresses for portable code
- `(@convex.fungible/balance #TOKEN #USER)` calls `balance` on the resolved actor

## Common CNS Names

| Name | Purpose |
|------|---------|
| `convex.fungible` | Fungible token standard library |
| `convex.trust` | Trust and access control |
| `convex.asset` | Generic asset interface |
| `asset.nft.simple` | Non-fungible token standard |
| `asset.multi-token` | Multi-token standard |

(Resolve with `(*registry*/resolve 'name)` to get the current address — these
are network-specific.)
