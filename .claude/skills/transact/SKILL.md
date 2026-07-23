---
name: transact
description: Execute a CVM transaction on the Convex network. Use when the user wants to modify on-chain state, call actor functions, or define values.
argument-hint: "<cvx-source>"
---

# Execute a Convex Transaction

Transactions modify global state atomically. They consume juice, paid in copper
by the origin account, and must be signed.

**Source:** `$ARGUMENTS`

See the `convex-lisp` skill for CVM conventions and error codes.

## Authorisation

A transaction requires a key. You have one only if the user has supplied it —
a signing-service passphrase, or a raw seed. There is no ambient authority
here, so:

1. **User has a key in the signing service** — use the MCP `signingTransact`
   tool with their address and passphrase.
2. **User has a raw seed** — use the MCP `transact` tool with address and seed.
3. **Neither** — you cannot execute. Use `prepare` to build the unsigned
   transaction, or the CLI below, and hand it back for the user to sign.

Do not treat case 3 as a blocker to work around. Preparing the transaction and
returning it is the correct outcome.

## Via the CLI

Works from a built `convex.jar` and needs no MCP server:

```bash
java -jar convex.jar client transact -a #13 '(def my-var 42)'
java -jar convex.jar client transact -a #13 -k <key> -p <keypass> '(transfer #42 1000000000)'
```

`-k` / `--key` selects the key from the keystore and `-p` / `--keypass` is its
passphrase; `--keystore` and `--storepass` select the keystore itself. Add
`--host` / `--port` for a non-default network.

## Common Patterns

| Task | Source |
|------|--------|
| Transfer coins | `(transfer #DEST AMOUNT)` |
| Define a value | `(def my-var 42)` |
| Call an actor | `(call #ACTOR (function-name arg1 arg2))` |
| Call a library | `(@convex.fungible/transfer #TOKEN #DEST AMOUNT)` |
| Set controller | `(set-controller #ADDR)` |

Confirm source, destination and amount with the user before executing anything
that moves value or changes account control. State what will happen in CVM
units, not copper.
