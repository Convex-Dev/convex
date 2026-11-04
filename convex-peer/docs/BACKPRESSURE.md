# Server-Side Backpressure

**See also:** [CLIENT_BACKPRESSURE.md](CLIENT_BACKPRESSURE.md) — client-side outbound backpressure;
[PROPAGATION.md](PROPAGATION.md) — peer delta fan-out and backpressure

## Overview

When clients send messages faster than the peer can process them, the server applies
per-channel backpressure rather than rejecting with immediate `:LOAD` errors. Each
channel independently pauses its own socket reads and retries delivery on a virtual
thread, while all other channels continue normally.

The result: under load, clients experience brief blocking instead of errors. Throughput
matches processing capacity rather than queue capacity.

A full queue is a reason to wait, never on its own a reason to refuse. `:LOAD` means
the peer is deliberately shedding work: a policy limit, or a wait that ran out.

Results going the other way never wait. A client whose results back up has its
requests read no further until they drain ([Reply Backlog](#reply-backlog)), so the
waiting is done by its socket, not by a thread of the peer.

## Architecture

```
                    ┌──────────────────────────────┐
                    │        Server                 │
                    │                               │
                    │  processMessage(m)             │
                    │    → null (accepted)           │
                    │    → Predicate (retry fn)      │
                    │                               │
                    │  Pre-allocated retry refs:     │
                    │    txnRetry, queryRetry        │
                    └──────────┬───────────────────┘
                               │ Function<Message, Predicate<Message>>
                    ┌──────────▼───────────────────┐
                    │   NettyInboundHandler          │
                    │                               │
                    │   manages: setAutoRead()       │
                    │   manages: backpressure gate   │
                    │   manages: virtual thread      │
                    └───────────────────────────────┘
```

### Dispatch

`Server.processMessage(Message)` returns either **null** (accepted — fast path, zero
allocation) or a **pre-allocated retry predicate** (queue full — the predicate blocks
with timeout until space is available).

`Server.deliverMessage(Message)` wraps decode + observe + processMessage for callers
that need the full pipeline (NettyServer, ConvexLocal).

### Message Types

| Type | Backpressure | Reason |
|------|-------------|--------|
| TRANSACT | Yes — `txMessageQueue` | Primary client workload |
| QUERY, DATA_REQUEST | Yes — `queryQueue` | Primary client workload |
| BELIEF, DATA | No | Best-effort peer propagation; drop if its dedicated queue is full |
| STATUS | No | Inline response, no queue |
| CHALLENGE / RESPONSE | No | Authentication protocol, must complete promptly |
| GOODBYE | No | Connection teardown, must not delay |

### Backpressure Flow

When a queue is full, the Netty handler pauses socket reads for that channel,
retries delivery on a virtual thread, and resumes reads when the retry succeeds
or times out. The I/O thread never blocks. See `NettyInboundHandler` for the
full lifecycle including cumulation buffer flush on resume.

```
I/O thread: decode()
                │
                ├─ backpressured? → return (stop decode loop)
                │
                ▼
          parse message → deliver(m)
                │
       ┌────────┴────────┐
     null              Predicate
       │                  │
     done            pause reads
   (fast path)       spawn virtual thread → retry
                          │
                   ┌──────┴──────┐
                 success       timeout
                   │              │
                   │           return :LOAD
                   └──────┬───────┘
                          ▼
                    resume reads
                    flush stranded data
```

### Connections a Node Opens

A client connection pauses in the same way when its delivery asks to wait
(`Transport.connectWithDelivery`, used by `ConvexRemote`). Lattice nodes rely on this
for requests and messages a peer sends back over a route the node opened; see
[LATTICE_NETWORKING.md](LATTICE_NETWORKING.md#inbound-pipeline). A transport that
cannot pause, such as the NIO client whose one selector thread serves every
connection, answers such a request with `:LOAD` at once and drops anything else.

### ConvexLocal

For in-JVM clients (no network), there is no Netty handler. If the queue is full,
`ConvexLocal` calls the retry predicate directly on the caller's thread, which waits
for space. A caller on the handler's own thread, such as a local client's callback for
a result that handler returned, is refused at once instead: only that thread empties
the queue, so the wait could only time out. `ConvexLocal` queries take no queue: they
run on the client's own thread ([MESSAGING.md](MESSAGING.md) §4.4).

### Reply Backlog

Replies to a client go through the server's shared outbound queue, and no thread
waits to add one: a reply is queued, or refused at once. Each connection counts its
backlog, the bytes of its replies queued or handed to Netty and not yet written:

| Backlog | Effect |
|---------|--------|
| Half `Config.SERVER_CONNECTION_PENDING_BYTE_LIMIT` | The handler holds back the next request it decodes and stops reading the socket |
| Written down to a quarter | The write completion that crosses it delivers the held request and resumes reading |
| The whole limit | Further replies to that client are refused: it is not reading |

So a client that sends faster than it reads is slowed by TCP, like one whose requests
fill a queue, rather than losing results. Pausing always holds a decoded request, so
that Netty's decoder does not ask the socket for more input while reads are off.

A verified peer's reads are never paused for its replies: it has a far larger
allowance (`Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT`) and a lane of its own. A client
reply that finds the shared queue full is refused: the writer only hands messages to
Netty, so that takes a server too short of CPU for the writer to keep up.

## Queue Processing

Both `TransactionHandler` and `QueryHandler` use batch-drain processing: block
until at least one message arrives, then `drainTo()` to grab all queued messages
in a single lock acquisition. This significantly reduces contention under load
compared to per-message polling.

The TransactionHandler further batches messages into blocks for consensus. The
QueryHandler processes each query against the current consensus state independently.

The consensus propagator has separate bounded inputs: an ordered, byte-bounded
message queue for wire DATA/BELIEF sequences, and a count-bounded FIFO for complete
Beliefs already acquired into the local store by status polling. Local Beliefs are
never encoded merely to measure their queue footprint. Either queue drops a new
entry when full; neither applies connection backpressure. Periodic status polling
is deferred while either trusted input queue is full, and a changed remote Belief
is acquired only while that capacity remains available.

## Connection Limiting

- **Limit:** `Config.MAX_CLIENT_CONNECTIONS` (default 1024)
- **Enforcement:** `NettyServer.initChannel()` rejects and closes if limit reached
- **Cleanup:** `DefaultChannelGroup` auto-removes channels on close
- **Shutdown:** `clientChannels.close()` provides clean bulk teardown

### Memory Budget

| Component | Memory | Notes |
|-----------|--------|-------|
| Netty `SocketChannel` + pipeline | ~4 KB | Channel object, handler contexts |
| Read/write `ByteBuf` | 2–64 KB | Adaptive allocator, grows under load |
| Kernel socket buffers | ~128 KB | `SO_RCVBUF` + `SO_SNDBUF`, outside JVM heap |
| Virtual thread (backpressured only) | ~1 KB | Transient — only exists during retry |

**Per connection: ~200 KB idle, ~300 KB under load.**

At 1024 connections: ~200–300 MB total, mostly kernel socket buffers (outside JVM heap).
Operators can adjust `MAX_CLIENT_CONNECTIONS` for high-traffic or constrained environments.

## Design Rationale

### Per-Channel, Not Global

A global backpressure mechanism (e.g. pause all reads when any queue fills) would
penalise well-behaved clients for a single fast sender. Per-channel backpressure
isolates the effect: only the specific client that hit the full queue is throttled.

### Virtual Threads for Retry

The Netty I/O thread must never block. The blocking retry needs *some* thread to
park on the queue. Virtual threads are ideal: lightweight (~1 KB), bounded by
timeout, and don't consume a platform thread while parked.

### Pre-Allocated Predicates

The retry predicates (`txnRetry`, `queryRetry`) are method references bound once
at server construction. Zero allocation on the reject path — important since
rejects happen precisely when the server is under load.

### Clean Dependency Direction

`Message` is in `convex-core` and carries no Netty-specific references. The server
exposes `processMessage` as a pure `Function<Message, Predicate<Message>>`. The
handler orchestrates all channel-level backpressure. The server never sees a channel.

### Deadlock Safety

The Netty PR #6662 deadlock (both sides block on writes simultaneously) does not
apply: no server thread waits to send (a reply is queued or refused at once), and
virtual threads hold no locks. A client's reads pause for its own reply backlog,
which drains as soon as that client reads, whatever the server is doing.

## Backward Compatibility

- `:LOAD` error only on timeout — existing retry logic still works but fires rarely
- Wire protocol unchanged
- API unchanged (`transact()` / `query()` still return `CompletableFuture<Result>`)
- Only observable change: calls may take longer to return under load (blocking
  instead of immediate error)

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
typically under 100 KB. Once peer/client channel classification is implemented,
client channels should enforce a smaller limit (e.g. 1 MB) to prevent malicious
cumulation buffer growth.

### Peer Priority

Peer-to-peer Belief propagation should not be affected by client load. This
requires distinguishing peer channels from client channels — a prerequisite for
both max message size and priority scheduling. See [PEER_PRIORITY.md](PEER_PRIORITY.md).

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
