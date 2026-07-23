---
name: transact
description: Execute a CVM transaction on the Convex network. Use when the user wants to modify on-chain state, call actor functions, or define values.
argument-hint: "<cvx-source>"
---

# Execute a Convex Transaction

Transactions modify global state atomically. They cost juice (gas) and require a funded, signed account.

**Source:** `$ARGUMENTS`

## Workflow

1. If the user has a signing key stored in the signing service, use `mcp__convex-testnet__signingTransact` with their address and passphrase.
2. If the user has a raw seed, use `mcp__convex-testnet__transact` with address and seed.
3. If neither is available, use `mcp__convex-testnet__prepare` to prepare the transaction, then guide the user through signing.

## CVM Conventions

- Amounts are in **copper**: 1 CVM = 1,000,000,000 copper
- **Never use `import`** — it mutates the account environment and costs extra juice
- For single calls: `(@convex.fungible/transfer #128 #13 100)`
- For multiple calls to the same actor: `(let [f @convex.fungible] (f/transfer ...) (f/balance ...))`

## Common Transaction Patterns

| Task | Source |
|------|--------|
| Transfer coins | `(transfer #DEST AMOUNT)` |
| Define a value | `(def my-var 42)` |
| Call an actor | `(call #ACTOR (function-name arg1 arg2))` |
| Set controller | `(set-controller #ADDR)` |

Always confirm the transaction source and destination with the user before executing.
