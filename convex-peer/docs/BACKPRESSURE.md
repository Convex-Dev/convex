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
                    │   has: ChannelHandlerContext    │
                    │   has: deliver function         │
                    │   has: backpressured flag       │
                    │                               │
                    │   manages: setAutoRead()       │
                    │   manages: virtual thread      │
                    │   manages: decode gating       │
                    └───────────────────────────────┘
```

### Server Dispatch

`Server.processMessage(Message)` returns `Predicate<Message>`:

- **null** — message accepted (fast path, zero allocation)
- **non-null** — queue full; the returned predicate is a pre-allocated blocking retry
  function (`transactionHandler::offerTransactionBlocking` or
  `queryHandler::offerQueryBlocking`)

The retry methods use `queue.offer(m, timeout, MILLISECONDS)` internally. The timeout
is `Config.DEFAULT_CLIENT_TIMEOUT` (8 seconds) — the same duration the client would
wait before giving up.

`Server.deliverMessage(Message)` wraps decode + observe + processMessage for callers
that need the full pipeline (NettyServer, ConvexLocal).

### Message Types

| Type | Backpressure | Reason |
|------|-------------|--------|
| TRANSACT | Yes — `txMessageQueue` | Primary client workload |
| QUERY, DATA_REQUEST | Yes — `queryQueue` | Primary client workload |
| BELIEF | No | Peer protocol, small dedicated queue (200), must not block |
| STATUS | No | Inline response, no queue |
| CHALLENGE / RESPONSE | No | Authentication protocol, must complete promptly |
| GOODBYE | No | Connection teardown, must not delay |

### Handler Backpressure Flow

The `NettyInboundHandler` owns the backpressure lifecycle. A per-handler `volatile boolean
backpressured` flag gates decoding — when true, `decode()` returns without consuming bytes,
stopping the `ByteToMessageDecoder` loop.

```
I/O thread: decode()
                │
                ├─ backpressured? → return (don't consume bytes, loop stops)
                │
                ▼
          parse message
                │
                ▼
          deliver.apply(m)
                │
       ┌────────┴────────┐
     null              Predicate
       │                  │
     done            backpressured = true
   (fast path)       setAutoRead(false)
                          │
                          ▼
                    virtual thread:
                      retry.test(m)
                          │
                   ┌──────┴──────┐
                 true          false (timeout)
                   │              │
                 done          return :LOAD
                   │              │
                   └──────┬───────┘
                          ▼
                    backpressured = false
                    setAutoRead(true)
                          │
                          ▼
                    fire synthetic channelRead
                    (flush cumulation buffer)
                          │
                          ▼
                    remaining bytes decoded in order
```

### Cumulation Buffer Flush

When `decode()` returns early (backpressured gate), `ByteToMessageDecoder` sees no
progress and stops its decode loop. Any bytes already read from the socket remain in
the cumulation buffer. When backpressure clears and `setAutoRead(true)` is called,
Netty resumes reading from the OS socket — but if all data was already read into the
cumulation buffer before autoRead was disabled, no new `channelRead` event fires and
the stranded data is never processed.

To handle this, the virtual thread fires a synthetic `channelRead` with an empty buffer
through the pipeline after clearing backpressure:

```java
ctx.channel().eventLoop().execute(() -> {
    ctx.pipeline().fireChannelRead(Unpooled.EMPTY_BUFFER);
});
```

This triggers `ByteToMessageDecoder.channelRead()`, which merges the empty buffer with
the existing cumulation (no-op) and calls `callDecode()` — our `decode()` method runs
with `backpressured = false` and processes the stranded data. The event loop scheduling
ensures this runs on the correct thread.

**The I/O thread never blocks.** `deliver.apply(m)` is a non-blocking `offer()`. If it
fails, setting the flag and spawning a virtual thread both return immediately. The I/O
thread moves on to service other channels.

### ConvexLocal — Direct Blocking

For `ConvexLocal` (in-JVM, no network), there is no TCP and no Netty handler. The
calling thread is the client's application thread, so blocking is safe. `ConvexLocal`
calls `server.deliverMessage(m)`, and if it returns a retry predicate, calls it directly
on the caller's thread. No virtual thread needed.

## Connection Limiting

`NettyServer` maintains a `DefaultChannelGroup` tracking all active inbound channels.
This is purely a Netty-layer concern — `Server` is unaware.

- **Limit:** `Config.MAX_CLIENT_CONNECTIONS` (default 1024)
- **Enforcement:** `initChannel()` rejects and closes if limit reached
- **Cleanup:** `DefaultChannelGroup` auto-removes channels on close
- **Shutdown:** `clientChannels.close()` provides clean bulk teardown

### Memory Budget

Each client connection consumes approximately:

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

### Why Per-Channel, Not Global?

A global backpressure mechanism (e.g. pause all reads when any queue fills) would
penalise well-behaved clients for a single fast sender. Per-channel backpressure
isolates the effect: only the specific client that hit the full queue is throttled.

### Why Virtual Threads?

The Netty I/O thread must never block (Rule 1). The blocking retry needs *some* thread
to park on `queue.offer(timeout)`. Virtual threads are ideal: lightweight (~1 KB),
bounded by timeout, and don't consume a platform thread while parked.

### Why Pre-Allocated Predicates?

The retry predicates (`txnRetry`, `queryRetry`) are method references bound once at
server construction. Zero allocation on the reject path — important since rejects
happen precisely when the server is under load.

### Dependency Direction

`Message` is in `convex-core` and carries no Netty-specific references. The server
exposes `processMessage` as a pure `Function<Message, Predicate<Message>>`. The
handler orchestrates all channel-level backpressure. The server never sees a channel.

### Deadlock Safety

The Netty PR #6662 deadlock (both sides block on writes simultaneously) does not apply:

- Server write volume is tiny (Result messages, ~100 bytes each)
- Client's Netty event loop is never blocked (only the application thread waits)
- Client continues reading results even while the application thread is blocked
- Virtual threads hold no locks and don't write to channels

## Thread Safety

| Operation | Thread | Blocking? | Notes |
|-----------|--------|-----------|-------|
| `deliver.apply(m)` | Netty I/O | No | Non-blocking `offer()` |
| `backpressured = true` | Netty I/O | N/A | Volatile write |
| `setAutoRead(false)` | Netty I/O | No | Netty serialises internally |
| `retry.test(m)` | Virtual thread | Yes | Bounded by timeout |
| `backpressured = false` | Virtual thread | N/A | Volatile write, before `setAutoRead` |
| `setAutoRead(true)` | Virtual thread | No | Netty serialises internally |
| `fireChannelRead(EMPTY)` | Event loop (scheduled) | No | Flushes stranded cumulation data |

**Ordering:** The virtual thread sets `backpressured = false` before `setAutoRead(true)`.
When Netty resumes reading and calls `decode()`, the volatile read sees `false` and
decoding proceeds. The volatile write/read provides the necessary happens-before.

## Backward Compatibility

- `:LOAD` error only on timeout — existing retry logic still works but fires rarely
- Wire protocol unchanged
- API unchanged (`transact()` / `query()` still return `CompletableFuture<Result>`)
- Only observable change: calls may take longer to return under load (blocking instead
  of immediate error)

## Future Direction

### Per-Channel Max Message Size

The protocol allows messages up to `CPoSConstants.MAX_MESSAGE_LENGTH` (50 MB),
primarily for peer Belief messages. A client has no reason to send a 50 MB message —
transactions and queries are typically under 100 KB. A malicious client could force the
cumulation buffer to grow to 50 MB before the message is fully received.

**TODO:** Once peer/client channel classification is implemented, pass a smaller
`maxMessageLength` (e.g. 1 MB) to `NettyInboundHandler` for client channels. Peer
channels keep the 50 MB limit. The handler constructor already has the check point —
make the limit a parameter instead of the global constant.

### Peer Priority

Peer-to-peer Belief propagation should not be affected by client load. This requires
distinguishing peer channels from client channels — a prerequisite for both max message
size and priority scheduling. See [PEER_PRIORITY.md](PEER_PRIORITY.md).

## References

- [Netty #10254: Write Throttling / Back Pressure](https://github.com/netty/netty/issues/10254) — Norman Maurer's guidance
- [Netty PR #6662: FlowControlHandler](https://github.com/netty/netty/pull/6662) — rejected due to bidirectional deadlock risk
- [Netty #5970: Write watermark settings](https://github.com/netty/netty/issues/5970) — watermark tuning
- [Cassandra Backpressure](https://cassandra.apache.org/_/blog/Improving-Apache-Cassandras-Front-Door-and-Backpressure.html) — production `setAutoRead(false)` at scale
