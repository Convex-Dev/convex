# MCP Upgrade Opportunities

Improvement opportunities for the Convex peer MCP server, with rationale for each.

## Current State

- **Protocol version:** `2025-03-26`
- **29 tools** across 4 categories (core, crypto, account, signing service)
- **6 prompts** (3 always, 3 conditional on signing service)
- **Transport:** Streamable HTTP (POST `/mcp`), no SSE
- **Capabilities declared:** `tools`, `prompts`
- **Auth:** Bearer token + elevated confirmation flow for sensitive operations
- **All tools** have `annotations` (`readOnlyHint`, `destructiveHint`, `idempotentHint`) and `outputSchema`

## Protocol Upgrades

### Upgrade to MCP 2025-06-18

The current spec is `2025-03-26`. The latest stable MCP spec is `2025-06-18`.

**Key additions in 2025-06-18:**
- `openWorldHint` annotation — signals tools with side effects beyond the server
- `title` on tools — human-readable display name (already present in our JSON metadata but not yet in spec compliance)
- Structured tool output — `structuredContent` field (already implemented)
- Elicitation — server can ask the client for missing info mid-tool-call

**Effort:** Low. Bump version string and add `openWorldHint` where relevant (e.g. `transact`, `signAndSubmit`, `submit`).

### SSE / Server-Sent Events

Currently `GET /mcp` returns 405. The MCP Streamable HTTP transport spec allows SSE for long-running operations and server-initiated notifications.

**Use cases:**
- Transaction confirmation notifications (block inclusion)
- Consensus state changes
- Account balance subscriptions

**Rationale:** Most MCP clients (Claude Code, Cursor) don't use SSE yet, so this is low priority. When needed, the Javalin SSE support makes it straightforward.

**Effort:** Medium. Requires session management and notification dispatch.

## Tool Improvements

### Dynamic Tool Descriptions

Tool descriptions are static JSON. Runtime context (faucet availability, faucet max, network ID) should be injected into descriptions when tools are listed.

**Example:** `createAccount` description should include:
- Whether faucet is available
- Maximum faucet amount (in copper and CVM)
- What the default controller is

**Rationale:** Agents make better decisions when tool descriptions reflect the actual peer configuration.

**Effort:** Low. Override `getMetadata()` in relevant tools or post-process at `tools/list` time.

### Tool Grouping / Categories

MCP doesn't have native tool categories, but the `title` and description can hint at grouping. Consider:
- Prefixing signing tools: already done (`signing*`)
- Using consistent naming: `query`, `transact`, `prepare` → core; `encode`, `decode`, `hash` → crypto

Currently naming is consistent. No action needed unless the tool count grows significantly.

### Convenience Tools

**`transferCoins`** — A dedicated tool for `(transfer #DEST amount)` would reduce agent errors vs. constructing source manually. Common pattern, high usage, easy to validate inputs.

**`deployActor`** — Wrap `(deploy '(...))` with source validation and return the new address. Agents frequently deploy actors and need the address back.

**`queryBalance`** — Shortcut for `(balance #ADDR)`. Most common query. Returns structured `{address, balance, balanceCVM}` with human-readable formatting.

**Rationale:** Convenience tools reduce prompt tokens, eliminate common CVM syntax errors, and make tool outputs predictable. Each is a thin wrapper over `transact`/`query`.

**Effort:** Low per tool. JSON metadata + ~20 lines of handler.

### `eval-as` Support

With the new controller feature (`createAccount` sets controller by default), agents can use `eval-as` to manage accounts they've created. A dedicated `evalAs` tool would be ergonomic:

```
evalAs(controller, target, source) → transact as controller: (eval-as #target source)
```

**Rationale:** Core agentic pattern — create account, then manage it. Currently requires constructing `eval-as` source manually.

### Error Enrichment

CVM errors return `errorCode` (e.g. `:FUNDS`, `:NOBODY`, `:UNDECLARED`) but no human-readable guidance. Tool responses could include a brief hint for common error codes:

- `:FUNDS` → "Insufficient balance for transfer"
- `:NOBODY` → "Account does not exist"
- `:UNDECLARED` → "Symbol not defined in current environment"
- `:ARITY` → "Wrong number of arguments to function"

**Rationale:** Agents recover faster with hints. Reduces retry loops.

**Effort:** Low. Lookup table on error code, append to response.

## Resource Support

### MCP Resources

MCP supports `resources/list` and `resources/read` for exposing structured data. Currently not implemented.

**Candidate resources:**
- `convex://peer/status` — peer status (auto-refreshed)
- `convex://account/{address}` — account state
- `convex://cns/{name}` — CNS resolution

**Rationale:** Resources are better than tools for data that changes infrequently and might be cached. Agents can subscribe to resource updates.

**Effort:** Medium. Requires implementing the resources capability, resource templates, and change notifications.

### Resource Templates

MCP resource templates let agents discover parameterised resources:
```json
{"uriTemplate": "convex://account/{address}", "name": "Account State"}
```

**Effort:** Low once resource infrastructure exists.

## Prompt Improvements

### Expand Prompt Library

Current prompts cover basic workflows. Additional prompts:

- **`audit-contract`** — examine an actor's code, permissions, and state
- **`manage-tokens`** — create, mint, transfer fungible tokens using the asset system
- **`debug-transaction`** — diagnose a failed transaction (lookup error, inspect state, suggest fix)

**Rationale:** Prompts encode expert knowledge that agents can invoke. They reduce the need for detailed system prompts.

### Prompt Arguments with Enum Validation

Some prompt arguments should have constrained values. MCP supports `enum` in argument schemas.

**Example:** `convex-guide` topic could enumerate: `"data types"`, `"actors"`, `"asset system"`, `"governance"`, `"trust"`.

## Security Improvements

### Rate Limiting

No per-identity rate limiting on faucet or transaction tools.

**Rationale:** Prevents abuse in public-facing deployments. The faucet is particularly sensitive — a single agent could drain it.

**Approach:** Track requests per bearer token identity with sliding window. Return standard `429 Too Many Requests`.

**Effort:** Medium. Needs per-identity tracking, configurable limits.

### Faucet Budgeting

Beyond per-request max (now configurable via `:faucet-max`), consider per-identity daily/hourly budgets.

**Rationale:** Even with per-request caps, repeated requests can drain faucet.

**Effort:** Medium. Requires state tracking per identity.

### Audit Logging

Log all tool invocations with identity, tool name, arguments (redacting seeds/passphrases), and result status.

**Rationale:** Operational visibility. Essential for public deployments.

**Effort:** Low. SLF4J logging in `toolCall()`.

## Infrastructure

### Metrics / Observability

Expose tool call counts, latencies, and error rates. Could use:
- MCP `logging/` capability for structured log messages
- Prometheus-style `/metrics` endpoint
- Lattice-backed counters (persistent across restarts)

**Effort:** Medium.

### Health Check Endpoint

Dedicated `/health` or `/ready` endpoint for container orchestrators. Currently `GET /.well-known/mcp` serves as an informal liveness check.

**Effort:** Trivial.

### Batch Tool Execution

Allow clients to call multiple tools in a single JSON-RPC batch. Already supported at the JSON-RPC level, but could add an explicit `tools/batch` method that executes tools atomically or returns partial results.

**Rationale:** Reduces round-trips for multi-step workflows.

## Recently Completed

### Annotations (Done)
All 29 tools now have `readOnlyHint`, `destructiveHint`, `idempotentHint` annotations.

### Controller on createAccount (Done)
`createAccount` and `signingCreateAccount` accept optional `controller` parameter:
- Default `*caller*` — faucet account becomes controller (agent can `eval-as`)
- `nil` — self-sovereign account (no external controller)
- `#ADDR` — specific controller address

### Configurable Faucet Max (Done)
Peer config key `:faucet-max` (Long, in copper) overrides the default 1 Gold (1,000,000,000 copper) limit.

### Compact Descriptions (Done)
All tool descriptions trimmed for context efficiency. Signing service boilerplate removed. Coin units documented where relevant.
