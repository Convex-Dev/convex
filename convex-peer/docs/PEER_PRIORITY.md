# Peer Priority: Trusted Channel Classification

**Consumed by:** [BACKPRESSURE.md](BACKPRESSURE.md) — determines which channels are subject
to backpressure (clients) and which are exempt (peers).

## Problem Statement

All inbound connections to a Convex peer are currently treated identically. The server
dispatch does not distinguish between a trusted peer propagating Beliefs and an untrusted
client submitting transactions. This creates a critical vulnerability:

**An untrusted client flood can starve Belief propagation.**

If the server applies backpressure uniformly, legitimate peer-to-peer Belief exchange —
the heartbeat of consensus — stops. The network stalls even though the peers themselves
are healthy.

### Security Invariant

> Untrusted client traffic must **never** block or delay Belief merges between peers.

This invariant must hold regardless of how many client connections exist, how fast
clients submit transactions, or whether any queue is full.

## Implemented: Challenge/Response Protocol

The challenge/response protocol is fully implemented and the trust loop is closed.

### Components

| Component | Status |
|-----------|--------|
| `AConnection.isTrusted()` / `setTrustedKey()` | Implemented — set by `ConvexRemote.setVerifiedPeer()` |
| `Message.respondToChallenge()` | Shared handler — used by both peer Server and NodeServer |
| `Convex.verifyPeer()` | Client-side API — returns `CompletableFuture<AccountKey>` |
| `Convex.verifiedPeer` field | Set on success, cleared on close/reconnect |
| `ConnectionManager.identifyPeer()` | Async verify → fallback to status (untrusted) |
| `LatticeConnectionManager.tryVerifyPeer()` | Async fire-and-forget verification |
| `ConnectionManager.processChallenge()` | One-liner via `respondToChallenge()` with networkID context |
| `NodeServer.processChallenge()` | One-liner via `respondToChallenge()` |
| `ConvexLocal.sendChallenge()` | Routes through server message delivery |
| `ConvexDirect.sendChallenge()` | Optimised direct path using local peer key |
| `ConvexHTTP.sendChallenge()` | Via generic `/api/v1/message` endpoint |

### Protocol

Challenge/response uses the vector shape `[token, otherKey, contextID?]`:

| Direction | Payload | `otherKey` is... |
|-----------|---------|------------------|
| Challenge (client → server) | `SignedData([token, targetKey, contextID?])` | server's expected key |
| Response (server → client) | `SignedData([token, challengerKey, contextID?])` | challenger's key (from SignedData) |

- **token** — random 16-byte nonce, proves response matches this challenge
- **otherKey** — the key of the other party
- **contextID** — optional; peer Server uses networkID (genesis hash), NodeServer uses null
- CHALLENGE messages use ID-based correlation like QUERY/STATUS/TRANSACT

### Outbound Connection Flow (Implemented)

**Peer `ConnectionManager.connectToPeer()`** (async `CompletableFuture<Convex>`):
1. Connect to remote address
2. Try `verifyPeer(null, networkID)` — discovery mode with network context
3. If verified → `verifiedPeer` set, `AConnection.trustedKey` set, peer key proven
4. If verification fails → fall back to `requestStatus()` (untrusted, self-reported key)
5. Add connection keyed by peer identity

**Lattice `LatticeConnectionManager`**:
1. Connect to remote address
2. If key pair available, fire async `verifyPeer(peerKey)` — non-blocking
3. Connection immediately usable; `verifiedPeer` set when verification completes

## Implemented: Generic Message API

`POST /api/v1/message` accepts messages in CAD3 raw (`application/cvx-raw`) or CVX text
(`application/cvx`) format. Delivers to the peer server and returns the Result, honouring
the `Accept` header for response format (JSON, CVX, CVX raw). This enables challenge/response
over HTTP — `ConvexHTTP.sendChallenge()` uses this endpoint.

Response size is capped at 1MB via `Cells.storageSize()` in `setContent()`.

## Implemented: Error Responses

`Server.processMessage()` returns specific errors for unhandled messages:
- Unknown message type → `Result.error(:FORMAT, "Unrecognised message type")`
- Unexpected result message → `Result.error(:UNEXPECTED, "Unexpected result message")`

All error strings are static constants in `Strings` — no per-message string construction.

## Trust Model

Trust is **asymmetric** between outbound and inbound connections:

| Direction | Trust | Rationale |
|-----------|-------|-----------|
| **Outbound** (we connected to them) | Verified via `verifyPeer()` → trusted | We chose who to connect to; verified their identity |
| **Inbound** (they connected to us) | Always client by default | We don't know who they are; they could be anyone |

Inbound connections are **never promoted to full peer status**. The outbound connections
are what matter for secure Belief broadcast — we control who we broadcast to.

However, other peers' outbound connections appear as our inbound connections. A remote
peer connecting to us will send Beliefs that we need to process. We must accept these
but protect against untrusted clients flooding the Belief queue.

### Inbound Belief Handling

Beliefs from inbound connections are **accepted but deprioritised until verified**:

1. **Belief arrives from inbound connection `C`**
2. **If `C.isTrusted()`** → normal Belief processing (high priority)
3. **If not trusted** → accept into bounded low-priority Belief queue; trigger
   server-side challenge to verify the sender
4. **Challenge succeeds** → `C.setTrustedKey(key)`, subsequent Beliefs get normal priority
5. **Challenge fails or low-priority queue full** → drop the Belief

This means Beliefs are signed (so can be verified), and a verified inbound connection
gets faster Belief processing. But it is still an inbound client connection — still
subject to backpressure for non-Belief traffic.

### Flood Mitigations

1. **Peer count is bounded** — limited by staking requirements. A few dozen verified
   inbound connections cannot overwhelm the server.
2. **Beliefs are deduplicated** — `BeliefPropagator` handles duplicate detection.
   Repeated identical Beliefs are cheap to reject.
3. **Peers are accountable** — a misbehaving peer's key is known. The operator can
   blacklist it or the network can slash its stake.
4. **Low-priority queue is bounded** — unverified Belief senders can't fill the
   high-priority path.

## Message ↔ Connection

See [MESSAGING.md](MESSAGING.md) for the full connection and message architecture.

`Message` carries a single `connection` field. `returnMessage()` delegates to
`connection.returnMessage()`. `LocalConnection` is a paired bidirectional
channel with per-end `acceptsMessages` control — `sendMessage` can be
structurally disabled while `returnMessage` continues to work.

`AConnection.supportsMessage()` allows the server to check whether a connection
supports general messaging before attempting protocol exchange. `InboundVerifier`
checks this early to avoid spawning virtual threads for return-only connections.

## Implemented: Inbound Belief Deprioritisation (Phase 2)

### Components

| Component | Status |
|-----------|--------|
| `BeliefPropagator.untrustedBeliefQueue` | Small bounded queue (10), non-blocking poll |
| `Server.processBelief()` | Routes by `conn.isTrusted()` — trusted→main, untrusted→low-priority |
| `InboundVerifier.maybeStart()` | CAS-guarded, virtual thread, sends CHALLENGE |
| `InboundVerifier.handleResult()` | Routes inbound RESULT to pending verification |
| `AConvexConnected.returnMessageHandler` | Auto-responds to server-initiated CHALLENGE |
| Client-side connection on messages | `NettyConnection` sets itself on inbound handler |

### Flow

1. **Untrusted belief arrives** → `Server.processBelief()` checks `conn.isTrusted()`
2. **Untrusted** → `propagator.queueUntrustedBelief(m)` (best-effort, bounded queue)
3. **Trigger verification** → `InboundVerifier.maybeStart(conn)` (no-op if already in progress)
4. **Virtual thread** sends CHALLENGE on `conn`, client auto-responds via `respondToChallenge()`
5. **Client RESULT** routed through `Server.processMessage()` → `InboundVerifier.handleResult()`
6. **Verification succeeds** → `conn.setTrustedKey(remoteKey)`, subsequent beliefs go to main queue
7. **awaitBelief()** drains main queue first, then polls one untrusted belief per cycle (non-blocking)

### Fast Path Guarantees

- **Per-message cost (trusted):** two field reads (`getConnection()`, `isTrusted()`) + queue offer
- **Per-message cost (untrusted, verification in progress):** above + `containsKey()` check → return
- **No blocking** on inbound connection — reads continue for challenge response
- **No concurrent verifications** per connection — `ConcurrentHashMap.putIfAbsent()` guard

## Remaining Work

### Phase 3: Backpressure Integration

1. Restrict `setAutoRead(false)` to connections where `!isTrusted()`
2. Verify trusted (outbound peer) connections are never paused
3. Inbound verified connections: Beliefs exempt from backpressure,
   other traffic (transactions, queries) still subject to it
4. Validate verified peer key against consensus state (minimum stake)

## Summary

| Aspect | Design |
|--------|--------|
| Outbound trust | `verifyPeer()` sets `verifiedPeer` + `AConnection.trustedKey` — **implemented** |
| Inbound trust | Server-initiated challenge on first untrusted belief — **implemented** |
| Belief priority | Trusted → main queue; untrusted → small bounded queue (1 per cycle) — **implemented** |
| Backpressure | Applied to all inbound connections for non-Belief traffic |
| Message routing | `Message` carries `AConnection`; paired `LocalConnection` for in-JVM — see [MESSAGING.md](MESSAGING.md) |
| Security invariant | Untrusted clients **never** block Belief propagation — **implemented** |
| Generic message API | `POST /api/v1/message` — CAD3 raw or CVX text — **implemented** |
