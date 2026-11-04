# Client-Side Backpressure

**See also:** [BACKPRESSURE.md](BACKPRESSURE.md) — server-side backpressure

## Overview

Each client connection holds a small bounded outbound queue. Application threads put
messages into the queue; the Netty event loop drains them to the channel when writable.
This caps per-connection memory and propagates TCP backpressure all the way to the
application thread — no unbounded buffering, no polling threads.

## AConnection Interface

Two send methods — blocking and non-blocking — with no transport-specific concepts:

- **`sendMessage(m)`** — blocks the calling (virtual) thread until space is available,
  and returns false if none frees within the send timeout. On a Netty event loop it
  does not wait: the loops empty every connection's queue, so a full queue returns
  false at once. A message that cannot be sent for any other reason throws:
  `ClosedChannelException` if the connection is closed, another `IOException` if the
  write fails, `InterruptedException` if the wait is interrupted, and
  `IllegalArgumentException` if the message is too large for one frame. Used by
  requests and `Convex.message()`.
- **`trySendMessage(m)`** — immediate return, false if the message cannot be queued
  for any reason. Used when the caller wants to detect overload and handle it (e.g.
  drop, retry later), such as a broadcast to many peers.

The interface says nothing about TCP, Netty, writability, or event loops. Works with
any future transport.

A client reports each outcome of a send as a Result, since its calls always return
one:

| Send outcome | Client result |
|---|---|
| `false` | `FULL_CLIENT_BUFFER` (`:LOAD`) |
| `ClosedChannelException` | `CLOSED_CONNECTION` (`:CONNECT`), also for requests still awaiting a reply when the connection closes |
| other `IOException` | `:IO` |
| `InterruptedException` | `:INTERRUPTED`; the calling thread keeps its interrupt status |
| `IllegalArgumentException` | none: the call fails with the exception, as for any invalid request |

## Design

```
Application thread              Netty event loop
      │                              │
  sendMessage(m)                     │
      │                              │
  outbound.offer(m, timeout)         │
      │  (blocks if queue full)      │
      │                              │
  flushPending() ──────────────►  doFlush()
      │                              │  while writable:
      │                              │    poll() → writeAndFlush()
      │                              │
      │                         channelWritabilityChanged()
      │                              │
      │                         ──► doFlush()
      │                              │  poll() frees slot in queue
      │                              │
  (blocked offer wakes) ◄───────────┘
```

### Thread Responsibilities

| Thread | Does | Touches |
|--------|------|---------|
| Application (virtual) thread | `outbound.offer()` | Queue only — never touches channel |
| Netty event loop | `outbound.poll()`, `writeAndFlush()` | Queue and channel |

The `BoundedMessageQueue` is the only state the two threads share.

### Drain Triggers

1. **`flushPending()`** — called after every successful `offer()`, schedules drain
   on the Netty event loop
2. **`channelWritabilityChanged()`** — fires when Netty's write buffer crosses the
   low watermark, resumes draining

### Disconnect Handling

When the channel closes, `channelInactive()` clears the outbound queue, waking any
threads blocked on `offer(timeout)`. They detect the closed channel and throw
`ClosedChannelException`.

## End-to-End Backpressure Chain

When the server pauses reads (see [BACKPRESSURE.md](BACKPRESSURE.md)):

```
Server pauses reads
      ↓
Kernel receive buffer fills on server
      ↓
TCP window shrinks → server advertises zero window
      ↓
Client kernel send buffer fills (can't push bytes)
      ↓
Netty can't flush to kernel → write buffer grows
      ↓
Write buffer exceeds high watermark → isWritable() = false
      ↓
doFlush() stops polling from outbound queue
      ↓
Outbound queue fills up
      ↓
sendMessage() blocks on offer(timeout) ← backpressure reaches application
```

No polling, no extra threads, no unbounded buffering.

## Configuration

| Parameter | Value | Location |
|-----------|-------|----------|
| `OUTBOUND_QUEUE_SIZE` | 128 | `Config` |
| `OUTBOUND_QUEUE_BYTE_LIMIT` | 16 MiB | `Config` |
| `PEER_OUTBOUND_QUEUE_SIZE` / `PEER_OUTBOUND_QUEUE_BYTE_LIMIT` | 65,536 / 256 MB, connections to peers only | `Config` |
| `WriteBufferWaterMark` low | 32 KB | Client bootstrap |
| `WriteBufferWaterMark` high | 64 KB | Client bootstrap |
| Send timeout | `DEFAULT_INTERNAL_TIMEOUT` (8 s) | `Config` |

The outbound queue absorbs brief bursts — at ~1 KB per message average, 128 entries
is ~128 KB per connection. The watermark bounds how much data sits in Netty's buffer
before draining pauses. Together they cap total per-connection memory.

## Future Direction

### Adaptive Queue Sizing

The fixed queue size of 128 works well for typical workloads. A future enhancement
could adapt the queue size based on observed throughput or message size distribution,
but the current fixed size is simple and effective.

### Transport Independence

The `AConnection` interface is deliberately transport-agnostic. Future transports
(QUIC, in-process, WebSocket) implement `sendMessage()` and `trySendMessage()` with
their own buffering strategy. Client code (`ConvexRemote`, `Convex`) is unaffected.
