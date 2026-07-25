---
name: query
description: Execute a read-only CVM query on the Convex network. Use when reading on-chain state, checking balances, looking up accounts, or evaluating Convex Lisp expressions.
argument-hint: "<cvx-expression> [address]"
---

# Query Convex State

Queries are read-only, free and instant. They never modify state and need no
key, so prefer a query over a transaction whenever you only need to read.

**Expression:** `$0`
**Address (optional):** `$1` — the account context for the query (e.g. `#13`)

See the `convex-lisp` skill for CVM conventions and error codes.

## How to Run It

**With a Convex MCP server configured** (tool names look like
`mcp__<server>__query`), use its `query` tool. The server is configured per
user, not by this repository — do not assume it is present.

**Otherwise use the CLI**, which always works from a built `convex.jar`:

```bash
java -jar convex.jar client query '(balance #13)'
java -jar convex.jar client query --host <host> --port <port> '(balance #13)'
java -jar convex.jar client query -a #13 '*balance*'
```

`-a` / `--address` sets the account context. Against a local network started
with the `local-network` skill, pass `--host localhost --port <PORT>`.

## Common Queries

| Task | Expression |
|------|-----------|
| Coin balance | `(balance #13)` |
| Own balance | `*balance*` |
| Account info | `(account #13)` |
| Token balance | `(@convex.fungible/balance #TOKEN #USER)` |
| Token supply | `(@convex.fungible/total-supply #TOKEN)` |
| Lookup symbol | `(lookup #ADDR 'symbol)` |
| Full state | `*state*` (large!) |

`lookup` needs a literal symbol as its last argument — it is resolved at
compile time, so a computed symbol fails with `:COMPILE`.

Present balances in CVM units (e.g. "1.5 CVM", not "1500000000 copper").
