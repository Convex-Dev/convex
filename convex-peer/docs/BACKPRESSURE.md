# Server-Side Backpressure

**See also:** [CLIENT_BACKPRESSURE.md](CLIENT_BACKPRESSURE.md) — client-side outbound backpressure;
[PROPAGATION.md](PROPAGATION.md) — peer delta fan-out and backpressure;
[peer-ingress-routing.md](../../docs/proposals/peer-ingress-routing.md) — the ingress design

## Overview

When clients send messages faster than the peer can process them, the peer stops
reading the connections that are ahead of it rather than refusing their requests.
Each connection is throttled on its own, by TCP, while all other connections continue
normally. Under load, clients see brief waits instead of errors, and throughput
matches processing capacity rather than queue capacity.

A full queue is a reason to wait, never on its own a reason to refuse. `:LOAD` means
the peer is deliberately shedding work: a policy limit, or a wait that ran out.

Results going the other way never wait. A client whose results back up has its
requests read no further until they drain ([Reply Backlog](#reply-backlog)), so the
waiting is done by its socket, not by a thread of the peer.

## Architecture

```
network thread (Netty event loop, or NIO selector)
   frame bytes → Message → IngressChannel.admit(m)
                               │
                  false: stop reading this connection
                               │
ingress workers (Config.INGRESS_WORKERS)
   take the next connection's messages in order
   decode → Server.receiveMessage(m)
                               │
              ┌────────────────┴────────────────┐
            null                          Destination (queue full)
              │                                 │
            done                 hold the message at the head of its
                                 connection, with no thread waiting
                                                │
                                   ┌────────────┴────────────┐
                              room in queue          deadline passes
                                   │                         │
                              place it               :LOAD (requests)
                                   └────────────┬────────────┘
                                                ▼
                                   connection served again;
                                   reading resumes at half its limits
```

A network thread only frames bytes into messages: no payload is decoded on it, so a
costly decode delays only its own connection and occupies one ingress worker, never
an event loop serving many connections. Each connection's messages are decoded and
routed in the order they arrived, by one worker at a time.

### Ingress limits

| Limit | Value | Effect |
|-------|-------|--------|
| Messages waiting per connection | `Config.INGRESS_CONNECTION_MESSAGES` (32) | Reading stops; resumes at half |
| Bytes waiting per connection | `Config.INGRESS_CONNECTION_BYTES` (1 MB) | Reading stops; resumes at half |
| Bytes waiting in all | `Config.INGRESS_TOTAL_BYTES` (64 MB) | Each connection that adds to it stops; all resume at half |
| Wait for a full queue | `Config.DEFAULT_INTERNAL_TIMEOUT` (8 s) | A request is answered `:LOAD`; anything else is dropped |

### Lanes

Connections are served in two lanes. A trusted connection (`AConnection.isTrusted`)
is in the priority lane: on a peer, one verified as a staked peer's own link
([PEER_PRIORITY.md](PEER_PRIORITY.md)); among the connections a process opens, one
whose peer has proved its key. Every other connection is ordinary.

The lanes alternate while both have work. Within a lane connections take turns by
encoded bytes, a turn allowing `Config.INGRESS_PRIORITY_QUANTUM` (64 KB) in the
priority lane and `Config.INGRESS_ORDINARY_QUANTUM` (2 KB) in the ordinary one. Each
message counts as at least `Config.INGRESS_MINIMUM_COST` (512 bytes), so a stream of
tiny frames cannot take more turns than its share. Ordinary connections are served by
at most `Config.INGRESS_WORKERS` minus `Config.INGRESS_RESERVED_WORKERS` workers, so a
flood of clients always leaves verified peers a worker.

### Message Types

| Type | Destination | When it is full |
|------|-------------|-----------------|
| TRANSACT | `txMessageQueue` | Waits |
| QUERY, DATA_REQUEST | `queryQueue` | Waits |
| PING, STATUS, CHALLENGE, GOODBYE | control handler queue (`Config.CONTROL_QUEUE_SIZE`) | Waits |
| BELIEF, DATA | propagator queue | Dropped: best-effort peer propagation |
| RESULT | the awaiting request on that connection | Never full |

Control messages have their own queue and thread, so a burst of them neither takes
an ingress worker for long nor waits behind transactions or queries.

### Connections a Node Opens

The connections a process opens share one client ingress (`ClientIngress`), whatever
their transport, and their results are decoded on its workers. A connection pauses in
the same way when its delivery asks to wait (`Transport.connectWithDelivery`, used by
`ConvexRemote`); a delivery that returns its own retry, rather than a queue, has the
retry run on a virtual thread while the connection waits. Lattice nodes rely on this
for requests and messages a peer sends back over a route the node opened; see
[LATTICE_NETWORKING.md](LATTICE_NETWORKING.md#inbound-pipeline). A transport that
cannot pause, such as a factory that implements only `connect`, answers such a
request with `:LOAD` at once and drops anything else.

### ConvexLocal

For in-JVM clients there is no network thread and no ingress: `ConvexLocal` delivers
on the caller's thread with `Server.deliverMessage`, handling control messages there
too. If a queue is full, the caller's thread waits on the queue for space. A caller on
the queue's own thread, such as a local client's callback for a result that handler
returned, is refused at once instead: only that thread empties the queue, so the wait
could only time out. `ConvexLocal` queries take no queue: they run on the client's own
thread ([MESSAGING.md](MESSAGING.md) §4.4).

### Reply Backlog

Replies to a client go through the server's shared outbound queue, and no thread
waits to add one: a reply is queued, or refused at once. Each connection counts its
backlog, the bytes of its replies queued or handed to Netty and not yet written:

| Condition | Effect |
|-----------|--------|
| Backlog reaches half `Config.SERVER_CONNECTION_PENDING_BYTE_LIMIT` | The handler holds back the next message it frames and stops reading the socket |
| Backlog written down to a quarter | The write completion that crosses it admits the held message and resumes reading |
| More than the whole limit handed to Netty and not yet written | Further replies to that client are refused: it is not reading |

Replies queued but not yet handed to Netty do not count towards the refusal, so a
burst of results for one client is absorbed by the shared queue.

So a client that sends faster than it reads is slowed by TCP, like one whose requests
fill a queue, rather than losing results. Pausing always holds a framed message, so
that Netty's decoder does not ask the socket for more input while reads are off.

A verified connection is never paused for its replies: it has a far larger allowance
(`Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT`) and a lane of its own, served first. A
lattice node verifies the connections it admits; a consensus peer verifies a
connection that proves it is a staked peer's own link
([PEER_PRIORITY.md](PEER_PRIORITY.md)). Every other connection is treated as a
client here. A client reply that finds the shared queue full is refused: the writer
only hands messages to Netty, so that takes a server too short of CPU for the writer
to keep up.

## Queue Processing

Both `TransactionHandler` and `QueryHandler` use batch-drain processing: block
until at least one message arrives, then `drainTo()` to grab all queued messages
in a single lock acquisition. This significantly reduces contention under load
compared to per-message polling. A drain that frees room tells the connections
waiting for it, which an ingress worker then serves.

The TransactionHandler further batches messages into blocks for consensus. The
QueryHandler processes each query against the current consensus state independently.

The consensus propagator has separate bounded inputs: an ordered, byte-bounded
message queue for wire DATA/BELIEF sequences, and a count-bounded FIFO for complete
Beliefs already acquired into the local store by status polling. Local Beliefs are
never encoded merely to measure their queue footprint. Either queue drops a new
entry when full; neither applies connection backpressure. Periodic status polling
waits for room in the queue of polled Beliefs, and a changed remote Belief is
acquired only while it has room; polling never waits for the message queue, so
pushes filling it never stop polling ([PEER_PRIORITY.md](PEER_PRIORITY.md#liveness)).

## Connection Limiting

- **Limit:** `Config.MAX_CLIENT_CONNECTIONS` (default 1024)
- **Enforcement:** `NettyServer.initChannel()` rejects and closes if limit reached,
  logging at debug, since any remote can cause it
- **Cleanup:** `DefaultChannelGroup` auto-removes channels on close
- **Shutdown:** `clientChannels.close()` provides clean bulk teardown

### Memory Budget

| Component | Memory | Notes |
|-----------|--------|-------|
| Netty `SocketChannel` + pipeline | ~4 KB | Channel object, handler contexts |
| Read/write `ByteBuf` | 2–64 KB | Adaptive allocator, grows under load |
| Kernel socket buffers | ~128 KB | `SO_RCVBUF` + `SO_SNDBUF`, outside JVM heap |
| Ingress messages waiting | up to 1 MB | Encoded bytes awaiting decode; 64 MB across all connections |

**Per connection: ~200 KB idle, ~300 KB under load**, plus whatever it has waiting in
the ingress.

At 1024 connections: ~200–300 MB total, mostly kernel socket buffers (outside JVM heap).
Operators can adjust `MAX_CLIENT_CONNECTIONS` for high-traffic or constrained environments.

## Design Rationale

### Per-Channel, Not Global

A global backpressure mechanism (e.g. pause all reads when any queue fills) would
penalise well-behaved clients for a single fast sender. Per-channel backpressure
isolates the effect: only the specific client that hit the full queue is throttled.
The one global bound, on bytes waiting in the ingress, stops only the connections
that add to it.

### Waiting Without a Thread

A message whose queue is full stays at the head of its connection, and the queue
signals when it has room; no thread parks per waiting connection, and the worker
moves on to other connections. The network threads never block either.

### Clean Dependency Direction

`Message` is in `convex-core` and carries no Netty-specific references. The server
exposes `receiveMessage` as a `Function<Message, Predicate<Message>>` that returns
the destination that refused a message. The transport frames and pauses; the ingress
schedules, decodes and waits. The server never sees a channel.

### Deadlock Safety

The Netty PR #6662 deadlock (both sides block on writes simultaneously) does not
apply: no server thread waits to send (a reply is queued or refused at once), and an
ingress worker never waits for a queue. A client's reads pause for its own reply
backlog, which drains as soon as that client reads, whatever the server is doing.

## Performance

Measured on a single peer with `ConvexLocal` (in-JVM, no network overhead):

| Workload | Throughput |
|----------|-----------|
| Simple query (constant), 20 sync clients | ~1M QPS |
| Simple query (constant), 20 async clients | ~1.3M QPS |
| Loop x100 query, 20 sync clients | ~80k QPS |
| Pre-signed transfers, 20 async clients | ~190k TPS |

Query load does not materially affect transaction throughput — they use separate
queues and handler threads. 100k+ TPS is achievable under 600k+ QPS sustained
query load.

## Future Direction

### Per-Channel Max Message Size

The protocol allows messages up to 50 MB, primarily for peer Belief messages. A
client has no reason to send a 50 MB message — transactions and queries are
typically under 100 KB. But peer Beliefs arrive on inbound connections like client
requests, and a consensus peer can tell the two apart only once it has verified the
connection, after its first Belief ([PEER_PRIORITY.md](PEER_PRIORITY.md)). So a
smaller limit cannot yet be set for clients alone to prevent malicious cumulation
buffer growth.

### QueryHandler Parallelism

The QueryHandler is deliberately single-threaded (`AThreadedComponent`). This
caps query processing at one core, which is correct for normal peers — most
queries are fast (immutable state lookups), and limiting to one thread prevents
query floods from starving transaction processing or consensus.

Parallelisation is technically straightforward (queries are read-only against
immutable `State`, no ordering dependencies) but not normal peer behaviour. May
be a future configuration option for specialised query-focused peers that do not
participate in consensus.

## References

- [Netty #10254: Write Throttling / Back Pressure](https://github.com/netty/netty/issues/10254) — Norman Maurer's guidance
- [Netty PR #6662: FlowControlHandler](https://github.com/netty/netty/pull/6662) — rejected due to bidirectional deadlock risk
- [Cassandra Backpressure](https://cassandra.apache.org/_/blog/Improving-Apache-Cassandras-Front-Door-and-Backpressure.html) — production `setAutoRead(false)` at scale
