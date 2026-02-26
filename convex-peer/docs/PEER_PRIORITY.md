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

## Current State

### Trust Infrastructure

The challenge/response protocol is fully implemented and the trust loop is closed:

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

### Protocol

Challenge/response uses the vector shape `[token, otherKey, contextID?]`:

| Direction | Payload | `otherKey` is... |
|-----------|---------|------------------|
| Challenge (client → server) | `SignedData([token, targetKey, contextID?])` | server's expected key (or null for discovery) |
| Response (server → client) | `SignedData([token, challengerKey, contextID?])` | challenger's key (from SignedData) |

- **token** — random 16-byte nonce, proves response matches this challenge
- **otherKey** — the key of the other party
- **contextID** — optional; peer Server uses networkID (genesis hash), NodeServer uses null
- CHALLENGE messages use ID-based correlation like QUERY/STATUS/TRANSACT

### Outbound Connection Flow

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

## Design: Channel Trust Classification

### Goal

Classify every inbound channel as either **trusted** (peer) or **untrusted** (client).
Only untrusted channels are subject to backpressure. Trusted channels are always read.

### Trust Lifecycle

1. **Connection arrives** — registered as untrusted (client) by default
2. **Belief received from untrusted channel** — triggers challenge/response handshake
3. **Successful mutual authentication** — channel promoted to trusted (peer)
4. **Channel closes** — trust revoked, channel unregistered

### Authentication Trigger

Receiving a `BELIEF` message from an untrusted channel is a natural trigger to verify
the sender. Legitimate peers broadcast Beliefs; clients never do. The Belief is still
processed during authentication — don't drop it while verifying.

### Peer Validation

A connection should only be promoted to trusted if the remote peer's `AccountKey` is
a valid active peer in the current consensus state with minimum effective stake. This
prevents authentication with a valid key pair that isn't actually a registered peer.
The check uses local consensus state (eventually consistent) — a newly joined peer
might not appear immediately, but the challenge can be retried.

### Trust Revocation

Trusted status is revoked when:

1. **Channel closes** — `ConvexRemote.close()` clears `verifiedPeer` and connection
2. **Peer removed from state** — periodic sweep (optional, low priority)
3. **Invalid data from trusted channel** — demote on malformed messages

## Integration with Backpressure

[BACKPRESSURE.md](BACKPRESSURE.md) defines the mechanism; this document defines the
policy for which channels it applies to:

| Channel Type | Backpressure | Rationale |
|-------------|-------------|-----------|
| **Client** (untrusted) | Yes — pause reads when queue full | Protects server from client flood |
| **Peer** (trusted) | Never | Belief propagation must not be interrupted |

### Peer Channel Flood Protection

Exempting peer channels from backpressure raises the question: what if a compromised
peer floods us? Mitigations:

1. **Peer count is bounded** — limited by staking requirements. A few dozen trusted
   channels cannot overwhelm the server.
2. **Beliefs are deduplicated** — `BeliefPropagator` handles duplicate detection.
   Repeated identical Beliefs are cheap to reject.
3. **Peers are accountable** — a misbehaving peer's key is known. The operator can
   blacklist it or the network can slash its stake.
4. **Separate queues** — peer transaction forwarding (if implemented) can use a
   separate high-priority queue with its own capacity.

## Remaining Work

### Phase 1: Inbound Channel Trust (next)

1. Wire channel reference into `Message` (or handler context) so `Server` can
   look up trust status per message
2. Register inbound channels on connection, remove on disconnect
3. All channels start as untrusted
4. Trigger challenge on first Belief from untrusted channel
5. Validate peer key against consensus state before promoting
6. Add `promoteToTrusted()` to Server — moves channel from client set to peer set

### Phase 2: Backpressure Integration

1. Restrict `setAutoRead(false)` to client channels only
2. Verify peer channels are never paused

## Summary

| Aspect | Design |
|--------|--------|
| Default trust | All inbound channels start as **untrusted** (client) |
| Promotion | Challenge/response authentication → promote to trusted |
| Trigger | First Belief received from untrusted channel |
| Validation | Peer key must be active in consensus state with minimum stake |
| Revocation | Channel close, or malformed data from trusted channel |
| Backpressure | Applied to client channels only; peer channels always read |
| Security invariant | Untrusted clients **never** block Belief propagation |
| Outbound trust | `verifyPeer()` sets `verifiedPeer` + `AConnection.trustedKey` |
