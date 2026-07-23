---
name: token
description: Create and manage fungible tokens on Convex. Use when the user wants to create a new token, check token balances, or manage token supply.
argument-hint: "[create|balance|mint|transfer] [args...]"
---

# Fungible Tokens on Convex

Fungible tokens use the `@convex.fungible` standard library.

## Create a New Token

Deploy a token actor using the fungible token standard:

```clojure
(deploy
  (let [f @convex.fungible]
    (f/build-token
      {:supply 1000000})))
```

This creates a token with initial supply held by the deployer. The returned address is the token's actor address.

## Check Token Balance

Query: `(@convex.fungible/balance #TOKEN #HOLDER)`

Or use `mcp__convex-testnet__getBalance` with the `token` parameter.

## Transfer Tokens

Transaction: `(@convex.fungible/transfer #TOKEN #DEST AMOUNT)`

Or use `mcp__convex-testnet__transfer` with the `token` parameter.

## Mint Additional Supply

Transaction (must be token controller): `(call #TOKEN (mint AMOUNT))`

## Common Operations

| Task | Expression |
|------|-----------|
| Create token | `(deploy (let [f @convex.fungible] (f/build-token {:supply N})))` |
| Check balance | `(@convex.fungible/balance #TOKEN #ADDR)` |
| Transfer | `(@convex.fungible/transfer #TOKEN #DEST AMOUNT)` |
| Total supply | `(@convex.fungible/quantity #TOKEN)` |
| Mint | `(call #TOKEN (mint AMOUNT))` |
