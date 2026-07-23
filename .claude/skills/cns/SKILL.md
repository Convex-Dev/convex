---
name: cns
description: Resolve or register Convex Name System (CNS) names. Use when the user wants to look up a CNS name, register a new name, or update a name's target.
argument-hint: "[resolve|register|update] <name>"
---

# Convex Name System (CNS)

CNS maps human-readable names (like `convex.fungible`) to on-chain addresses.

## Resolve a Name

Use `mcp__convex-testnet__resolveCNS` with the name.

Returns: value (usually an address), controller, metadata, and child node.

## Register or Update a Name

Transaction: `(call #9 (cns-update 'my.name *address*))`

- `#9` is the CNS registry actor
- The name must be under a namespace you control
- Top-level names require special authority

## CNS in Code

- `@convex.fungible` in CVM source resolves to the actor address at runtime
- Use CNS paths instead of hardcoded addresses for portable code
- `(@convex.fungible/balance #TOKEN #USER)` calls `balance` on the resolved actor

## Common CNS Names

| Name | Purpose |
|------|---------|
| `convex.fungible` | Fungible token standard library |
| `convex.trust` | Trust and access control |
| `convex.asset` | Multi-asset interface |
| `convex.nft` | Non-fungible token support |
