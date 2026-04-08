---
name: account
description: Create or inspect Convex accounts. Use when the user wants to set up a new account, check account details, or manage keys.
argument-hint: "[create|info|keys] [address]"
---

# Convex Account Management

## Create a New Account

### With signing service (recommended)
Use `mcp__convex-testnet__signingCreateAccount` — creates a key pair and on-chain account in one step. Optionally fund via faucet.

### With raw key
1. Generate a key pair: `mcp__convex-testnet__keyGen`
2. Create the account: `mcp__convex-testnet__createAccount` with the public key
3. Optionally fund with `faucet` parameter (max 1 CVM = 1,000,000,000 copper)

**Important:** Save the seed securely — it cannot be recovered.

## Inspect an Account

- **Full details:** `mcp__convex-testnet__describeAccount` with the address
- **Balance only:** `mcp__convex-testnet__getBalance` with the address
- **Via query:** `(account #ADDR)` returns the full account record

## Key Management

- **List stored keys:** `mcp__convex-testnet__signingListKeys`
- **List key-account mappings:** `mcp__convex-testnet__signingListAccounts`
- **Generate new key:** `mcp__convex-testnet__signingCreateKey` with a passphrase

## Display

- Show balances in CVM units (1 CVM = 10^9 copper)
- Show addresses with `#` prefix
- Show public keys as hex strings
