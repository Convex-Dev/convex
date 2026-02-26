# Messaging and Connection Architecture

Architecture for the universal messaging model used across all Convex and
lattice applications. This document defines the relationships between
connections, messages, results, and client APIs.

**See also:**
- [P2P_DESIGN.md](P2P_DESIGN.md) — network topology, transport, discovery
- [PEER_PRIORITY.md](PEER_PRIORITY.md) — trust, verification, belief priority
- [BACKPRESSURE.md](BACKPRESSURE.md) — per-channel flow control

## 1. Principles

1. **Symmetric P2P model.** A connection is between two peers. Neither side is
   privileged. Both sides have the same interface. Asymmetry is in configuration
   (handlers, capabilities), not in the model.

2. **One connection abstraction.** `AConnection` is the universal interface for
   all communication channels — network sockets, in-memory channels, HTTP
   request/response. Different implementations provide different capabilities
   but the same API.

3. **Message carries one field** — the connection it arrived on. No separate
   return handler. The connection knows how to deliver results.

4. **Connections are paired.** Every connection has two ends. Each end can send
   to the other. Each end has its own trust state. The pair is the
   relationship; each end is one participant's view of it.

## 2. Core Concepts

### 2.1 Connection (`AConnection`)

A connection end represents one participant's view of a communication channel
with another participant. It provides three communication methods:

| Method | Semantics | Blocking | Used for |
|---|---|---|---|
| `returnMessage(Message)` | Deliver a result to the other end | Non-blocking | Result delivery from processing threads |
| `sendMessage(Message)` | Push an arbitrary message to the other end | May block (bounded) | General messaging, protocol exchange |
| `supportsMessage()` | Check if `sendMessage` is available | — | Guard before protocol exchange |

`returnMessage` and `sendMessage` are independent capabilities. A connection
may support result delivery without supporting general messaging. The
`supportsMessage()` check allows callers to test this before attempting
server-initiated protocol exchange (e.g. challenge/response).

Additional state and lifecycle:
- `isTrusted()` / `getTrustedKey()` / `setTrustedKey(AccountKey)` — this end's
  verified trust in the remote participant
- `getRemoteAddress()` — remote socket address if applicable
- `isClosed()` / `close()` — lifecycle
- `getReceivedCount()` — statistics

### 2.2 Message

A `Message` is a unit of communication with a type, payload, and a reference to
the connection it arrived on (or was sent with).

```
Message
  ├── type: MessageType
  ├── payload: ACell
  ├── messageData: Blob         (wire-format cache)
  └── connection: AConnection   (the connection this message arrived on)
```

`Message.returnMessage(result)` delegates to `connection.returnMessage(result)`.
There is no separate `returnHandler` field — the connection is the sole
abstraction for message delivery.

### 2.3 Connection Capabilities

Different connection configurations provide different capabilities:

| Configuration | returnMessage | sendMessage | Trust | Lifecycle |
|---|---|---|---|---|
| Network peer (Netty/NIO) | yes (socket) | yes (socket) | yes | persistent |
| Local bidirectional pair | yes (to paired end) | yes (to paired end) | yes | persistent |
| Local return-only pair | yes (to paired end) | no (`supportsMessage()` = false) | no | persistent |

The distinction between "bidirectional" and "return-only" is set at creation
time via the `acceptsMessages` flag on each end of a `LocalConnection` pair.

### 2.4 Trust

Trust is **per-end**. Each end of a connection independently tracks whether it
has verified the remote participant's identity:

- `endA.setTrustedKey(keyB)` — A has verified B
- `endB.setTrustedKey(keyA)` — B has verified A
- These are independent

Trust is established by the [challenge/response verification protocol](PEER_PRIORITY.md)
(Ed25519 signatures over random tokens). The verification protocol is universal
— any participant with an Ed25519 keypair can verify any other, regardless of
application.

What trust **means** is application-specific policy, separate from the
connection layer:
- Consensus peer: prioritise beliefs, exempt from backpressure
- Lattice node: accept merge data from verified sources
- AI agent: authorise privileged operations
- Generic: "I have cryptographically verified who you are"

## 3. Local Connections — Paired Channel

### 3.1 The Pair Model

A local connection is one end of a paired in-memory channel. Each pair consists
of two `LocalConnection` instances that reference each other. Sending on one
end delivers to the other end's handler, with the receiving end's connection
stamped on the message.

```
endA.sendMessage(msg) → endB receives msg.withConnection(endB)
endB.sendMessage(msg) → endA receives msg.withConnection(endA)
```

The receiver sees its own end on the message. Calling `msg.returnMessage(result)`
on a received message sends through that end, which delivers to the original
sender. This is symmetric — the same mechanism works in both directions.

The mutual reference between paired ends is the in-memory equivalent of a
shared network socket. From the processing perspective, a local paired
connection is indistinguishable from a network connection.

### 3.2 How a Pair Works

```
              endA                              endB
         (participant A's view)            (participant B's view)
        ┌─────────────────┐              ┌─────────────────┐
        │  handler: ...   │              │  handler: ...   │
        │  paired: endB ────────────────── paired: endA    │
        │  acceptsMsgs    │              │  acceptsMsgs    │
        │  trustedKey     │              │  trustedKey     │
        └─────────────────┘              └─────────────────┘

A calls endA.sendMessage(msg):
  1. endA checks endB.acceptsMessages — if false, returns false
  2. endA delivers msg.withConnection(endB) to endB.handler
  3. B's handler processes msg
  4. B calls msg.returnMessage(result)
  5. msg.connection is endB → endB.returnMessage(result)
  6. returnMessage bypasses acceptsMessages check
  7. endB delivers result.withConnection(endA) to endA.handler
  8. A's handler receives the result
```

Both sides use the same mechanism. Neither is privileged. The handlers on each
end determine what happens when a message arrives — that is where the
application-specific behaviour lives.

### 3.3 Factories

```java
// Bidirectional pair — both ends accept general messages
LocalConnection.createPair(handlerA, handlerB)

// Return-only pair — endA receives results only, not general messages
LocalConnection.createReturnable(handlerA, handlerB)

// Simple return-only — endA has null handler, endB has given handler
LocalConnection.create(handler)
```

`createPair` returns endA; call `endA.getPaired()` to get endB. Both ends
have `acceptsMessages = true`.

`createReturnable` returns endA with `acceptsMessages = false`. endB has
`acceptsMessages = true`. Both ends have handlers. `sendMessage` from endB
to endA returns false, but `returnMessage` from endB to endA works.

`create` is a convenience for the simple case where the client end needs no
handler (null) and no general messaging.

### 3.4 Return-Only Pairs

When an end has `acceptsMessages = false`, the paired end's `sendMessage`
returns false for that direction. `returnMessage` is unaffected — results
always flow through. This structurally prevents server-initiated protocol
exchange (e.g. challenge/response verification) on return-only connections
while preserving result delivery.

`supportsMessage()` on the sending end returns false when the paired end
does not accept messages. Callers should check this before attempting
protocol exchange to avoid unnecessary work (e.g. spawning virtual threads).

### 3.5 Network Connections

Network connections (Netty, NIO) fit the same model implicitly. The socket is
the shared channel. Each side has its own connection object. `sendMessage` and
`returnMessage` both write to the socket. The receiving end's connection is set
by the network layer when a message arrives. `supportsMessage()` returns true
(network sockets are always bidirectional).

No changes to the network connection model are needed — it already works this
way. The paired local connection model makes the in-memory case consistent with
it.

## 4. Client Architecture

### 4.1 Class Hierarchy

```
Convex (abstract — API contract, shared state)
├── AConvexConnected (abstract — awaiting map, result dispatch, connection)
│   ├── ConvexRemote (network socket via Netty/NIO)
│   └── ConvexLocal (paired LocalConnection to local Server)
└── ConvexDirect (direct peer calls, no messaging)
```

### 4.2 `AConvexConnected` — Shared Dispatch

`AConvexConnected` provides connection-oriented dispatch shared by all clients
that communicate via an `AConnection`:

- **Connection field** — `protected AConnection connection`, managed by
  `setConnection()` / `close()`
- **Awaiting map** — `ConcurrentHashMap<ACell, CompletableFuture<Message>>`
  keyed by request ID. Registers a future before sending, completes it when the
  result arrives.
- **Result correlation** — incoming result messages are matched by ID to
  awaiting futures via `returnMessageHandler`.
- **Protocol handling** — incoming protocol messages (e.g. server-initiated
  CHALLENGE) are handled automatically (auto-response with the client's
  keypair).
- **`message(Message)`** — core dispatch implementing the register-before-send
  pattern. Fire-and-forget messages (no request ID) are sent directly and return
  `Result.SENT_MESSAGE`.

### 4.3 `ConvexRemote` — Network Client

Connected via a network `AConnection` (Netty or NIO). The network receive
handler feeds incoming messages into the shared dispatch. Extends
`AConvexConnected`, adding only network-specific concerns:

- `remoteAddress` and `connectToPeer()`
- Static factories: `connect()`, `connectNetty()`, `connectNIO()`
- `reconnect()` — close and re-establish network connection

### 4.4 `ConvexLocal` — In-Memory Client

Connected to a `Server` in the same JVM via a persistent `LocalConnection`
pair. Extends `AConvexConnected`, using the same dispatch logic as
`ConvexRemote`.

The pair is created at construction time. The mode depends on keypair presence:

**Connected mode** (keypair present — peer-to-peer, agent-to-agent):
- `createPair(clientHandler, serverHandler)` — bidirectional
- Both ends support `sendMessage` — full protocol exchange
- Trust can accumulate; challenge/response works identically to network

**Return-only mode** (no keypair — shared gateway):
- `createReturnable(clientHandler, serverHandler)` — client end return-only
- Server end cannot `sendMessage` to client (`supportsMessage()` = false)
- `returnMessage` works — results flow back through the awaiting map
- No trust accumulation; `InboundVerifier` skips these connections

### 4.5 `ConvexDirect` — Fast Path

No connection, no messages. Calls `Peer` methods directly (`executeQuery`,
`proposeBlock`). Same `Convex` API semantics, avoids message construction and
thread handoff. For local testing, forked simulations, and embedded scenarios
where messaging overhead is not wanted.

### 4.6 REST API Integration

`ChainAPI` (HTTP endpoints) uses the shared `ConvexLocal` instance provided by
`RESTServer`. It calls `convex.message(message)` or `convex.messageRaw(blob)`
and gets a `Result` future back. `ChainAPI` has no connection awareness and no
direct `Server` access — all message plumbing is handled by `ConvexLocal` in
return-only mode.

```
HTTP request → ChainAPI
  → convex.message(msg)           (shared ConvexLocal, no keypair)
  → persistent pair, return-only
  → server.deliverMessage(msg)    (msg carries connection for result routing)
  → server processes, returns result via returnMessage
  → awaiting map completes future
  → ChainAPI writes HTTP response
```

JSON content type is rejected at the message endpoint (400 Bad Request).
Fire-and-forget messages (no request ID) return HTTP 202 Accepted.

## 5. Server Perspective

The server sees `AConnection` on incoming messages and uses capabilities
uniformly. It does not know or care about the transport — a local paired
connection, a Netty socket, and a future WebSocket connection all look the same.

### 5.1 Result Delivery

```java
msg.returnMessage(result)
  → msg.connection.returnMessage(result)
  → delivered to the other end
```

Works on any connection that has a return path. The server processing thread
is never blocked (returnMessage is non-blocking).

### 5.2 Server-Initiated Messaging

```java
if (conn.supportsMessage()) {
    conn.sendMessage(challenge)
}
```

Used for protocol exchange (challenge/response verification), data push, and
any server-initiated communication. Only works on bidirectional connections.
`supportsMessage()` returns false on return-only connections — check before
attempting.

### 5.3 Trust Checks

```java
AConnection conn = msg.getConnection();
if (conn != null && conn.isTrusted()) {
    // trusted path — e.g. priority belief queue
} else {
    // untrusted path — e.g. best-effort queue, trigger verification
}
```

Messages on return-only connections are always untrusted (`supportsMessage()`
returns false so verification cannot be initiated). Persistent bidirectional
connections can accumulate trust via verification.

### 5.4 Verification

`InboundVerifier` triggers server-initiated verification when an untrusted
connection sends a message that would benefit from trust (e.g. a belief):

1. Check `conn.supportsMessage()` — if false, skip verification entirely
2. `conn.sendMessage(challenge)` — send CHALLENGE on the connection
3. The other end's handler receives the CHALLENGE and auto-responds
4. The response routes back through the pair to the server
5. Verification succeeds → `conn.setTrustedKey(remoteKey)`
6. Subsequent messages on this connection are trusted

See [PEER_PRIORITY.md](PEER_PRIORITY.md) for the full verification flow and
belief priority model.

Application-specific policy (e.g. "is this key a registered peer in consensus
state") is separate from the connection-layer verification. The connection
layer establishes cryptographic identity. The application decides what that
identity means.

## 6. Summary — Connection Type Matrix

| Actor | Connection type | Mode | Trust | Verification |
|---|---|---|---|---|
| Network peer (`ConvexRemote`) | Network socket | bidirectional | yes | client or server initiated |
| In-memory peer/agent (`ConvexLocal` + keypair) | Local pair (persistent) | bidirectional | yes | client or server initiated |
| Shared REST gateway (`ConvexLocal`, no keypair) | Local pair (persistent) | return-only | no | not possible |
| HTTP endpoint (`ChainAPI`) | Via shared `ConvexLocal` | return-only | no | not possible |
| Direct fast path (`ConvexDirect`) | None | direct calls | n/a | n/a |
