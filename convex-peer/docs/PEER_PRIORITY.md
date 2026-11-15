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

DATA is different: it is cells pushed ahead of a Belief, stored before anything refers to
them. A consensus peer stores DATA only from a connection it trusts: one whose far end has
proved it is a staked peer's own link to this peer
([Verifying an Inbound Connection](#verifying-an-inbound-connection)). DATA from any other
connection is ignored, and an update it preceded completes through the missing-data fetch
([PROPAGATION.md](PROPAGATION.md)).

Besides results, a peer sends on a connection opened to it only a challenge, when the
connection is untrusted and sends a Belief or DATA, and a request for data missing from an
update that arrived on it. What a fetch brings back is content-addressed and judged like
any other data.

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
3. **A bounded queue.** Pushed Beliefs from every connection, and DATA from trusted
   connections, share one ordered queue, bounded by count and bytes. When it is full,
   further pushes are dropped; the connection is never paused for them.

While junk fills the queue, pushes from real peers can be dropped with it, and consensus
proceeds at the polling rate. A sender that never answers can likewise hold a peer's one
fetch of missing data for up to the poll delay at a time, and with it the same polling
rate at worst.

## Inbound Connections

| Message | Handling |
|---------|----------|
| BELIEF | Joins the propagation queue, whoever sent it. From an untrusted connection, it also starts verification of that connection |
| DATA | From a trusted connection, joins the propagation queue, and its cells are stored if the connection's key still has the minimum effective stake; they count only once a valid Belief refers to them. From any other connection, ignored, and starts verification |
| CHALLENGE | Answered with this peer's key, so whoever opened the connection can verify it |
| RESULT | Completes this peer's request on that connection, a challenge or a fetch of missing data; any other is ignored (CAD015) |

Data missing from a received Belief or Order is requested from the connection it arrived
on, one fetch at a time ([PROPAGATION.md](PROPAGATION.md)). A peer's own connections to
other peers answer such requests from its store.

### Verifying an Inbound Connection

`InboundVerifier` challenges an untrusted connection when it sends a Belief or DATA:

1. It sends a CHALLENGE `[token, nil, networkID]`, signed by this peer.
2. The far end must give the bound answer `[token, thisPeer, networkID, thisPeer]`, signed
   by a key with at least the minimum effective stake. The last element is the peer the
   answering connection was opened to reach. Only a peer's own outbound link gives this
   form ([Outbound Connections](#outbound-connections-challengeresponse)); a server's
   answer has three elements and is not accepted.
3. On success, the connection's trusted key is set. DATA from it is then stored, and it
   gets the trusted treatment for replies ([BACKPRESSURE.md](BACKPRESSURE.md#reply-backlog)).

On a network thread the verifier only checks whether to start: whether the connection is
trusted, has an attempt in flight, or is backing off. Signing, sending, waiting up to 5 s
for the answer and checking it run on a virtual thread.

A failed attempt delays the next one for that connection by the poll delay, doubling with
each further failure up to 2⁸ poll delays. The verifier forgets a connection when it is
trusted or closes. Closes are reported by the network listeners, and swept up when an
attempt starts for transports that do not report them.

The first update a peer sends on a new link may arrive before the link is trusted. Its
DATA is then ignored, and that update completes through the fetch.

Lattice nodes verify connections opened to them in their own way; see
[LATTICE_NETWORKING.md](LATTICE_NETWORKING.md).

## Outbound Connections: Challenge/Response

### Components

| Component | Role |
|-----------|------|
| `AConnection.isTrusted()` / `setTrustedKey()` | Set by `ConvexRemote.setVerifiedPeer()` |
| `Message.respondToChallenge()` | Shared handler — used by both peer Server and NodeServer |
| `Convex.verifyPeer()` | Client-side API — returns `CompletableFuture<AccountKey>` |
| `Convex.verifiedPeer` field | Set on success, cleared on close/reconnect |
| `Convex.setDialledPeer()` | The peer a client was opened to reach. While set, the client answers a challenge in the bound form naming it |
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

A client with a dialled peer answers in the bound form instead,
`SignedData([token, challengerKey, contextID, dialledKey])`, always four elements, with a
nil context if the challenge had none (`Message.answerBoundChallenge`). A consensus peer
accepts only this form when it verifies a connection opened to it
([Verifying an Inbound Connection](#verifying-an-inbound-connection)).

### Connection Flow

**Peer `ConnectionManager.connectToPeer()`** (async `CompletableFuture<Convex>`):
1. Connect to remote address, and record the peer key it set out to reach, if any, as the
   dialled peer
2. Try `verifyPeer(null, networkID)` — discovery mode with network context (over TLS, the
   expected key)
3. If verified → `verifiedPeer` set, `AConnection.trustedKey` set, peer key proven
4. If verification fails → fall back to `requestStatus()` (untrusted, self-reported key)
5. With no key chosen before dialling, record the verified key, if any, as the dialled peer
6. Add connection keyed by peer identity

Peers send Beliefs only on connections added in step 6, so the dialled peer is recorded
before any challenge they bring.

**Lattice `LatticeConnectionManager`**:
1. Connect to remote address
2. If key pair available, fire async `verifyPeer(peerKey)` — non-blocking
3. Connection immediately usable; `verifiedPeer` set when verification completes

A peer's outbound connection answers a CHALLENGE sent back on it in the bound form, naming
its dialled peer, so that peer can verify the link. Other clients with a key pair answer in
the plain form.

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
