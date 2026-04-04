---
name: transfer
description: Transfer CVM coins or fungible tokens between Convex accounts. Use when the user wants to send coins or tokens to another account.
argument-hint: "<to-address> <amount> [token-address]"
---

# Transfer Coins or Tokens

## CVM Coin Transfer

Transfer native CVM coins using `mcp__convex-testnet__transfer`:
- **to:** destination address (e.g. `#42`)
- **amount:** in copper (1 CVM = 1,000,000,000 copper)

Or via transaction: `(transfer #42 1000000000)` — sends 1 CVM.

## Fungible Token Transfer

For non-native tokens, include the token actor address:
- Via transfer tool: set `token` parameter to the token actor address
- Via transaction: `(@convex.fungible/transfer #TOKEN #DEST AMOUNT)`

## Important

- Always confirm the destination address and amount with the user before executing
- Convert user-friendly amounts to copper: multiply by 1,000,000,000
- Check the sender has sufficient balance first with a query
- Display amounts in CVM units in responses (e.g. "Sent 2.5 CVM to #42")
