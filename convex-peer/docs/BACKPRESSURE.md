# Backpressure Design for Convex Peer

## Problem Statement

When clients send messages faster than the peer can process them, queues fill up and
subsequent messages are immediately rejected with `:LOAD` errors. There is no
backpressure — the client is never told to slow down, it simply receives errors.

**Observed in stress testing:** 100,000 transactions submitted rapidly, ~80,000 receive
`:LOAD` errors because the 10,000-capacity `ArrayBlockingQueue` fills up and `offer()`
returns `false` immediately for the remainder.

This is undesirable because:

- Clients must implement retry logic (complex, wasteful)
- Throughput is artificially limited to queue capacity rather than processing capacity
- No flow control signal propagates back to the sender
- Load errors conflate "server genuinely overloaded" with "burst slightly exceeds buffer"

## Current Architecture

### Message Types and Processing Paths

The server dispatches inbound messages by type. Each type has its own processing path:

```
Netty I/O thread
      │
      ▼
Server.processMessage(m)
      │
      ├─ TRANSACT ──► processTransact() ──► txMessageQueue.offer()  ──► TransactionHandler
      │                                          10,000 capacity
      │
      ├─ QUERY ──────► processQuery() ───► queryQueue.offer()  ──────► QueryHandler
      ├─ DATA_REQUEST ► processQuery() ───► queryQueue.offer()  ──────► QueryHandler
      │                                          10,000 capacity
      │
      ├─ BELIEF ─────► processBelief() ──► beliefQueue.offer()  ─────► BeliefPropagator
      │                                          200 capacity
      │
      ├─ STATUS ─────► processStatus() ──► (inline response, no queue)
      │
      ├─ CHALLENGE ──► processChallenge() ► (inline, delegated to ConnectionManager)
      ├─ RESPONSE ───► processResponse() ─► (inline, delegated to ConnectionManager)
      └─ GOODBYE ────► processClose()
```

**Key observation:** a single client channel carries **all** message types. A client
sending transactions is likely also sending queries (e.g. checking balances) and
receiving status updates.

### Where Backpressure Is Missing

| Queue | Current Behaviour | Problem |
|-------|-------------------|---------|
| `txMessageQueue` | `offer()` — immediate reject when full | Client gets `:LOAD`, no flow control |
| `queryQueue` | `offer()` — immediate reject when full | Client gets `:LOAD`, no flow control |
| `beliefQueue` | `offer()` — logged warning when full | Peer Beliefs silently dropped |

All three queues use `ArrayBlockingQueue` with non-blocking `offer()`. The Netty I/O
thread must never block, so this is correct — but there is no mechanism to signal the
sender to slow down.

### Bug: QueryHandler Queue Size

In `QueryHandler` constructor (line 32), the field is reinitialised:

```java
queryQueue = new ArrayBlockingQueue<>(Config.TRANSACTION_QUEUE_SIZE);  // should be QUERY_QUEUE_SIZE
```

Both constants are currently 10,000, so no functional impact, but this should use
`QUERY_QUEUE_SIZE` for clarity.

## Netty Best Practices for Backpressure

Relevant guidance from Netty maintainers and production usage:

### Rule 1: Never Block the Event Loop

The Netty I/O thread (event loop) must never block. All backpressure must be achieved
through non-blocking mechanisms. (Norman Maurer, netty/netty#10254)

### Rule 2: Use `channelWritabilityChanged` for Write Throttling

> "You need to stop writing until the channel becomes writable again. You will be
> notified once the writability state of the Channel changes via the
> `ChannelInboundHandler.channelWritabilityChanged(...)` callback. How you propagate
> this through your application/framework is up to you."
>
> — Norman Maurer (Netty maintainer)

### Rule 3: Use `setAutoRead(false)` for Inbound Throttling

To stop accepting data from a socket, set `channel.config().setAutoRead(false)`. Netty
stops reading from the socket, the kernel receive buffer fills, TCP advertises a
smaller window, and the sender's TCP stack throttles. This is the standard mechanism
used by Apache Cassandra at scale.

### Rule 4: Beware of Bidirectional Deadlock

Netty PR #6662 (a generic `FlowControlHandler`) was **rejected** by Trustin Lee due to
deadlock risk: if both sides stop reading when their outbound buffer is full, neither
side can drain, and traffic stalls permanently.

This concern applies to **bidirectional heavy flows** (e.g. proxies). It does **not**
apply to our case — see Deadlock Analysis below.

## Design: Per-Channel Backpressure with Virtual Threads

### Approach

Each channel independently manages its own backpressure. When a message can't be
queued, the channel:

1. Stops decoding further messages from its buffer
2. Pauses its own socket reads (`setAutoRead(false)`)
3. Hands the message to a virtual thread that blocks until the queue accepts it
4. Resumes decoding and reads when the message is delivered (or timed out)

This is **local** — only the specific client that hit the full queue is throttled.
All other clients continue normally. No global channel tracking, no enumeration,
no watermarks. Message ordering is preserved per-channel.

### Dependency Direction

`Message` is in `convex-core` and must not carry Netty-specific references (like
`Channel`). Backpressure is a networking concern — it belongs in the Netty handler
layer, not in the server or message model.

The server exposes a single method returning `Predicate<Message>`:

```java
Predicate<Message> processMessage(Message m)
```

- Returns **null** → message accepted (fast path)
- Returns **non-null** → message rejected, the returned predicate is a blocking
  retry function (slow path)

The retry predicates are pre-allocated as fields — **zero allocation** on the
reject path:

```java
// In Server — created once at construction
private final Predicate<Message> txnRetry =
    transactionHandler::offerTransactionBlocking;
private final Predicate<Message> queryRetry =
    queryHandler::offerQueryBlocking;
```

The handler receives `processMessage` as a `Function<Message, Predicate<Message>>`
and orchestrates backpressure using the return value. The server never sees a channel.

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

### Server-Side: Single Dispatch

One `processMessage` method handles both the non-blocking offer and returning the
appropriate retry predicate. No duplicate dispatch:

```java
// Pre-allocated retry predicates — zero allocation on reject path
private final Predicate<Message> txnRetry =
    transactionHandler::offerTransactionBlocking;
private final Predicate<Message> queryRetry =
    queryHandler::offerQueryBlocking;

/**
 * Process an inbound message. Returns null if accepted, or a blocking
 * retry predicate if the message could not be queued.
 */
public Predicate<Message> processMessage(Message m) {
    try {
        MessageType type = m.getType();
        switch (type) {
            case TRANSACT:
                if (transactionHandler.offerTransaction(m)) return null;
                return txnRetry;

            case QUERY: case DATA_REQUEST:
                if (queryHandler.offerQuery(m)) return null;
                return queryRetry;

            // Protocol messages — always accepted, handled inline
            case BELIEF:    processBelief(m);    return null;
            case STATUS:    processStatus(m);    return null;
            case CHALLENGE: processChallenge(m); return null;
            case RESPONSE:  processResponse(m);  return null;
            case GOODBYE:   processClose(m);     return null;
            default: return null;
        }
    } catch (MissingDataException e) {
        m.returnResult(Result.error(ErrorCodes.MISSING,
            Strings.create("Missing data: " + e.getMissingHash()))
            .withSource(SourceCodes.PEER));
        return null;
    } catch (Exception e) {
        log.warn("Unexpected error processing message", e);
        return null;
    }
}
```

The blocking retry methods use `queue.offer(m, timeout, MILLISECONDS)` internally.
The timeout (`Config.DEFAULT_CLIENT_TIMEOUT`, 8s) is the server's concern — the
handler doesn't need to know or pass it:

```java
// In TransactionHandler
public boolean offerTransactionBlocking(Message m) {
    try {
        return txMessageQueue.offer(m, Config.DEFAULT_CLIENT_TIMEOUT,
                                    TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }
}

// In QueryHandler
public boolean offerQueryBlocking(Message m) {
    try {
        return queryQueue.offer(m, Config.DEFAULT_CLIENT_TIMEOUT,
                                TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }
}
```

### Handler-Side: Backpressure Orchestration

The `NettyInboundHandler` owns the backpressure flow. It has a per-handler
`backpressured` flag that gates decoding — when true, `decode()` returns without
consuming bytes, which stops the `ByteToMessageDecoder` loop. This ensures:

- **At most one message** in the retry path per channel at a time
- **Message ordering preserved** — remaining bytes stay in the buffer and are
  decoded in order after the retry completes
- **Only this channel is affected** — each handler is per-connection, other
  clients' handlers are independent

```java
class NettyInboundHandler extends ByteToMessageDecoder {

    private final Function<Message, Predicate<Message>> deliver;
    private final Predicate<Message> returnAction;
    private volatile boolean backpressured = false;

    NettyInboundHandler(Function<Message, Predicate<Message>> deliver,
                        Predicate<Message> returnAction) {
        this.deliver = deliver;
        this.returnAction = returnAction;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Stop decoding while a message is being retried on a virtual thread.
        // ByteToMessageDecoder sees no progress and stops its loop.
        // Remaining bytes stay in the cumulation buffer, preserving order.
        if (backpressured) return;

        // ... parse message bytes from ByteBuf ...
        Message m = Message.create(returnAction, null, Blob.wrap(messageData));

        Predicate<Message> retry = deliver.apply(m);
        if (retry != null) {
            // Queue full — pause this channel, retry on virtual thread
            backpressured = true;
            ctx.channel().config().setAutoRead(false);

            Thread.startVirtualThread(() -> {
                try {
                    boolean delivered = retry.test(m);
                    if (!delivered) {
                        // Timeout — :LOAD error
                        Result r = Result.create(m.getID(),
                            Strings.SERVER_LOADED, ErrorCodes.LOAD)
                            .withSource(SourceCodes.PEER);
                        m.returnResult(r);
                    }
                } finally {
                    // Always resume. If the next message also can't be
                    // queued, it triggers another cycle.
                    backpressured = false;
                    ctx.channel().config().setAutoRead(true);
                }
            });
        }
    }
}
```

The flow:

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
                    remaining bytes decoded in order
```

**The I/O thread never blocks.** `deliver.apply(m)` is a non-blocking `offer()`.
If it fails, setting the flag and spawning a virtual thread both return immediately.
The I/O thread moves on to service other channels.

### Wiring in NettyServer

```java
// In NettyServer.initChannel()
@Override
public void initChannel(SocketChannel ch) {
    Predicate<Message> returnHandler = m -> {
        ch.writeAndFlush(m);
        return true;
    };
    Function<Message, Predicate<Message>> deliver = server::processMessage;

    NettyInboundHandler inbound = new NettyInboundHandler(deliver, returnHandler);
    ch.pipeline().addLast(inbound, new NettyOutboundHandler());
}
```

No changes to `Message`, `convex-core`, or the wire protocol.

### Message Types Not Subject to Backpressure

| Type | Reason |
|------|--------|
| BELIEF | Peer protocol — must never be blocked (small queue, 200, peers only) |
| STATUS | Inline on I/O thread — tiny response, no queue |
| CHALLENGE / RESPONSE | Authentication protocol — must complete promptly |
| GOODBYE | Connection teardown — must not be delayed |

These always return `null` from `processMessage` — they are handled inline or use
dedicated queues that don't fill under normal operation. The backpressure path
is never triggered for them.

### ConvexLocal — Direct Blocking

For `ConvexLocal` (in-JVM, no network), there is no TCP and no Netty handler. The
calling thread is the client's application thread, so blocking is safe.

`ConvexLocal` calls `processMessage()`, and if it returns a retry predicate,
calls it directly on the caller's thread. No virtual thread needed:

```java
// In ConvexLocal message path
Predicate<Message> retry = server.processMessage(m);
if (retry != null) {
    // Block caller's thread — this IS the application thread
    if (!retry.test(m)) {
        Result r = Result.create(m.getID(), Strings.SERVER_LOADED, ErrorCodes.LOAD)
            .withSource(SourceCodes.PEER);
        m.returnResult(r);
    }
}
```

### Client-Side: Check Writability Before Sending

When the server pauses reads on a channel, the client's TCP send buffer fills and the
Netty channel becomes **unwritable**. The client should detect this and wait.

**Important:** the waiting must happen on the **application thread** (the thread
calling `transact()` or `query()`), never on the Netty event loop thread.
`sendMessage()` is called from `ConvexRemote.message()` which is called from
application code — this is safe.

```java
// In NettyConnection
private final Object writabilityLock = new Object();

@Override
public boolean sendMessage(Message m) {
    if (!channel.isActive()) return false;

    // Wait if channel is not writable (TCP backpressure from server).
    // Blocks the APPLICATION thread, not the Netty event loop.
    if (!channel.isWritable()) {
        try {
            synchronized (writabilityLock) {
                while (!channel.isWritable() && channel.isActive()) {
                    writabilityLock.wait(100); // periodic check
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!channel.isActive()) return false;
    }

    channel.writeAndFlush(m);
    return true;
}
```

This applies to all message types sent by the client — transactions, queries, and
status requests alike. The backpressure signal is channel-level, so the client
naturally throttles everything.

**Writability change handler — fires on the Netty event loop, must not block:**

```java
@Override
public void channelWritabilityChanged(ChannelHandlerContext ctx) {
    if (ctx.channel().isWritable()) {
        synchronized (writabilityLock) {
            writabilityLock.notifyAll();  // wake application threads
        }
    }
    ctx.fireChannelWritabilityChanged();
}
```

**Configure write buffer watermarks on the client bootstrap:**

```java
b.option(ChannelOption.WRITE_BUFFER_WATER_MARK,
    new WriteBufferWaterMark(32 * 1024, 64 * 1024));
```

The defaults (32KB low / 64KB high) are well-tested and recommended by Netty.

## Progress Guarantee

Backpressure is safe as long as the system is **making progress** — queues are
actively draining. Both handler threads continuously drain their respective queues:

- `TransactionHandler.loop()` drains `txMessageQueue` → CVM execution
- `QueryHandler.loop()` drains `queryQueue` → query execution

The virtual thread blocks on `offer(m, timeout, MILLISECONDS)`. As long as the
handler is draining, a slot opens and the offer succeeds. The timeout fires when
the server can't keep up with this client's demand — the server is still healthy
and processing, but this particular client is asking for more than is available.

The timeout aligns with `Config.DEFAULT_CLIENT_TIMEOUT` (8s) — the same duration
the client would wait before giving up anyway.

## Thread Safety Analysis

| Operation | Thread | Blocking OK? | Notes |
|-----------|--------|-------------|-------|
| `deliver.apply(m)` | Netty I/O thread | No — must not block | Non-blocking `offer()` |
| `backpressured = true` | Netty I/O thread | N/A | Volatile write |
| `setAutoRead(false)` | Netty I/O thread | N/A (non-blocking) | Netty serialises internally |
| `retry.test(m)` | Virtual thread | Yes | Lightweight, bounded by timeout |
| `backpressured = false` | Virtual thread | N/A | Volatile write, before setAutoRead |
| `setAutoRead(true)` | Virtual thread | N/A (non-blocking) | Netty serialises internally |
| `backpressured` check | Netty I/O thread | N/A | Volatile read at top of decode() |
| `sendMessage()` wait loop | Application thread | Yes | Never runs on event loop |
| `channelWritabilityChanged()` | Netty event loop | No — must not block | Only does `notifyAll()` |
| Local blocking retry | ConvexLocal caller | Yes | Client's own thread |

**Ordering of `backpressured` and `setAutoRead`:** The virtual thread sets
`backpressured = false` before `setAutoRead(true)`. When Netty resumes reading
and calls `decode()`, the volatile read of `backpressured` sees `false`, and
decoding proceeds normally. The volatile write/read provides the necessary
happens-before relationship.

## Deadlock Analysis

The Netty PR #6662 deadlock requires both sides to be simultaneously blocked on writes:

```
Deadlock scenario (DOES NOT APPLY):
  Client write buffer full  ──►  client stops writing
  Server write buffer full  ──►  server stops reading
  Neither side drains       ──►  permanent stall
```

In our case:
- **Server write volume is tiny** (Result messages, ~100 bytes each)
- **Client's Netty event loop is never blocked** (only the application thread waits)
- **Client continues reading** results even while the application thread is blocked
- Server's outbound buffer never fills because responses drain continuously

Therefore the bidirectional deadlock cannot occur.

**Virtual thread deadlock?** The virtual thread blocks on `queue.offer(timeout)`.
It does not hold any locks. It does not write to the channel. It cannot cause the
bidirectional stall described above.

## Connection Limiting

### ChannelGroup in NettyServer

`NettyServer` maintains a `DefaultChannelGroup` to track all active inbound channels.
This is purely a Netty-layer concern — `Server` never sees or interacts with the group.

`DefaultChannelGroup` automatically removes channels when they close (via
`ChannelFutureListener`), so there is no manual cleanup on disconnect.

```java
// In NettyServer
private final ChannelGroup clientChannels =
    new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
```

### Maximum Connections

A configurable limit prevents resource exhaustion from connection storms or
misbehaving clients opening thousands of sockets:

```java
// In Config
public static final int MAX_CLIENT_CONNECTIONS = 1024;
```

Enforcement happens in `initChannel()` — the earliest point where we can reject:

```java
@Override
public void initChannel(SocketChannel ch) throws Exception {
    if (clientChannels.size() >= Config.MAX_CLIENT_CONNECTIONS) {
        log.warn("Connection limit reached ({}), rejecting {}",
            Config.MAX_CLIENT_CONNECTIONS, ch.remoteAddress());
        ch.close();
        return;
    }
    clientChannels.add(ch);

    // ... rest of pipeline setup ...
}
```

The channel is added to the group **after** the limit check. If the channel later
closes (client disconnect, timeout, error), `DefaultChannelGroup` removes it
automatically.

### Server Shutdown

On server close, the group provides clean bulk teardown:

```java
@Override
public void close() {
    clientChannels.close();   // close all client channels
    if (channel != null) {
        channel.close();      // close the server channel
    }
}
```

### Why Not in Server?

The `Server` class deals in `Message` objects and processing logic. It has no
reason to know about connection counts, channel lifecycle, or Netty types.
The `NettyServer` already owns the channel pipeline — connection limiting is
a natural extension of that responsibility.

### Design Rationale

| Concern | Location | Rationale |
|---------|----------|-----------|
| Connection limit | `NettyServer.initChannel()` | Earliest rejection point, before pipeline setup |
| Channel tracking | `DefaultChannelGroup` | Auto-cleanup, thread-safe, zero maintenance |
| Limit constant | `Config.MAX_CLIENT_CONNECTIONS` | Configurable per deployment |
| Bulk close | `NettyServer.close()` | Clean shutdown of all connections |
| Server class | Unaware | Clean dependency direction |

### Memory Budget

Each client connection consumes approximately:

| Component | Memory | Notes |
|-----------|--------|-------|
| Netty `SocketChannel` + pipeline | ~4 KB | Channel object, handler contexts |
| Read/write `ByteBuf` | 2–64 KB | Adaptive allocator, grows under load |
| Kernel socket buffers | ~128 KB | `SO_RCVBUF` (64 KB) + `SO_SNDBUF` (64 KB), outside JVM heap |
| Virtual thread (backpressured only) | ~1 KB | Transient — only exists during retry |

**Per connection: ~200 KB idle, ~300 KB under load.**

At the default limit of 1024 connections: ~200–300 MB total, with most of that
in kernel socket buffers (outside JVM heap). This is comfortable for a server
with a few GB of heap. Operators can adjust `MAX_CLIENT_CONNECTIONS` for
high-traffic deployments or constrained environments.

## Future: Per-Channel Max Message Size

The protocol allows messages up to `CPoSConstants.MAX_MESSAGE_LENGTH` (50 MB),
primarily for peer Belief messages. The decoder already checks this at line 79
of `NettyInboundHandler` — but it applies the same 50 MB limit to all connections.

A client has no reason to send a 50 MB message. Transactions and queries are
typically under 100 KB. A malicious client could force the cumulation buffer to
grow to 50 MB before the message is even fully received (the decoder waits at
line 86 until `readableBytes() >= mlen`).

**TODO:** Once peer/client channel classification is implemented (see
[PEER_PRIORITY.md](PEER_PRIORITY.md)), pass a smaller `maxMessageLength` (e.g.
1 MB) to `NettyInboundHandler` for client channels. Peer channels keep the
50 MB limit. The handler constructor already has the check point — just make
the limit a parameter instead of using the global constant.

## Backward Compatibility

- `:LOAD` error only on timeout — existing retry logic still works but fires rarely
- Wire protocol is unchanged
- API is unchanged (`transact()` / `query()` still return `CompletableFuture<Result>`)
- The only observable change: calls may take longer to return under load
  (blocking instead of immediate error)
- Clients that don't check writability (e.g. third-party tools) still work — their
  TCP stack handles flow control transparently

## Implementation Sequence

### Phase 1: Server Delivery Method

1. Change `processMessage` to return `Predicate<Message>` (null = accepted)
2. Add pre-allocated `txnRetry` and `queryRetry` predicate fields
3. Add `offerTransactionBlocking(Message)` to `TransactionHandler`
4. Add `offerQueryBlocking(Message)` to `QueryHandler`
5. Fix `QueryHandler` constructor to use `QUERY_QUEUE_SIZE`

### Phase 2: Connection Limiting and Channel Tracking

1. Add `DefaultChannelGroup` to `NettyServer`
2. Add `MAX_CLIENT_CONNECTIONS` to `Config` (default 1024)
3. Check limit in `initChannel()` — reject and close if full
4. Add `clientChannels.add(ch)` after limit check
5. Add `clientChannels.close()` to `NettyServer.close()`

### Phase 3: Handler Backpressure

1. Add `backpressured` flag and `deliver` function to `NettyInboundHandler`
2. Gate `decode()` on `backpressured` — return without consuming if true
3. On reject: set flag, `setAutoRead(false)`, spawn virtual thread
4. Virtual thread: `retry.test(m)` → on failure return `:LOAD` →
   `finally` clear flag + `setAutoRead(true)`
5. Update `NettyServer.initChannel()` to wire `server::processMessage`

### Phase 4: ConvexLocal Path

1. Update local message delivery to call `processMessage`, then call
   returned predicate directly on the caller's thread if non-null

### Phase 5: Client-Side Writability Check

1. Add `WriteBufferWaterMark` to client bootstrap (32KB/64KB)
2. Add `channelWritabilityChanged()` handler to client pipeline
3. Add `writabilityLock` + wait loop to `NettyConnection.sendMessage()`

## References

- [Netty #10254: Implementing Write Throttling / Back Pressure](https://github.com/netty/netty/issues/10254) — Norman Maurer's guidance
- [Netty PR #6662: FlowControlHandler](https://github.com/netty/netty/pull/6662) — rejected due to bidirectional deadlock risk
- [Netty #5970: Write watermark settings](https://github.com/netty/netty/issues/5970) — watermark tuning discussion
- [Cassandra Backpressure](https://cassandra.apache.org/_/blog/Improving-Apache-Cassandras-Front-Door-and-Backpressure.html) — production `setAutoRead(false)` usage at scale

## Summary

| Aspect | Design |
|--------|--------|
| Backpressure scope | Per-channel, per-handler instance |
| Fast path | `deliver.apply(m)` returns null — zero allocation, non-blocking |
| Slow path | Returns pre-allocated retry `Predicate` — virtual thread blocks |
| Ordering | `backpressured` flag stops decode loop, remaining bytes stay in buffer |
| `:LOAD` | Only on timeout (`DEFAULT_CLIENT_TIMEOUT`) — server healthy but client exceeds capacity |
| Message model | Unchanged — no Channel reference, no Netty dependency in `convex-core` |
| Server API | Single `processMessage` method, no duplicate dispatch |
| Allocation | Zero on reject (pre-allocated predicates), virtual thread on slow path only |
| Protocol messages | BELIEF, STATUS, CHALLENGE, RESPONSE, GOODBYE — always accepted inline |
| Local path | `ConvexLocal` calls retry predicate on caller's thread — no virtual thread |
| Connection limit | `DefaultChannelGroup` in `NettyServer`, max 1024, reject in `initChannel()` |
| Deadlock | Not applicable — asymmetric flow, virtual threads hold no locks |
