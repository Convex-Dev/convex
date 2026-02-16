# Peer Priority: Trusted Channel Classification

**Consumed by:** [BACKPRESSURE.md](BACKPRESSURE.md) — determines which channels are subject
to backpressure (clients) and which are exempt (peers).

## Problem Statement

All inbound connections to a Convex peer are treated identically. The `Server.processMessage()`
dispatch does not distinguish between a trusted peer propagating Beliefs and an untrusted
client submitting transactions. This creates a critical vulnerability:

**An untrusted client flood can starve Belief propagation.**

If the server applies backpressure uniformly (e.g. pausing reads on all channels when a
queue fills), legitimate peer-to-peer Belief exchange — the heartbeat of consensus — stops.
The network stalls even though the peers themselves are healthy.

### Security Invariant

> Untrusted client traffic must **never** block or delay Belief merges between peers.

This invariant must hold regardless of:
- How many client connections exist
- How fast clients submit transactions
- Whether the transaction queue is full

## Current Architecture

### Inbound Connection Setup

```
Client/Peer              NettyServer
──────────               ──────────
    TCP connect ─────►   initChannel(SocketChannel ch)
                              │
                              ▼
                         NettyInboundHandler(receiveAction, returnHandler)
                              │  ← no trust context
                              │  ← no channel tracking
                              ▼
                         Server.processMessage(m)
                              │
                         ┌────┴────────────────┐
                         │ BELIEF → processBelief()
                         │ TRANSACT → processTransact()
                         │ CHALLENGE → processChallenge()
                         │ RESPONSE → processResponse()
                         │ QUERY → processQuery()
                         └─────────────────────┘
```

All message types share the same I/O thread and dispatch path. There is no per-channel
metadata distinguishing peers from clients.

### Existing Trust Infrastructure

The codebase already has the building blocks for trust classification:

| Component | Location | Status |
|-----------|----------|--------|
| `AConnection.isTrusted()` | `convex-peer/.../net/AConnection.java` | Implemented, returns `trustedKey != null` |
| `AConnection.setTrustedKey(AccountKey)` | Same | Implemented but **never called** |
| `ChallengeRequest` | `convex-peer/.../net/ChallengeRequest.java` | Fully implemented |
| `ConnectionManager.processChallenge()` | `convex-peer/.../peer/ConnectionManager.java` | Fully implemented |
| `ConnectionManager.processResponse()` | Same | Implemented but `setTrustedKey()` call is **commented out** |
| `ConnectionManager.requestChallenge()` | Same | Fully implemented with timeout/dedup |

### What Works

The **protocol** is complete:

1. Peer A sends `CHALLENGE`: `[random-token, networkID, peerB-key]` signed by A
2. Peer B validates: correct network, addressed to B, valid signature from A
3. Peer B sends `RESPONSE`: `[token, networkID, peerA-key, challenge-hash]` signed by B
4. Peer A validates: token matches, network matches, hash matches, signature from B

This proves that B possesses the private key corresponding to `peerB-key`. It cannot
be spoofed — unlike a signed Belief, which is public data anyone can relay.

### What's Broken

In `ConnectionManager.processResponse()` (line ~484):

```java
Convex connection = getConnection(fromPeer);
if (connection != null) {
    // connection.setTrustedKey(fromPeer);  // ← COMMENTED OUT
}
```

The trust loop never closes. Even after successful challenge/response, `isTrusted()`
returns `false` for every connection.

## Design: Channel Trust Classification

### Goal

Classify every inbound channel as either **trusted** (peer) or **untrusted** (client).
Only untrusted channels are subject to backpressure. Trusted channels are always read.

### Step 1: Complete the Trust Handshake

Uncomment and fix `setTrustedKey()` in `processResponse()`:

```java
// In ConnectionManager.processResponse()
AccountKey fromPeer = signedData.getAccountKey();
// ... validation ...

// Close the trust loop
AConnection conn = getConnectionForPeer(fromPeer);
if (conn != null) {
    conn.setTrustedKey(fromPeer);
    log.info("Peer {} authenticated via challenge/response", fromPeer);
}
return fromPeer;
```

This requires access to the underlying `AConnection` (not the `Convex` wrapper).
The `ConnectionManager` already tracks connections — it needs a method to retrieve
the raw `AConnection` for a peer key.

### Step 2: Track Inbound Channels in Server

The server must maintain a registry of active inbound channels with their trust status.
This is the integration point with [BACKPRESSURE.md](BACKPRESSURE.md).

```java
// In Server
private final Set<Channel> clientChannels = ConcurrentHashMap.newKeySet();
private final Set<Channel> peerChannels = ConcurrentHashMap.newKeySet();

// Called from NettyServer.initChannel()
public void registerInboundChannel(Channel ch) {
    clientChannels.add(ch);  // all channels start as untrusted
}

// Called when a channel is authenticated as a peer
public void promoteToTrusted(Channel ch) {
    clientChannels.remove(ch);
    peerChannels.add(ch);
}

// Called on channel close
public void unregisterChannel(Channel ch) {
    clientChannels.remove(ch);
    peerChannels.remove(ch);
}
```

### Step 3: Authenticate Inbound Peers

When a peer connects inbound (it connected to us), we don't automatically know it's a
peer. The flow:

1. Connection arrives → registered as `clientChannel` (untrusted)
2. Remote sends `CHALLENGE` → we respond (existing `processChallenge()`)
3. We send `CHALLENGE` back → remote responds
4. On successful `RESPONSE` → `promoteToTrusted(channel)`

Both sides must challenge each other. The existing `requestChallenge()` method
handles outbound challenges with deduplication and timeouts.

**When to initiate inbound challenge:** On receiving a `BELIEF` message from an
untrusted channel. A legitimate peer will be broadcasting Beliefs; receiving one
is a natural trigger to verify the sender's identity.

```java
// In Server.processBelief()
protected void processBelief(Message m) {
    Channel ch = m.getChannel();
    if (!isChannelTrusted(ch)) {
        // Trigger challenge/response for this channel
        manager.requestChallengeForInbound(ch, getPeer());
        // Still process the Belief — don't drop it while authenticating
    }
    beliefPropagator.queueBelief(m);
}
```

### Step 4: Wire Channel Reference into Message

Currently, `Message` does not carry a reference to the originating `Channel`. The
`NettyInboundHandler` creates messages with a `returnAction` (lambda over `ch`) but
the channel itself is not directly accessible.

Add a channel reference to `Message` or to the handler context:

```java
// In NettyServer.initChannel()
NettyInboundHandler inbound = new NettyInboundHandler(getReceiveAction(), returnHandler);
inbound.setChannel(ch);  // or pass as constructor arg
```

```java
// In NettyInboundHandler.decode()
Message m = Message.create(returnAction, null, Blob.wrap(messageData));
m.setChannel(ctx.channel());  // attach originating channel
```

This gives `Server.processMessage()` access to the channel for trust lookups
and backpressure control.

## Peer Validation

A connection should only be promoted to trusted if the remote peer's `AccountKey` is
recognised as a **valid peer** in the current consensus state. This prevents an
attacker from authenticating with a valid key pair that isn't actually a registered peer.

```java
// Validation before promoting
State state = getPeer().getConsensusState();
PeerStatus ps = state.getPeer(peerKey);
if (ps == null || ps.getTotalStake() < CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
    log.warn("Challenge/response succeeded but {} is not an active peer", peerKey);
    return;  // do not promote
}
promoteToTrusted(channel);
```

This check uses the local consensus state, which is eventually consistent. A newly
joined peer might not appear in the state immediately — that's fine, as the challenge
can be retried.

## Trust Revocation

Trusted status should be revoked when:

1. **Channel closes** — `unregisterChannel()` on disconnect
2. **Peer removed from state** — periodic sweep (optional, low priority)
3. **Invalid Belief received** — if a trusted channel sends malformed data, demote it

For (1), Netty's `channelInactive()` callback provides the trigger:

```java
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    server.unregisterChannel(ctx.channel());
    ctx.fireChannelInactive();
}
```

## Integration with Backpressure

[BACKPRESSURE.md](BACKPRESSURE.md) defines the mechanism; this document defines the
policy for which channels it applies to:

| Channel Type | `setAutoRead(false)` | Rationale |
|-------------|---------------------|-----------|
| **Client** (untrusted) | Yes — when txn queue high watermark exceeded | Protects server from client flood |
| **Peer** (trusted) | Never | Belief propagation must not be interrupted |

The `Server.pauseClientReads()` method from BACKPRESSURE.md iterates `clientChannels`
only — `peerChannels` are never touched.

```java
// From BACKPRESSURE.md — only applies to clientChannels
private void pauseClientReads() {
    if (readsPaused) return;
    readsPaused = true;
    for (Channel ch : clientChannels) {
        ch.config().setAutoRead(false);
    }
    // peerChannels are NEVER paused
}
```

## Peer Channel Flood Protection

Exempting peer channels from backpressure raises the question: what if a compromised
peer floods us? Mitigations:

1. **Peer count is bounded** — the number of active peers is limited by staking
   requirements. A few dozen trusted channels cannot overwhelm the server.
2. **Beliefs are deduplicated** — `BeliefPropagator` already handles duplicate
   Belief detection. Repeated identical Beliefs are cheap to reject.
3. **Peers are accountable** — a misbehaving peer's key is known. The operator
   can blacklist it or the network can slash its stake.
4. **Separate queues** — TRANSACT messages from peers (transaction forwarding, if
   implemented) can use a separate high-priority queue with its own capacity.

## Implementation Sequence

### Phase 1: Channel Tracking

1. Add `Channel` reference to `Message` (or `NettyInboundHandler` context)
2. Register inbound channels in `Server.registerInboundChannel()`
3. Remove channels on disconnect via `channelInactive()`
4. All channels start as untrusted (client)

### Phase 2: Trust Completion

1. Uncomment `setTrustedKey()` in `ConnectionManager.processResponse()`
2. Add `promoteToTrusted()` to `Server` — moves channel from client set to peer set
3. Validate peer key against consensus state before promoting
4. Trigger challenge on first Belief from untrusted channel

### Phase 3: Backpressure Integration

1. Pass `clientChannels` set to backpressure mechanism (BACKPRESSURE.md Phase 1)
2. Verify peer channels are never paused — add assertion / log warning

## Thread Safety

| Operation | Thread | Notes |
|-----------|--------|-------|
| `registerInboundChannel()` | Netty I/O | `ConcurrentHashMap.newKeySet()` is safe |
| `promoteToTrusted()` | Netty I/O (from processResponse) | Atomic remove + add on concurrent sets |
| `unregisterChannel()` | Netty I/O (from channelInactive) | Safe concurrent removal |
| `pauseClientReads()` iteration | Any | Concurrent set allows safe iteration |
| `isTrusted()` check | Netty I/O | Volatile read of `trustedKey` field |

## Summary

| Aspect | Design |
|--------|--------|
| Default trust | All inbound channels start as **untrusted** (client) |
| Promotion | Challenge/response authentication → `promoteToTrusted()` |
| Trigger | First Belief received from untrusted channel |
| Validation | Peer key must be active in consensus state with minimum stake |
| Revocation | Channel close, or malformed data from trusted channel |
| Backpressure | Applied to `clientChannels` only; `peerChannels` always read |
| Security invariant | Untrusted clients **never** block Belief propagation |
| Existing infrastructure | `ChallengeRequest`, `AConnection.isTrusted()`, `ConnectionManager` |
| Key fix needed | Uncomment `setTrustedKey()` in `processResponse()` |
