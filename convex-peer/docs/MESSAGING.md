# Messaging and Connection Architecture

**Target design** for the universal messaging model used across all Convex and
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
with another participant. It provides two optional communication capabilities:

| Method | Semantics | Blocking | Used for |
|---|---|---|---|
| `returnMessage(Message)` | Deliver a result to the other end | Non-blocking | Result delivery from processing threads |
| `sendMessage(Message)` | Push an arbitrary message to the other end | May block (bounded) | General messaging, protocol exchange |

Both methods are optional. A connection end may support one, both, or neither.

Additional state and lifecycle (as today):
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
| Local bidirectional pair | yes (to peer) | yes (to peer) | yes | persistent |
| Local return-only pair | yes (to peer) | no (null handler) | no | per-request |

The distinction between "bidirectional" and "return-only" is a configuration
choice (which handlers are present), not a type distinction.

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
        │  peer: endB ────────────────────── peer: endA    │
        │  trustedKey     │              │  trustedKey     │
        └─────────────────┘              └─────────────────┘

A calls endA.sendMessage(msg):
  1. endA delivers msg.withConnection(endB) to endB.handler
  2. B's handler processes msg
  3. B calls msg.returnMessage(result)
  4. msg.connection is endB → endB.returnMessage(result)
  5. endB delivers result.withConnection(endA) to endA.handler
  6. A's handler receives the result
```

Both sides use the same mechanism. Neither is privileged. The handlers on each
end determine what happens when a message arrives — that is where the
application-specific behaviour lives.

### 3.3 Factory

```java
LocalConnection.createPair(handlerA, handlerB) → (endA, endB)
```

Either handler may be null. A null handler means that end does not support
`sendMessage` from the peer — `sendMessage` returns false. `returnMessage`
always works if the peer end exists (it is the minimum viable capability for
any connection).

### 3.4 Return-Only Pairs

When one handler is null, the pair is return-only in one direction. The end
with the null handler cannot receive general messages — only the result path
works. This is the correct configuration for connectionless interactions (HTTP
requests, shared gateway clients) where server-initiated messaging is not
needed.

`sendMessage` returning false naturally prevents server-initiated protocol
exchange (e.g. challenge/response verification) on that connection. No
capability query method is needed — the standard send-and-check-result pattern
works.

### 3.5 Network Connections

Network connections (Netty, NIO) fit the same model implicitly. The socket is
the shared channel. Each side has its own connection object. `sendMessage` and
`returnMessage` both write to the socket. The receiving end's connection is set
by the network layer when a message arrives.

No changes to the network connection model are needed — it already works this
way. The paired local connection model makes the in-memory case consistent with
it.

## 4. Client Architecture

### 4.1 Convex Base Class

The `Convex` abstract class provides the client API for communicating with a
peer or server. It owns the **client-side message dispatch** logic:

- **Awaiting map** — `ConcurrentHashMap<ACell, CompletableFuture<Message>>`
  keyed by request ID. Registers a future before sending, completes it when the
  result arrives.
- **Result correlation** — incoming result messages are matched by ID to
  awaiting futures.
- **Protocol handling** — incoming protocol messages (e.g. server-initiated
  CHALLENGE) are handled automatically (auto-response with the client's
  keypair).

This dispatch logic is currently in `ConvexRemote` only, but it is the same for
any connected client regardless of transport. It belongs in the base class (or
a shared helper).

### 4.2 ConvexRemote — Network Client

Connected via a network `AConnection` (Netty or NIO). The network receive
handler feeds incoming messages into the base class dispatch. Works as today,
with dispatch logic shared rather than duplicated.

### 4.3 ConvexLocal — In-Memory Client

Connected to a `Server` in the same JVM via a `LocalConnection` pair.

**Connected mode** (persistent relationship — peer-to-peer, agent-to-agent):
- One pair created at construction time
- One end's handler = base class dispatch (awaiting map + protocol handling)
- Other end's handler = `server.deliverMessage`
- Both ends support `sendMessage` — full bidirectional messaging
- Trust can accumulate; verification works identically to network

**Connectionless mode** (shared gateway, per-request interactions):
- New pair created per request
- One end's handler = simple callback that completes the specific future
- Other end's handler = `server.deliverMessage`
- One end has null handler — `sendMessage` returns false, no server-initiated
  messaging
- No trust accumulation

The mode is a usage pattern, not a class distinction. The same `ConvexLocal`
class, the same `LocalConnection` class, different pair configurations.

**Choosing the mode:** when `ConvexLocal` has a keypair (identity), it uses
connected mode with a persistent pair. Without a keypair, it uses connectionless
mode with per-request pairs.

### 4.4 ConvexDirect — Fast Path

No connection, no messages. Calls `Peer` methods directly (`executeQuery`,
`proposeBlock`). Same `Convex` API semantics, avoids message construction and
thread handoff. For local testing, forked simulations, and embedded scenarios
where messaging overhead is not wanted.

### 4.5 REST API Integration

`ChainAPI` (HTTP endpoints) uses the shared `ConvexLocal` instance provided by
`RESTServer`. It calls `convex.message(message)` and gets a `Result` future
back. `ChainAPI` has no connection awareness and no direct `Server` access —
all message plumbing is handled by `ConvexLocal` in connectionless mode.

```
HTTP request → ChainAPI
  → convex.message(msg)           (shared ConvexLocal, no keypair)
  → per-request pair created
  → server.deliverMessage(msg)    (msg carries connection for result routing)
  → server processes, returns result via connection
  → future completes
  → ChainAPI writes HTTP response
```

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
conn.sendMessage(challenge)
  → delivered to the other end's handler
  → returns false if not supported (null handler / return-only pair)
```

Used for protocol exchange (challenge/response verification), data push, and
any server-initiated communication. Only works on bidirectional connections.
Returns false on return-only connections — no special capability check needed.

### 5.3 Trust Checks

```java
AConnection conn = msg.getConnection();
if (conn != null && conn.isTrusted()) {
    // trusted path — e.g. priority belief queue
} else {
    // untrusted path — e.g. best-effort queue, no verification
}
```

Messages with no connection (`null`) or return-only connections are always
untrusted. Persistent bidirectional connections can accumulate trust via
verification.

### 5.4 Verification

`InboundVerifier` triggers server-initiated verification when an untrusted
connection sends a message that would benefit from trust (e.g. a belief):

1. `conn.sendMessage(challenge)` — if this returns false, the connection
   doesn't support bidirectional messaging; verification is not attempted
2. The other end's handler receives the CHALLENGE and auto-responds
3. The response routes back through the pair to the server
4. Verification succeeds → `conn.setTrustedKey(remoteKey)`
5. Subsequent messages on this connection are trusted

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
| Shared REST gateway (`ConvexLocal`, no keypair) | Local pair (per-request) | return-only | no | not possible |
| HTTP endpoint (`ChainAPI`) | Via shared `ConvexLocal` | return-only | no | not possible |
| Direct fast path (`ConvexDirect`) | None | direct calls | n/a | n/a |

## 7. Migration from Current State

The current implementation has:
- `Message` with two fields: `connection` (AConnection) and `returnHandler`
  (Predicate\<Message\>), with connection-first priority in `returnMessage()`
- `LocalConnection` as a one-directional wrapper around a handler predicate
- `ConvexLocal.makeMessageFuture()` creating throwaway `LocalConnection`
  instances per request
- `ChainAPI.handleMessage` using `withResultHandler` to bypass `ConvexLocal`
- Client dispatch logic (awaiting map, protocol handling) in `ConvexRemote` only

### Target changes

| Component | Change |
|---|---|
| **AConnection** | Add `returnMessage(Message)` as distinct from `sendMessage`. |
| **LocalConnection** | Paired channel model. Factory `createPair(handlerA, handlerB)`. Mutual peer references. Send stamps peer's connection on delivered message. Either handler nullable (return-only). |
| **Message** | Remove `returnHandler` field and `withResultHandler()`. `returnMessage()` delegates to `connection.returnMessage()`. Single `connection` field. |
| **Convex base class** | Pull dispatch logic (awaiting map, result correlation, protocol handling) up from `ConvexRemote`. |
| **ConvexRemote** | Simplifies — dispatch logic moves to base class. |
| **ConvexLocal** | Connected mode (persistent pair + base class dispatch) or connectionless mode (per-request pair + direct callback). Determined by keypair presence. |
| **ChainAPI** | Uses shared `ConvexLocal.message()` instead of direct `server.deliverMessage()`. No connection awareness. |
| **InboundVerifier** | No structural change. `sendMessage` returning false naturally prevents verification on return-only connections. Application-specific trust policy (e.g. peer registration check) separated from generic identity verification. |
