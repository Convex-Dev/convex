# Server-Side Backpressure

**See also:** [CLIENT_BACKPRESSURE.md](CLIENT_BACKPRESSURE.md) — client-side outbound backpressure

## Overview

When clients send messages faster than the peer can process them, the server applies
per-channel backpressure rather than rejecting with immediate `:LOAD` errors. Each
channel independently pauses its own socket reads and retries delivery on a virtual
thread, while all other channels continue normally.

The result: under load, clients experience brief blocking instead of errors. Throughput
matches processing capacity rather than queue capacity.

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
| BELIEF | No | Peer protocol, small dedicated queue, must not block |
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

### ConvexLocal

For in-JVM clients (no network), there is no Netty handler. If the queue is full,
`ConvexLocal` calls the retry predicate directly on the caller's thread — blocking
is safe because the caller is an application thread, not an I/O thread.

## Queue Processing

Both `TransactionHandler` and `QueryHandler` use batch-drain processing: block
until at least one message arrives, then `drainTo()` to grab all queued messages
in a single lock acquisition. This significantly reduces contention under load
compared to per-message polling.

The TransactionHandler further batches messages into blocks for consensus. The
QueryHandler processes each query against the current consensus state independently.

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
apply: server write volume is tiny (Result messages), the client's event loop is
never blocked, and virtual threads hold no locks.

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
