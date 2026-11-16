# Peer ingress decode and routing

- Status: Accepted
- Tracking issue: [#608](https://github.com/Convex-Dev/convex/issues/608)
- Last updated: 2026-11-16

## Summary

Peer network input should cross one asynchronous ingress boundary that owns a
message from completed framing until the message has been accepted by its final
destination queue.

That boundary should:

1. admit raw messages under per-connection and global count/byte limits;
2. schedule connections fairly, with a priority lane and reserved capacity for
   verified Peers;
3. decode the message once, away from network I/O threads;
4. classify it and perform all initial routing;
5. retain backpressure until the selected destination accepts the message.

This is not intended to add a generic middleware layer. It replaces the current
decode, observation, routing and retry paths with one component, `Ingress` in
`convex.net.ingress`. Existing transaction, query and Belief workers remain
responsible for their domain work.

## Context

### Accepted connections

`NettyInboundHandler` currently validates the frame length, copies the encoded
body into a `Message`, and calls its delivery function synchronously. For a
Peer `Server`, this reaches `Server.deliverMessage`, which calls
`Message.getPayload(store)` before `Server.processMessage` selects the
transaction, query, Belief or protocol path.

The full CAD3 multi-cell decode therefore runs on a Netty event-loop thread.
It may traverse adversarial structure and resolve partial cells through the
Peer store. Existing bounded transaction and query queues provide backpressure
only after this work has completed.

The Netty worker group is deliberately small because application work is
expected to run elsewhere. Blocking one of those workers affects every channel
assigned to the same event loop. A small number of expensive decodes can
therefore delay unrelated Peer traffic and connection lifecycle events.

### Outbound connections

The same concern exists on locally initiated `ConvexRemote` connections.
`NettyConnection` wraps the receive callback as a `Consumer` which always
reports successful delivery. `AConvexConnected.returnMessageHandler` may
complete a result future from that callback; its synchronous continuation then
decodes the result. This also leaves full decode work on the Netty event loop.

An outbound socket is not trusted merely because the local Peer opened it.
`Convex.verifyPeer` establishes trust through challenge/response and then sets
the verified key on the underlying `AConnection`.

### Existing destinations

Once decoded, messages already have useful domain boundaries:

| Message kind | Destination |
| --- | --- |
| `TRANSACT` | `TransactionHandler` |
| `QUERY`, `DATA_REQUEST` | `QueryHandler` |
| `BELIEF`, and `DATA` from a verified connection | the one `BeliefPropagator` propagation queue, which drops a push when full ([PEER_PRIORITY.md](../../convex-peer/docs/PEER_PRIORITY.md#liveness)) |
| `DATA` from any other connection | ignored, and starts verification of that connection |
| `PING`, `STATUS`, `CHALLENGE`, `GOODBYE` | bounded control handler |
| `RESULT` on an accepted connection | completes this Peer's own request on it (a verification challenge or a fetch of missing data); any other is ignored |
| outbound client `RESULT`, reverse `CHALLENGE`, reverse `DATA_REQUEST` | outbound connection/client handler |

These destinations should remain. The new ingress component ends when one of
them has accepted the message.

## Goals

- No payload or store-backed cell decode on a Netty event-loop thread.
- One decode and routing stage, not a stack of intermediate queues.
- Bounded memory use by message count and encoded bytes.
- Fair service between active connections.
- Prefer verified Peer traffic without starving untrusted verification and
  client traffic.
- Preserve FIFO ordering within each connection.
- Throttle fast senders through per-channel TCP backpressure.
- Carry downstream queue pressure back to the originating connection.
- Cover both accepted sockets and locally initiated outbound sockets.
- Keep already-decoded local/in-process messages on a low-latency path through
  the same routing logic.
- Preserve the current wire protocol and CVM semantics.

## Non-goals

- Changing message framing, CAD3 encoding or protocol-version semantics.
- Replacing transaction batching, query execution or Belief merging.
- Providing fairness between transactions after they enter a domain handler.
- Solving the separate `NodeServer` lattice-ingress design tracked by #605.
- Treating status-based Peer identification as cryptographic trust.

## Design invariants

1. **The transport is thin.** It frames, copies or wraps bytes, and attempts a
   non-blocking admission. It does not decode or route.
2. **Ingress owns accepted work.** A connection's message and byte credit is
   retained until the final destination accepts the message or the message is
   rejected.
3. **Decode and route are one worker turn.** There is no global decoded-message
   queue and no second executor submission between decode and routing.
4. **Workers never wait for a full destination.** A blocked message remains at
   its connection head and is retried after a real destination-capacity signal.
   A destination may instead drop, as the propagation queue does; it never
   pauses the connection.
5. **Trust is verified.** Only a connection whose remote Peer has proved its key
   receives the verified-Peer service class: on an accepted connection, a staked
   Peer's own link verified as [PEER_PRIORITY.md](../../convex-peer/docs/PEER_PRIORITY.md#verifying-an-inbound-connection)
   describes; on an outbound connection, a Peer verified by `Convex.verifyPeer`.
   Each ingress is given the rule for its own connections; the ingress itself
   holds no trust policy. This is the priority
   [CAD015](https://docs.convex.world/docs/cad/peercomms) asks for messages from
   trusted sources.
6. **Priority is non-starving.** Verified traffic has more service and reserved
   decode capacity, but untrusted traffic continues to make bounded progress.
7. **Memory is bounded globally and locally.** A fast connection can consume
   only its own allowance.

## Proposed architecture

```text
                     already-decoded local message
                                  |
                                  v
socket -> frame -> raw admission -> decode -> classify -> destination.tryOffer
                       ^              |             |
                       |              |             +-> TransactionHandler
             fairness / trust         |             +-> QueryHandler
                 scheduler            |             +-> BeliefPropagator
                                      |             +-> ControlHandler
                                      |             +-> OutboundClientHandler
                                      |
                              same ingress worker
```

There is one `Ingress` per Peer `Server`, serving its accepted connections, and
one shared by the process's outbound client connections, so a flood on accepted
channels cannot delay the replies a Peer's own outbound links receive.

### Per-connection ingress state

Each network connection has a small state object containing:

- the `AConnection`;
- its accepted-message route profile (Peer server or outbound client);
- a FIFO of raw or decoded-but-not-yet-admitted messages;
- pending encoded-byte and message counts;
- active, paused and disconnected state;
- scheduler lane and deficit;
- the destination currently blocking its head message, if any.

A connection appears at most once in the scheduler's active set. Further
messages append to its private FIFO rather than adding duplicate global work
items.

The per-connection FIFO should be intentionally small. Its purpose is to
decouple a socket read from one decode turn, not to buffer an arbitrary burst.
When either its count or byte high-water mark is reached, the transport pauses
that channel.

### Raw admission

The network adapter submits the same `Message` object created from the framed
bytes. Admission:

1. rejects a disconnected or shutting-down connection;
2. reserves per-connection and global encoded-byte credit;
3. appends the message to the connection FIFO;
4. activates the connection if it was previously idle;
5. returns immediately, saying whether the transport may keep reading.

The transport has already allocated the framed message, so admission always
takes it, and asks the transport to pause once the connection or the global
total reaches its high-water mark. A connection therefore overshoots by at most
one frame. Netty pauses by turning `autoRead` off, signalled only together with
an emitted frame so that `ByteToMessageDecoder` requests no further read. Once
credit falls below the low-water mark, resumption is scheduled on the channel's
event loop, and bytes already held in the decoder's cumulation are made
eligible for decoding.

Netty receive allocators are not capped. Once the event loop only frames
messages, event-loop responsiveness measurements show whether a cap on bytes or
read attempts per socket read loop is needed as defence in depth.

### Fair scheduler

The scheduler selects active connections rather than selecting individual
messages from one global FIFO.

The model is weighted surplus round-robin:

- encoded length is the principal service cost;
- every message has a minimum cost, preventing floods of tiny frames from being
  effectively free;
- a connection's head message is served whenever its credit is positive, and
  the connection repays any overrun in later rounds before it is served again;
- a connection returns to the end of its lane when its credit is spent or its
  FIFO is empty;
- FIFO ordering is preserved within the connection.

Serving the head whenever credit is positive keeps a large message from waiting
many rounds: under classic deficit round-robin, a 4 MB update would wait about
64 rounds of a 64 KB quantum before it could be decoded.

A connection whose message reaches its head waits at most one turn of every
other active connection. This, not the priority lane, is what stops a client
burst delaying Peer updates: they are served between client messages instead of
after the whole burst.

Two lanes are required:

- **verified Peer**: the connection's remote Peer has proved its key (design
  invariant 5);
- **ordinary**: all other network connections, including outbound sockets
  before verification.

A connection's lane is read each time it rejoins the scheduler, so a connection
verified while active moves at its next turn. Verified Peers receive a larger
quantum. Strict priority is not acceptable: ordinary challenge and status
traffic must make progress so a legitimate connection can become verified.

Queue weighting alone cannot guarantee verified-Peer latency because decode is
not pre-emptible. With `N` concurrent decode permits, ordinary work may occupy
at most `N - 1`. This leaves capacity for already-verified Peer traffic during
sustained hostile input, such as a flood of maximum-size frames. One decoder
for ordinary work reproduces the measured stall, so `N` is at least three.

Defaults for worker count, quantum and queue limits are documented constants
in `Config`, chosen from the before and after measurements.

### Decode and classification

An ingress worker takes the head message for its selected connection and calls
`Message.getPayload(store)` exactly once.

Raw messages cannot be sent directly to the existing transaction or query
queues. Beliefs and results can often be recognised from a top-level encoding
tag, but vector messages such as `TRANSACT`, `QUERY` and `DATA_REQUEST` remain
`UNKNOWN` until their first element has been decoded. A shallow raw classifier
would duplicate and couple routing to CAD3 decoding details.

After a successful decode, the same worker:

1. invokes the receive observer;
2. classifies the message;
3. selects its final destination;
4. attempts a non-blocking destination offer.

There is no decoded-message scheduler between steps 2 and 4.

### Destination admission

Each destination exposes a non-blocking offer and a capacity signal:

```java
boolean tryOffer(Message message);
void whenCapacity(Runnable action);
```

The signal must be race-safe and driven by an actual dequeue/capacity change;
workers must not poll or sleep. After a signal the worker offers once more, so
a dequeue between a failed offer and the registration is not lost.

A destination is also a `Predicate<Message>` whose test waits a bounded time,
for callers that may wait, such as the local fast path. The delivery contract
stays `Function<Message,Predicate<Message>>`: a route returns null when the
message has left ingress, or the destination that refused it. Deliveries
outside the Peer, such as lattice clients, may still return a plain predicate;
the ingress runs it as an opaque retry on a virtual thread while that
connection stays blocked, with `:LOAD` for a request it refuses, as today.

A blocked head that is still refused at the internal timeout (8 s, as today) is
answered with `:LOAD` if it is a request and dropped otherwise.

If the destination accepts the message:

- remove it from the connection FIFO;
- release its count and byte credit;
- resume the channel if it is below its low-water mark;
- reactivate the connection if more messages remain.

If the destination is full:

- keep the now-decoded message at the connection head;
- retain its byte and message credit;
- mark the connection blocked on that destination;
- let the worker serve another connection;
- reactivate it when the destination signals capacity.

This propagates transaction, query, Belief and control congestion back to the
originating socket without blocking an ingress worker.

### Control messages

Protocol/control messages should not be handled on the event loop or inline in
the decode scheduler. A small bounded control handler, `ControlHandler`,
receives through one queue:

- `PING` and `STATUS`;
- `CHALLENGE`;
- `GOODBYE`;
- any future cheap protocol-control messages.

A `RESULT` that completes this Peer's own request, such as the answer to a
verification challenge, completes its future on the ingress worker; the
request's owner does its work in its own thread, as `InboundVerifier` and
`Acquiror` do.

This keeps ingress work limited to decode, classification and admission.
Per-connection limits prevent an untrusted control-message flood from claiming
unbounded control capacity, and capacity signals wake blocked connections in
scheduler order, so one queue retains service for challenge/response.

### Outbound connection routing

Locally initiated `ConvexRemote` connections use the same raw admission and
fair scheduler.

Their route profile differs from an accepted Peer-server channel:

- a `RESULT` completes its awaiting future on the ingress worker;
- a reverse `CHALLENGE` is answered by the route on the worker;
- an enabled reverse `DATA_REQUEST` reaches its configured handler;
- unexpected messages are rejected or logged according to current semantics.

Future completion runs on an ingress worker, so synchronous future
continuations, including the result decode, cannot move decode back onto the
Netty event loop. A continuation that sends must not park a worker:
`NettyConnection.sendMessage` waits for queue space only off event loops and
ingress workers.

After `verifyPeer` succeeds, `setTrustedKey` promotes the connection to the
verified-Peer scheduler lane. Status-based fallback identification does not.
The key is held in a `volatile` field, and the scheduler reads it each time the
connection rejoins its lane.

### Local decoded fast path

`ConvexLocal` and other controlled in-process callers often already hold a
decoded payload. They call the same routing function, `Server.deliverMessage`,
on their own thread without entering the raw network FIFO:

```text
deliverMessage(message) -> destination.tryOffer
```

Decode is a no-op for a decoded message. This preserves one source of routing
truth without imposing a network scheduling hop on local queries, which keep
using no queue at all ([MESSAGING.md](../../convex-peer/docs/MESSAGING.md)). The
local path uses neither ingress fairness nor byte accounting. A full
destination makes the local caller wait through the destination's bounded
test, and then returns the existing load result, as today.

## Message lifecycle

| State | Owner | Credit retained | Next event |
| --- | --- | --- | --- |
| Framing | transport | no | completed frame |
| Admitted raw | ingress connection | yes | scheduler service |
| Decoding | ingress worker | yes | decoded or rejected |
| Destination blocked | ingress connection | yes | capacity signal |
| Destination admitted | domain handler | no ingress credit | handler service |
| Rejected | ingress/error path | released | response or close |

At no point should a message be owned by two queues.

## Failure handling

### Decode failure

Malformed data, unresolved partial data and ordinary decode exceptions are
contained by the ingress worker. The current best-effort error response should
be preserved where an ID and return path can be determined. Repeated malformed
input may feed the existing per-connection rejection policy.

An ordinary exception must not terminate an ingress worker: each turn is
contained per message, as `RequestFailures` does. A JVM failure closes the
affected connection rather than silently losing a worker, and on a Peer
`Server`'s ingress also requests the Peer's shutdown, as `LoopFailures` does for
every other Peer loop.

### Disconnect

Disconnect is the single cleanup sink for a connection:

- stop admission;
- remove it from active and destination-blocked sets;
- discard pending messages according to current connection semantics;
- release all global and local credits exactly once;
- cancel capacity callbacks;
- complete or fail outbound awaiting futures as closed.

### Shutdown

Server shutdown closes the connection manager, then the network listeners, then
the ingress, then the domain handlers. Closing the ingress:

1. stops new admission;
2. discards accepted ingress work, releasing its credit;
3. waits for ingress workers to finish their current turn;
4. stops the deadline timer.

The shared client ingress uses daemon threads and closes from the same shutdown
hook as the Netty client event loops.

No virtual thread, capacity callback or Netty event-loop task may keep the
process alive after shutdown.

## Performance model

The design adds one unavoidable scheduling hand-off for raw network input. That
handoff is the isolation boundary which removes hostile decode from the I/O
thread.

It must not add further layers:

- no extra payload copy after framing;
- no re-encoding;
- no per-message virtual thread;
- no global decoded-message queue;
- no executor task between decode and destination offer;
- no duplicate classification in `Server`;
- no blocking destination retry.

A small set of long-lived ingress workers should select, decode and route in one
turn. Scheduler activation should occur only when a connection changes from
idle to active. Under low load, the added cost is one queue operation and one
worker wake-up. Under load, batching within a scheduler quantum may amortise
wake-ups without relaxing fairness.

The implementation replaces, rather than wraps:

- `Server.deliverMessage`;
- the initial routing responsibility of `Server.processMessage`;
- the blocking `Server.receiveAction` retry wrapper;
- the accepted-Netty delivery function;
- the outbound `NettyConnection` consumer wrapper;
- decode inside outbound result-future continuations.

## Configuration

The settings are documented constants in `Config`, alongside the existing
queue sizes, with no new Peer configuration keywords. Starting values, before
tuning from measurements:

| Setting | Purpose | Starting value |
| --- | --- | --- |
| decoder worker count | maximum concurrent ingress decode/routing work | 3 |
| maximum ordinary decodes | preserves verified-Peer capacity | worker count − 1 |
| per-connection message limit | bounds tiny-message bursts | 32, resuming at 16 |
| per-connection byte limit | bounds large-message bursts | 1 MB, resuming at 512 KB |
| global ingress byte limit | bounds total retained encoded data | 64 MB |
| verified/ordinary quantum | weighted scheduler policy | 1 MB / 64 KB |
| minimum message cost | prevents tiny-frame monopolisation | 512 bytes |
| control queue capacity | bounds protocol-control work | as the query queue |

High and low water marks are distinct to avoid rapidly toggling `autoRead`.

## Anticipated code changes

- Add the ingress scheduler/router and deterministic scheduler tests.
- Change `NettyInboundHandler` to perform framing plus non-blocking raw
  admission only.
- Change `NettyServer` and `NettyConnection` to install a delivery function
  capable of returning per-channel backpressure.
- Give accepted Peer-server and outbound client connections explicit route
  profiles.
- Replace `Server.deliverMessage`, `Server.receiveAction` and the duplicated
  initial routing path.
- Add a bounded control handler.
- Add non-blocking capacity signals to the transaction, query and control
  destinations.
- Complete outbound result futures on ingress workers, so their continuations
  decode there.
- Adapt the NIO transport to the same ingress contract: pausing clears
  `OP_READ`, resuming restores it and wakes the selector.
- Retain a decoded fast path for `ConvexLocal`.

## Verification

### Deterministic functional tests

- No network payload decode occurs on a Netty event-loop thread.
- One deliberately blocked decode does not prevent another connection from
  being framed and admitted.
- FIFO ordering is preserved per connection.
- A fast client consumes only its configured message and byte allowance.
- Global byte accounting is released exactly once on success, rejection,
  disconnect and shutdown.
- Destination saturation pauses only affected connections and resumes them on a
  real capacity signal.
- Scheduler service matches configured weights without starving untrusted
  traffic.
- Sustained untrusted decode load cannot consume reserved verified-Peer
  capacity.
- Outbound connections remain untrusted before challenge verification and move
  lanes after successful verification.
- Status-only fallback identification remains untrusted.
- Malformed input cannot terminate an ingress worker.
- Accepted server channels, outbound client channels, NIO and local delivery
  preserve their expected routing behaviour.

Concurrency tests must use latches, futures or explicit capacity signals, never
sleeps. Network tests bind port `0`.

### Performance tests

Before and after measurements should cover:

- loopback query p50 and p99 latency;
- `QueryThroughput`;
- transaction admission throughput;
- Belief propagation latency under simultaneous untrusted load;
- allocations and retained encoded bytes per message;
- event-loop responsiveness during large and malformed decodes;
- trusted and untrusted throughput under the selected scheduler weights.

A material low-load regression indicates that the implementation has introduced
extra queueing or task submission beyond the single ingress boundary.

## Rollout

Suggested implementation sequence:

1. Land the scheduler, byte accounting and destination-capacity abstraction
   under deterministic unit tests.
2. Connect accepted Netty Peer-server channels and the control handler.
3. Connect transaction, query and Belief destinations end to end.
4. Connect outbound `ConvexRemote` result/control routing.
5. Adapt NIO and the local decoded fast path.
6. Remove the old delivery, decode, routing and retry paths.
7. Run the full build and performance comparison.

Temporary dual paths may be useful while compiling intermediate commits, but
the final change must have one authoritative network-ingress route.

## Alternatives considered

### Keep decode on the event loop

Rejected. Size limits bound a single frame but not decode cost or work rate, and
shared event loops make this an availability risk.

### Insert a generic queue before existing delivery

Rejected. This adds latency while retaining duplicated decode, routing and
backpressure logic.

### Put raw messages directly on transaction/query queues

Rejected. Most vector-based message types cannot be classified before decode,
and it would make each domain handler duplicate decode/error policy.

### Use one global FIFO

Rejected. A fast connection can fill it, byte fairness is poor, and trust-aware
service becomes global message prioritisation rather than connection fairness.

### Strict trusted priority

Rejected. It can starve the challenge/status traffic needed for legitimate
connections to establish trust.

### Spawn one virtual thread per message

Rejected. It provides no admission fairness, allows unbounded concurrent decode
and adds per-message scheduling overhead.

### Rely only on Netty receive allocators

Rejected as a complete solution. They bound work per socket read loop but do
not schedule decoded application work across connections or carry destination
pressure back to a sender.

### Peek the vector keyword from raw CAD3

Rejected initially. It couples ingress routing to encoding details, still needs
a general decode/error stage and does not remove destination backpressure
requirements.

### Pause a connection for a full Belief queue

Rejected. Pushed Beliefs and DATA are best-effort: when the propagation queue is
full they are dropped and the connection is never paused, so pushes cannot
hold up the polling that keeps consensus live
([PEER_PRIORITY.md](../../convex-peer/docs/PEER_PRIORITY.md#liveness)).
