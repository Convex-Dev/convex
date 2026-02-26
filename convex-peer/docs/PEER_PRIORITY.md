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

## Design: Message ↔ Connection

### Problem

Currently `Message` uses a `Predicate<Message>` return handler for routing responses
back to the sender. This works but has drawbacks:

- No access to the originating connection (can't check trust, apply backpressure)
- A closure per message for routing
- `closeConnection()` just nulls the handler — doesn't actually close a connection
- `ConvexLocal` and HTTP use the handler for result delivery but have no connection

### Design: Message carries optional AConnection

Add an optional `AConnection` field to `Message`. When present, `returnMessage()` uses
the connection's `sendMessage()` directly. When absent (local/HTTP), falls back to the
existing handler predicate.

```
Message
  ├── payload: ACell
  ├── messageData: Blob
  ├── type: MessageType
  ├── returnHandler: Predicate<Message>     (existing — used for local/HTTP)
  └── connection: AConnection               (new — set for network messages)
```

**`returnMessage()` becomes:**
1. If `connection` is set → `connection.sendMessage(resultMsg)`
2. Else if `returnHandler` is set → `returnHandler.test(resultMsg)`
3. Else → throw (no return path)

**`closeConnection()` becomes:** actually close the connection if present.

### LocalConnection

For symmetry, `ConvexLocal` uses a lightweight `AConnection` subclass instead of null:

```java
public class LocalConnection extends AConnection {
    private final Predicate<Message> handler;

    public boolean sendMessage(Message msg) {
        return handler.test(msg);
    }
    public InetSocketAddress getRemoteAddress() { return null; }
    public boolean isClosed() { return false; }
    public void close() { }
    public long getReceivedCount() { return 0; }
}
```

This keeps the API uniform — every Message has a connection, `Server.processMessage()`
can always call `m.getConnection().isTrusted()`. The local connection is never trusted
(it's an in-JVM client, not a peer).

### Benefits

- `Server.processMessage()` can check `m.getConnection().isTrusted()` for Belief priority
- `closeConnection()` actually closes the network connection
- Backpressure can target specific connections
- No per-message closure allocation for network messages
- Clean API — connection is always available, trust is always queryable

## Remaining Work

### Phase 1: Message ↔ Connection (next)

1. Add `AConnection connection` field to `Message` with getter
2. Update `returnMessage()` to use connection when available
3. Create `LocalConnection extends AConnection` for `ConvexLocal`
4. Set connection on Messages from network (Netty/NIO receive path)
5. Update `ConvexLocal.makeMessageFuture()` to use `LocalConnection`

### Phase 2: Inbound Belief Deprioritisation

1. Server checks `m.getConnection().isTrusted()` when processing Beliefs
2. Trusted → existing high-priority path
3. Untrusted → bounded low-priority queue; trigger server-side challenge
4. On successful challenge → `connection.setTrustedKey(key)`
5. Validate peer key against consensus state (minimum stake)

### Phase 3: Backpressure Integration

1. Restrict `setAutoRead(false)` to connections where `!isTrusted()`
2. Verify trusted (outbound peer) connections are never paused
3. Inbound verified connections: Beliefs exempt from backpressure,
   other traffic (transactions, queries) still subject to it

## Summary

| Aspect | Design |
|--------|--------|
| Outbound trust | `verifyPeer()` sets `verifiedPeer` + `AConnection.trustedKey` — **implemented** |
| Inbound trust | Always client; verified for Belief priority only |
| Belief priority | Trusted connections → high priority; untrusted → low-priority bounded queue |
| Backpressure | Applied to all inbound connections for non-Belief traffic |
| Message routing | `Message` carries optional `AConnection`; `LocalConnection` for in-JVM |
| Security invariant | Untrusted clients **never** block Belief propagation |
| Generic message API | `POST /api/v1/message` — CAD3 raw or CVX text — **implemented** |
