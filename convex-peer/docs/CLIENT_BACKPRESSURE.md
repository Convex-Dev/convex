# Client-Side Backpressure

**See also:** [BACKPRESSURE.md](BACKPRESSURE.md) — server-side backpressure

## Overview

Each client connection holds a small bounded outbound queue. Application threads put
messages into the queue; the Netty event loop drains them to the channel when writable.
This caps per-connection memory and propagates TCP backpressure all the way to the
application thread — no unbounded buffering, no polling threads.

## AConnection Interface

Two send methods — blocking and non-blocking — with no transport-specific concepts:

- **`sendMessage(m)`** — blocks the calling (virtual) thread until space is available
  or timeout. Used by `ConvexRemote.message()` for normal operation.
- **`trySendMessage(m)`** — immediate return, false if queue full. Used when the
  caller wants to detect overload and handle it (e.g. drop, retry later).

The interface says nothing about TCP, Netty, writability, or event loops. Works with
any future transport.

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

The `ArrayBlockingQueue` is the only shared state. Thread-safe by design — no locks,
no volatile flags, no custom synchronisation.

### Drain Triggers

1. **`flushPending()`** — called after every successful `offer()`, schedules drain
   on the Netty event loop
2. **`channelWritabilityChanged()`** — fires when Netty's write buffer crosses the
   low watermark, resumes draining

### Disconnect Handling

When the channel closes, `channelInactive()` clears the outbound queue, waking any
threads blocked on `offer(timeout)`. They detect the closed channel and return `false`.

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
| `WriteBufferWaterMark` low | 32 KB | Client bootstrap |
| `WriteBufferWaterMark` high | 64 KB | Client bootstrap |
| Send timeout | `DEFAULT_CLIENT_TIMEOUT` (8s) | `Config` |

The outbound queue absorbs brief bursts — at ~1 KB per message average, 128 entries
is ~128 KB per connection. The watermark bounds how much data sits in Netty's buffer
before draining pauses. Together they cap total per-connection memory.

## Backward Compatibility

`ConvexRemote.message()` calls `sendMessage(m)` and checks the boolean. The contract
is the same — just properly enforced now. The only behavioural change: `sendMessage()`
may block briefly under load instead of always returning `true`.

## Future Direction

### Adaptive Queue Sizing

The fixed queue size of 128 works well for typical workloads. A future enhancement
could adapt the queue size based on observed throughput or message size distribution,
but the current fixed size is simple and effective.

### Transport Independence

The `AConnection` interface is deliberately transport-agnostic. Future transports
(QUIC, in-process, WebSocket) implement `sendMessage()` and `trySendMessage()` with
their own buffering strategy. Client code (`ConvexRemote`, `Convex`) is unaffected.
