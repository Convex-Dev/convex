---
name: account
description: Create or inspect Convex accounts. Use when the user wants to set up a new account, check account details, or manage keys.
argument-hint: "[create|info|keys] [address]"
---

# Convex Account Management

Accounts are self-sovereign: an address like `#1337` controlled by a key pair.
See the `convex-lisp` skill for CVM conventions.

## Via the CLI

Always available from a built `convex.jar`:

```bash
java -jar convex.jar account create        # create an account
java -jar convex.jar account info #13      # full account record
java -jar convex.jar account balance #13   # coin balance
java -jar convex.jar account fund #13      # fund from the faucet, where available

java -jar convex.jar key generate          # new key pair in the keystore
java -jar convex.jar key list              # keys in the keystore
java -jar convex.jar key import|export|delete|sign
```

`--keystore` and `--storepass` select the keystore; `-k`/`--key` and
`-p`/`--keypass` select a key within it. Add `--host`/`--port` to target a
specific network.

## Via a Convex MCP Server

If one is configured (tool names look like `mcp__<server>__…`) — it is set up
per user, not by this repository, so check before relying on it:

- **Create in one step:** `signingCreateAccount` — key pair plus on-chain
  account, optionally funded from the faucet
- **From a raw key:** `keyGen`, then `createAccount` with the public key
- **Inspect:** `describeAccount` (full record), `getBalance` (balance only)
- **Keys:** `signingListKeys`, `signingListAccounts`, `signingCreateKey`

## Via Query

`(account #ADDR)` returns the full account record; `(balance #ADDR)` the coin
balance. Both are free — see the `query` skill.

## Handling Keys

**Save the seed when an account is created — it cannot be recovered.** Tell the
user explicitly at the moment of creation, not afterwards.

Never write a seed or passphrase into a file in the repository, a commit
message, or any output that will be shared onward. If the user pastes one, use
it for the operation at hand and do not repeat it back.

## Display

- Balances in CVM units (1 CVM = 10^9 copper)
- Addresses with the `#` prefix
- Public keys as hex strings
