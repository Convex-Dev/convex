# Peer ingress decode and routing

- Status: Proposed
- Tracking issue: [#608](https://github.com/Convex-Dev/convex/issues/608)
- Last updated: 2026-07-28

## Summary

Peer network input should cross one asynchronous ingress boundary that owns a
message from completed framing until the message has been accepted by its final
destination queue.

That boundary should:

1. admit raw messages under per-connection and global count/byte limits;
2. schedule connections fairly, with additional capacity for verified Peers;
3. decode the message once, away from network I/O threads;
4. classify it and perform all initial routing;
5. retain backpressure until the selected destination accepts the message.

This is not intended to add a generic middleware layer. It replaces the current
decode, observation, routing and retry paths with one `PeerIngressRouter`-style
component. Existing transaction, query and Belief workers remain responsible
for their domain work.

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
| `BELIEF` | trusted or untrusted `BeliefPropagator` queue |
| `PING`, `STATUS`, `CHALLENGE`, `GOODBYE`, verification `RESULT` | bounded control handler |
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
5. **Trust is verified.** Only `AConnection.isTrusted()` traffic receives the
   verified-Peer service class.
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

The implementation may use a different class name, but this document calls the
owner `PeerIngressRouter`.

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
5. returns immediately.

If credit is unavailable, the adapter applies the existing parking-ticket
pattern: disable `autoRead` for that channel and retry admission away from the
event loop. Once credit falls below the low-water mark, resumption is scheduled
on the channel's event loop. Bytes already held in
`ByteToMessageDecoder`'s cumulation must also be made eligible for decoding.

Netty receive allocators should additionally cap bytes or read attempts per
socket read loop. This reduces event-loop monopolisation before application
admission, but it is defence in depth rather than the fairness mechanism.

### Fair scheduler

The scheduler selects active connections rather than selecting individual
messages from one global FIFO.

Weighted deficit round-robin is the preferred model:

- encoded length is the principal service cost;
- every message has a minimum cost, preventing floods of tiny frames from being
  effectively free;
- a connection consumes at most its available byte/message quantum before
  returning to the end of its lane;
- FIFO ordering is preserved within the connection.

Two lanes are required:

- **verified Peer**: the connection has a challenge-verified trusted key;
- **untrusted/client**: all other network connections, including outbound
  sockets before verification.

Verified Peers receive a larger quantum. Strict priority is not acceptable:
untrusted challenge and status traffic must make progress so a legitimate
connection can become trusted.

Queue weighting alone cannot guarantee verified-Peer latency because decode is
not pre-emptible. With `N` concurrent decode permits, untrusted work may occupy
at most `N - 1`. This leaves capacity for already-verified Peer traffic during
sustained hostile input. The configured decoder count must therefore be at
least two when hard trusted-capacity reservation is enabled.

Defaults for worker count, quantum and queue limits should be selected from
benchmarks rather than fixed by this proposal.

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

Each destination exposes a non-blocking offer and a capacity signal. The exact
API is an implementation detail, but it must have the equivalent of:

```java
boolean tryOffer(Message message);
void signalWhenCapacityAvailable(Runnable action);
```

The signal must be race-safe and driven by an actual dequeue/capacity change;
workers must not poll or sleep.

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
the decode scheduler. A small bounded control handler should receive:

- `PING` and `STATUS`;
- `CHALLENGE` and verification `RESULT`;
- `GOODBYE`;
- any future cheap protocol-control messages.

This keeps ingress work limited to decode, classification and admission.
Per-connection limits prevent an untrusted control-message flood from claiming
unbounded control capacity. The control handler must retain enough service for
challenge/response to promote legitimate connections.

### Outbound connection routing

Locally initiated `ConvexRemote` connections use the same raw admission and
fair scheduler.

Their route profile differs from an accepted Peer-server channel:

- a decoded `RESULT` completes its awaiting future;
- a reverse `CHALLENGE` reaches the client control handler;
- an enabled reverse `DATA_REQUEST` reaches its configured handler;
- unexpected messages are rejected or logged according to current semantics.

Future completion occurs only after decode, so synchronous future continuations
cannot accidentally move decode back onto the Netty event loop.

After `verifyPeer` succeeds, `setTrustedKey` promotes the connection to the
verified-Peer scheduler lane. Status-based fallback identification does not.
The trusted key must be safely published to ingress workers, for example with a
`volatile` field or an explicit scheduler state transition.

### Local decoded fast path

`ConvexLocal` and other controlled in-process callers often already hold a
decoded payload. They should call the same decoded routing function without
entering the raw network FIFO:

```text
routeDecoded(message) -> destination.tryOffer
```

This preserves one source of routing truth without imposing a network
scheduling hop on local queries. A full destination still returns the existing
bounded load/backpressure result to the local caller.

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

An ordinary exception must not terminate an ingress worker. Serious JVM errors
follow the repository's existing containment policy and should disable or close
the affected connection rather than silently lose the sole dispatcher.

### Disconnect

Disconnect is the single cleanup sink for a connection:

- stop admission;
- remove it from active and destination-blocked sets;
- discard pending messages according to current connection semantics;
- release all global and local credits exactly once;
- cancel capacity callbacks;
- complete or fail outbound awaiting futures as closed.

### Shutdown

Server shutdown:

1. stops new admission;
2. either drains accepted ingress work or rejects it deterministically;
3. waits for ingress workers to finish within the configured lifecycle bound;
4. closes domain handlers and network resources in the established order.

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

The following should be operator-configurable or derived from a small set of
documented defaults:

| Setting | Purpose |
| --- | --- |
| decoder worker count | maximum concurrent ingress decode/routing work |
| maximum untrusted decodes | preserves verified-Peer capacity |
| per-connection message limit | bounds tiny-message bursts |
| per-connection byte limit | bounds large-message bursts |
| global ingress byte limit | bounds total retained encoded data |
| trusted/untrusted quantum | weighted scheduler policy |
| minimum message cost | prevents tiny-frame monopolisation |
| control queue capacity | bounds protocol-control work |

High and low water marks should be distinct to avoid rapidly toggling
`autoRead`.

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
- Add non-blocking capacity signals to transaction, query, Belief and control
  destinations.
- Move outbound result decode before future completion.
- Make trusted-key publication safe across threads.
- Adapt the NIO transport to the same ingress contract.
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

## Open implementation choices

- Default worker count and reserved trusted capacity.
- Initial per-connection and global byte limits.
- Trusted and untrusted scheduler quanta.
- Whether the control handler uses one queue or separate verification and
  ordinary-control queues.
- The exact race-safe destination-capacity notification API.
- Whether decoded local callers may bypass fairness only, or both fairness and
  ingress byte accounting.

These choices require benchmarks and implementation tests; they do not change
the architectural boundary established by this proposal.
