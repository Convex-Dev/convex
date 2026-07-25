---
name: transfer
description: Transfer CVM coins or fungible tokens between Convex accounts. Use when the user wants to send coins or tokens to another account.
argument-hint: "<to-address> <amount> [token-address]"
---

# Transfer Coins or Tokens

A transfer is a transaction, so it needs a key the user has supplied — see the
`transact` skill for the authorisation model and the CLI form. Without a key
you can prepare the transfer but not execute it.

## CVM Coin Transfer

Source: `(transfer #42 1000000000)` — sends 1 CVM.

With a Convex MCP server configured, its `transfer` tool takes **to**
(destination address) and **amount** (in copper).

## Fungible Token Transfer

Source: `(@convex.fungible/transfer #TOKEN #DEST AMOUNT)`

With the MCP `transfer` tool, set the `token` parameter to the token actor
address.

## Before Executing

- **Confirm destination and amount with the user.** Restate both in CVM units
  — "send 2.5 CVM to #42" — and get agreement before signing. A transfer is
  irreversible.
- **Convert to copper**: multiply CVM by 1,000,000,000. Getting this wrong by a
  factor of 10^9 is the easiest and most expensive mistake here.
- **Check the sender's balance first** with a query, so a `:FUNDS` failure is
  caught before signing rather than after.
- For tokens, check the *token* balance, not the coin balance — they are
  unrelated.

Report the result in CVM units (e.g. "Sent 2.5 CVM to #42").
