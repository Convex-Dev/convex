---
name: query
description: Execute a read-only CVM query on the Convex network. Use when reading on-chain state, checking balances, looking up accounts, or evaluating Convex Lisp expressions.
argument-hint: "<cvx-expression> [address]"
---

# Query Convex State

Execute a read-only query on the Convex network. Queries are free, instant, and never modify state.

**Expression:** `$0`
**Address (optional):** `$1` — the account context for the query (e.g. `#13`)

Use the `mcp__convex-testnet__query` tool.

## CVM Conventions

- Amounts are in **copper**: 1 CVM = 1,000,000,000 copper (10^9). Always convert to CVM for display.
- Account addresses use `#` prefix: `#13`, `#42`
- CNS paths use `@` prefix for actor lookup: `@convex.fungible`
- **Never use `import`** — use CNS-resolved paths like `(@convex.fungible/balance #128 #13)`

## Common Queries

| Task | Expression |
|------|-----------|
| Coin balance | `(balance #13)` |
| Own balance | `*balance*` |
| Account info | `(account #13)` |
| Token balance | `(@convex.fungible/balance #TOKEN #USER)` |
| Lookup symbol | `(lookup #ADDR 'symbol)` |
| Full state | `*state*` (large!) |

Present balances in human-readable CVM units (e.g. "1.5 CVM" not "1500000000 copper").
