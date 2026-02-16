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

### Existing Trust Infrastructure

The codebase already has the building blocks for trust classification:

| Component | Status |
|-----------|--------|
| `AConnection.isTrusted()` / `setTrustedKey()` | Implemented but `setTrustedKey()` is never called |
| `ChallengeRequest` protocol | Fully implemented |
| `ConnectionManager.processChallenge()` | Fully implemented |
| `ConnectionManager.processResponse()` | Implemented but `setTrustedKey()` call is **commented out** |
| `ConnectionManager.requestChallenge()` | Fully implemented with timeout/dedup |

The **protocol** is complete — mutual challenge/response proves possession of a peer's
private key. The **trust loop never closes** because the final `setTrustedKey()` call
is commented out.

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

1. **Channel closes** — cleanup on disconnect
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

## Implementation Sequence

### Phase 1: Channel Tracking

1. Wire channel reference into `Message` (or handler context) so `Server` can
   look up trust status per message
2. Register inbound channels on connection, remove on disconnect
3. All channels start as untrusted

### Phase 2: Trust Completion

1. Uncomment `setTrustedKey()` in `ConnectionManager.processResponse()`
2. Validate peer key against consensus state before promoting
3. Trigger challenge on first Belief from untrusted channel
4. Add `promoteToTrusted()` to Server — moves channel from client set to peer set

### Phase 3: Backpressure Integration

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
| Key fix needed | Uncomment `setTrustedKey()` in `processResponse()` |
