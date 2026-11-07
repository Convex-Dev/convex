# Peer Trust and Belief Intake

**See also:** [BACKPRESSURE.md](BACKPRESSURE.md) — client request backpressure;
[PROPAGATION.md](PROPAGATION.md) — Belief delta fan-out and queue bounds

## Principle

A Belief is judged by what it carries, not by who sent it. A Belief is a set of signed
Orders, and each Order counts only if it is signed by a peer with at least the minimum
effective stake, is newer than the Order already held from that peer, and is not
timestamped in the future ([CAD051](https://docs.convex.world/docs/cad/cpos)). Peers relay
each other's Orders, so knowing which connection a Belief arrived on would vouch for
nothing it contains.

A consensus peer therefore verifies only the connections it opens. It never challenges a
connection opened to it ([CAD015](https://docs.convex.world/docs/cad/peercomms) Trust).
Besides results, it sends on that connection only a request for data missing from an
update that arrived on it ([PROPAGATION.md](PROPAGATION.md)). What comes back is
content-addressed and judged like any other data.

## Liveness

> Traffic from any connection must never stop Belief merges between peers.

Peers push Beliefs only on connections they opened, so every pushed Belief arrives on an
inbound connection that anyone could have opened. Three things keep junk from stopping
consensus:

1. **Polling.** `ConnectionManager` polls one of the peers it chose by stake and verified
   every poll interval, and acquires that peer's Belief into a queue of its own. Polling
   waits only for room in that queue (`BeliefPropagator.hasBeliefPollCapacity`), so pushes
   filling the propagation queue cannot stop it.
2. **Cheap rejection.** `BeliefPropagator` drops an Order from a key that is not a staked
   peer before checking its signature or storing it, and an Order no newer than the one
   it holds before checking its signature. A bad signature ends processing of the message
   carrying it. The exception is an update with data missing that this peer fetches: its
   cells, and whatever the fetch brings, are stored before any Order in it is judged, as
   pushed DATA is. With one fetch at a time, that is one update at a time.
3. **A bounded queue.** Pushed DATA and Beliefs from every connection share one ordered
   queue, bounded by count and bytes. When it is full, further pushes are dropped; the
   connection is never paused for them.

While junk fills the queue, pushes from real peers can be dropped with it, and consensus
proceeds at the polling rate. A sender that never answers can likewise hold a peer's one
fetch of missing data for up to the poll delay at a time, and with it the same polling
rate at worst.

## Inbound Connections

| Message | Handling |
|---------|----------|
| BELIEF | Joins the propagation queue, whoever sent it |
| DATA | Joins the propagation queue, whoever sent it. Its cells are staged in the store; they count only once a valid Belief refers to them |
| CHALLENGE | Answered with this peer's key, so whoever opened the connection can verify it |
| RESULT | Completes this peer's request on that connection, a fetch of missing data; any other is ignored (CAD015) |

Accepting DATA from any connection is under review: a peer could instead accept it only
from a sender it has verified separately ([#826](https://github.com/Convex-Dev/convex/issues/826)).

Data missing from a received Belief or Order is requested from the connection it arrived
on, one fetch at a time ([PROPAGATION.md](PROPAGATION.md)). A peer's own connections to
other peers answer such requests from its store.

Lattice nodes do verify connections opened to them; see
[LATTICE_NETWORKING.md](LATTICE_NETWORKING.md).

## Outbound Connections: Challenge/Response

### Components

| Component | Role |
|-----------|------|
| `AConnection.isTrusted()` / `setTrustedKey()` | Set by `ConvexRemote.setVerifiedPeer()` |
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

### Connection Flow

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

A peer's outbound connection still answers a CHALLENGE sent back on it, as any client with
a key pair does, so released peers that challenge inbound connections can verify it.

## Generic Message API

`POST /api/v1/message` accepts messages in CAD3 raw (`application/cvx-raw`) or CVX text
(`application/cvx`) format. Delivers to the peer server and returns the Result, honouring
the `Accept` header for response format (JSON, CVX, CVX raw). This enables challenge/response
over HTTP — `ConvexHTTP.sendChallenge()` uses this endpoint.

Response size is capped at 1MB via `Cells.storageSize()` in `setContent()`.

## Error Responses

`Server.processMessage()` returns an error for an unknown message type:
`Result.error(:FORMAT, "Unrecognised message type")`. Error strings are static constants in
`Strings` — no per-message string construction.

## Message ↔ Connection

See [MESSAGING.md](MESSAGING.md) for the full connection and message architecture.

`Message` carries a single `connection` field. `returnMessage()` delegates to
`connection.returnMessage()`. `LocalConnection` is a paired bidirectional
channel with per-end `acceptsMessages` control — `sendMessage` can be
structurally disabled while `returnMessage` continues to work.

`AConnection.supportsMessage()` allows a server to check whether a connection
supports general messaging before attempting protocol exchange, as a lattice node does
before verifying an inbound connection.
