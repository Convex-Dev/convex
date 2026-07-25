---
name: token
description: Create and manage fungible tokens on Convex. Use when the user wants to create a new token, check token balances, or manage token supply.
argument-hint: "[create|balance|mint|transfer] [args...]"
---

# Fungible Tokens on Convex

Fungible tokens use the `@convex.fungible` standard library
(`convex-core/src/main/cvx/convex/asset/fungible.cvx`). See the `convex-lisp`
skill for CVM conventions, and `transact` for how transactions are signed.

## Create a New Token

**Fixed supply** — no further tokens can ever be minted:

```clojure
(deploy (@convex.fungible/build-token {:supply 1000000}))
```

**Mintable** — `build-token` alone provides *no* mint or burn capability. To
allow minting you must compose `add-mint` into the same deployment:

```clojure
(deploy [(@convex.fungible/build-token {:supply 1000000})
         (@convex.fungible/add-mint {:minter *address* :max-supply 1000000000})])
```

Decide which the user wants before deploying — the choice is permanent, and a
token deployed without `add-mint` fails on any later mint attempt.

`build-token` config: `:supply` (defaults to 0), `:initial-holder` (defaults to
`*address*`), `:decimals`. `add-mint` config: `:minter` (any trust monitor,
defaults to `*address*`) and `:max-supply` (from protocol v1, defaults to
unlimited).

**Always set `:max-supply` explicitly.** Before v1 activates, omitting it
defaults the cap to `0` — and because `0` is truthy in CVM, that installs a
zero cap which blocks *all* minting, silently producing a mintable-looking
token that can never mint (#528). Setting it explicitly is correct under both
genesis and v1. See the `protocol-versions` skill.

`deploy` returns the token's actor address.

## Operations

| Task | Source |
|------|--------|
| Check balance | `(@convex.fungible/balance #TOKEN #HOLDER)` |
| Total supply | `(@convex.fungible/total-supply #TOKEN)` |
| Decimals | `(@convex.fungible/decimals #TOKEN)` |
| Transfer | `(@convex.fungible/transfer #TOKEN #DEST AMOUNT)` |
| Mint | `(@convex.fungible/mint #TOKEN AMOUNT)` |
| Burn | `(@convex.fungible/burn #TOKEN AMOUNT)` |

There is no `quantity` function — it returns `:UNDECLARED`. Total supply is
`total-supply`.

`balance`, `total-supply` and `decimals` are reads: use a query, not a
transaction. `transfer`, `mint` and `burn` change state and must be
transactions.

If a Convex MCP server is configured, its `getBalance` and `transfer` tools
also accept a `token` parameter.

## Authorisation

Minting requires the caller to satisfy the token's `:minter` trust monitor;
anything else fails with `:TRUST`. `:minter` takes any trust monitor, not just
an address — see the `trust` skill for composing them (a mint window, a
multi-party whitelist, delegated revocation). Creating, transferring, minting and burning
are all transactions, so they need a key the user has supplied — see the
`transact` skill. Without one you can prepare the source but not execute it.
