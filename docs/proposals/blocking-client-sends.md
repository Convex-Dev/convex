# Client sends that block until queued

- Status: Proposed
- Last updated: 2026-11-05
- Specification: [CAD015](https://docs.convex.world/docs/cad/peercomms) makes messaging
  asynchronous and matches results to pending request IDs;
  [CAD011](https://docs.convex.world/docs/cad/errors) defines the error sources used here:
  `:CLIENT` for client library code and `:COMM` for client–server communication. Nothing here
  changes the wire protocol or a CAD.
- Current design: [CLIENT_BACKPRESSURE.md](../../convex-peer/docs/CLIENT_BACKPRESSURE.md),
  [BACKPRESSURE.md](../../convex-peer/docs/BACKPRESSURE.md),
  [MESSAGING.md](../../convex-peer/docs/MESSAGING.md).

## Summary

A client request blocks its caller until its message is on the connection's send queue, or until
it is certain it never will be. That blocking is the client's backpressure: when the peer reads
slowly, the queue fills and callers slow to match. One deadline per request, counted from the call,
bounds both the wait for room and the wait for the reply.

Putting a message on the send queue commits it. A request that fails names its cause and shows
whether it was sent: by an exception subclass for code that uses a connection directly, and by the
Result's source for client users.

## Why

- `request()` blocks for room for up to a fixed 8 s (`Config.DEFAULT_INTERNAL_TIMEOUT`), unrelated
  to the request's own timeout (`Config.DEFAULT_CLIENT_TIMEOUT`, 20 s).
- The reply timer starts before that wait. A request can time out while its caller is still blocked,
  then be queued and sent anyway, and `:TIMEOUT` cannot say whether anything was sent.
- Closing a connection empties its send queue instead of closing it. A caller blocked for room
  wakes, queues its message and is told it was queued while the channel closes behind it. Close also
  fails requests not yet queued as though they had been.
- The client keeps a transaction's sequence number as soon as it signs it, even if the message is
  never sent. The next transaction then fails with `:SEQUENCE`.
- `transact` returns before queuing when the sequence number must be looked up, a pre-compile is
  pending or an earlier transaction is still waiting, and sends later from a virtual thread. Its
  return then says nothing about whether the transaction was queued.
- Callers' callbacks run on the thread that completes the future: the Netty event loop for a reply,
  the JDK's single shared timer thread for a timeout. A callback that blocks there stalls every
  connection that thread serves. `transact` holds the client's monitor across its blocking send, so
  a reply callback on the event loop that calls `transact` meanwhile blocks the loop that would free
  the room.
- `querySync` and `requestStatusSync` start their own timer only after the blocking send returns:
  `requestStatusSync(2000)` can take about 10 s. `transactSync` subtracts the time the send took,
  but can still time out with the message never sent.

## Parts

| Part | Role | Class |
|---|---|---|
| Request | One correlated exchange: its deadline, its state (waiting for room or queued) and its future | `AConnection` (`RequestFuture`) |
| Send queue | Committed messages, emptied only by the transport | `NettyConnection` outbound `BoundedMessageQueue` (128 messages or 16 MiB for a client) |
| Transport thread | Writes the send queue to the socket; reads replies and matches them to requests | Netty event loop; the NIO client's selector thread |
| Client | Turns outcomes into Results; sequences transactions | `Convex`, `AConvexConnected`, `ConvexRemote`, `ConvexLocal` |
| Server | Pauses reading, queues work, sheds load with `:LOAD` | Unchanged ([BACKPRESSURE.md](../../convex-peer/docs/BACKPRESSURE.md)) |

## Rules

### Commit

A message is committed when it goes onto the connection's send queue. Before that, a request can be
dropped: its deadline passes, the connection closes or its thread is interrupted. After it, the
message is sent unless the connection closes first, and a deadline or a cancel only stops the wait
for the reply. Nothing but the transport takes messages from the send queue; closing the connection
discards what is left in it.

### Blocking until queued

Every send blocks its calling thread until its message is queued, or until it is certain it never
will be. The caller's own thread makes the offer, bounded by the time left before its deadline, so no
other thread can queue its message and a request whose deadline has passed is never queued.

This holds for every case of `transact`. When the sequence number must first be looked up, a
pre-compile is pending or an earlier transaction from the same client is still waiting, the call
waits for those too, within its deadline. When `transact` returns, its transaction is queued or has
failed.

The transport never waits on its own thread: a send made there finds room or is refused at once, as
today. With replies handed off (below), only code that uses a connection directly runs there.

Closing a connection closes its send queue, which refuses offers and wakes every caller waiting for
room. Each request records whether it was queued, so close fails one that was not as never sent.

### Deadline

Each request has one deadline, counted from the call, covering the wait for room and the reply. The
deadline is a parameter of every send, down to the queue offer; a call without one uses the client's
timeout (`setTimeout`). The `*Sync` methods pass their own timeout, so it bounds the whole call.

The reply timer starts once the message is queued, for the time left. A request queued with no time
left has timed out: no time left is never read as no deadline. A timeout of zero means no deadline
(see [Decisions](#decisions)).

### Replies off the transport thread

When a reply arrives over the network, the transport thread matches it to its request and hands the
completion to a virtual thread instead of completing it itself. Decoding the reply and every callback
the caller attached then run on that virtual thread, which may block, so a callback can call the
client like any other caller. A request's deadline works the same way: the timer completes the
request on a virtual thread, not on the JDK's shared timer thread.

This is a hand-off at the edge between network I/O and application code: one virtual thread per
reply or timeout, living while the callbacks run. It awaits confirmation (decision 1).

In-JVM clients need no hand-off. `TransactionHandler` already delivers each block's results on a
virtual thread of its own, `ConvexLocal` runs each query on its own virtual thread, a status request
is answered on the caller's thread, and `ConvexDirect` runs everything on the caller's thread. A
block's results go to its clients in order on one virtual thread, so a callback that blocks there
delays the results for later clients in that block.

Callbacks for one connection's replies, which today run one at a time and in order on its event
loop, can then run at the same time. Code that makes its own requests through `Convex` or
`AConnection` must not rely on that order: `ConnectionManager`, `LatticeConnectionManager`,
`LatticeProtocolEndpoint`, `LatticeInboundVerifier`, `P2PNode` and `Server`.

### Outcomes

A connection (`AConnection.request`, `sendRequest`) reports:

| What ended the request | Sent? | The future |
|---|---|---|
| A reply | Yes | Completes with the reply |
| Deadline, before queued | No | Fails with `SendTimeoutException` |
| Deadline, after queued | Unknown | Fails with `TimeoutException` |
| Close, before queued | No | Fails with `ClosedBeforeSendException` |
| Close, after queued | Unknown | Fails with `ClosedChannelException` |
| Sent from a thread that may not wait, queue full | No | Fails with `RejectedExecutionException` |
| Interrupt while waiting for room | No | Fails with `InterruptedException`; the thread keeps its status |
| Message too large for one frame | No | Fails with `IllegalArgumentException` |

Each exception names its cause with the JDK's type for it. Whether the request was sent is how far
it got, not a cause, so a subclass marks the cases that end before the message was queued, as the
JDK's `HttpConnectTimeoutException` marks an HTTP request that timed out before it was sent:

- `SendTimeoutException` extends `java.util.concurrent.TimeoutException`.
- `ClosedBeforeSendException` extends `java.nio.channels.ClosedChannelException`.

Code that catches the parent type still works, and the mark travels with the exception through any
chain of futures.

A client (`Convex`) completes its futures with a Result, except for a message too large for one
frame, which fails the future. The error code names the cause and the source says how far the
request got:

| Source | Meaning | For the caller |
|---|---|---|
| `:CLIENT` | Failed in the client; nothing was sent | Safe to retry; no sequence number was used |
| `:COMM` | Sent, then the reply was lost to a timeout or a close | Outcome unknown |
| `:PEER`, `:CVM`, `:CODE` | The peer answered | The answer |

So `:TIMEOUT` from `:CLIENT` timed out waiting to send, and from `:COMM` waiting for the reply.
`FULL_CLIENT_BUFFER` (`:LOAD`) has source `:CLIENT`. A close gives `CLOSED_CONNECTION` (`:CONNECT`)
from `:CLIENT` before the request was queued and from `:COMM` after. `AConvexConnected.failureResult`
chooses the source from whether the request was queued, which the client knows for itself.

### Transaction sequence numbers

The client's cached sequence number advances only when a transaction it signed is queued. It signs
with the next number while holding its lock and keeps the number only if its own offer to the queue
succeeds. It decides from that offer, never from a Result's source, which a peer can set to anything
in its reply. Later transactions wait behind it, so transactions reach the send queue, and the peer,
in sequence order, and the client leaves no gaps of its own making.

`prepareTransaction` reserves the number it hands out: a caller that asks for a signed transaction
takes charge of its number. An application that prepares transactions separately manages its own
sequence accounting, and calls `clearSequence()` for a transaction it does not submit.

A signed transaction submitted with `transact(SignedData)` whose origin is the client's address
advances the cached number once it is queued. `Convex` does this for every kind of client.

A transaction can still fail to run after it is queued: the connection closes before it is written,
the peer sheds it (`SERVER_LOADED`), or the peer rejects it before consensus (`:FUNDS`,
`:SIGNATURE`). The peer has not used its number, so the next transaction fails with `:SEQUENCE`, and
on `:SEQUENCE` the client forgets its cached number and looks it up again. A caller that needs each
outcome before sending the next uses `transactSync`; a caller that pipelines transactions handles
`:TIMEOUT` and `:SEQUENCE`.

## The caller's view

A synchronous caller's code is unchanged, and its timeout bounds the whole call:

```java
convex.setTimeout(5000);
Result r = convex.transactSync("(def x 1)");  // about 5 s at most, waiting for room included
```

A pipelining caller slows to the rate the peer reads:

```java
for (ATransaction tx : batch) results.add(convex.transact(tx));  // blocks while the queue is full
for (var f : results) { Result r = f.join(); /* handle :TIMEOUT and :SEQUENCE */ }
```

A callback runs on a thread that may block, so it can call the client directly:

```java
convex.query(q).thenCompose(r -> convex.transact(next(r)));
```

## Who sees a change

- **Synchronous callers:** their timeouts bound the whole call, and a result says whether the request
  was sent.
- **Asynchronous callers:** `transact` also waits, within its deadline, for a sequence lookup, a
  pre-compile or an earlier transaction. Callbacks run on a virtual thread instead of the event loop
  or the JDK's timer thread.
- **Callers on threads that should stay responsive:** once the fixed 8 s goes, the longest wait for
  room is the request's deadline, 20 s by default. GUI code that calls the client on the Swing event
  thread (`REPLPanel`, `TokenInfoPanel`, `AccountChooserPanel`, `TransferPanel`) can freeze for that
  long when a connection is backed up.
- **Transactions:** one that is never sent no longer makes the next fail with `:SEQUENCE`.
- **Result sources:** failures before sending report `:CLIENT` instead of `:COMM`. Nothing in the
  main code checks for `:COMM`. The REST API picks its HTTP status from the error code and reads the
  source only for `:CVM`, `:CODE` and `:PEER`.
- **Load tools** that rely on being slowed keep being slowed: the GUI stress panel,
  `LatencyBenchmark`, `ServerTest.testServerFlood` and the `TransactionThroughput` example.

## API changes

- **Public signatures:** none change.
- **New public types:** `SendTimeoutException` and `ClosedBeforeSendException` in
  `convex.core.exceptions`.
- **`AConnection.sendMessage(Message, long timeoutMillis)`** replaces `sendMessage(Message)`. Its only
  caller is `AConvexConnected.send`, for one-way `Convex.message`. Server-side connections never wait
  and ignore the deadline.
- **Protected on `Convex`:** `transact(SignedData, long)`, `query(ACell, Address, long)` and
  `requestStatus(long)`. `ConvexRemote`, `ConvexLocal`, `ConvexDirect` and `ConvexHTTP` implement
  these instead of today's public methods, which keep their signatures and pass the client's timeout.
  The `*Sync` methods call them with their own timeout, as the protected
  `querySync(ACell, Address, long)` already does.
- **Internal:** the request's state and deadline live in `AConnection`, with a way for a transport to
  complete a request on a virtual thread.
- **Contracts:**
  - `AConnection.request` and `sendRequest` block until queued and report the outcomes above.
  - `Convex.transact` returns once its transaction is queued or has failed. It loses "may block
    briefly if the send buffer is full" and the withdrawal on cancel, since its future is returned
    only once the transaction is queued.
  - The `Convex` class javadoc says callbacks run on a thread that may block, replacing its advice to
    chain with `thenCompose` and hand waiting work to an executor.
  - The `*Sync` methods' timeouts bound the whole call.
  - `prepareTransaction` states that it reserves the number it hands out.

## Other transports

- **Server-side connections** (`NettyServerConnection`) never wait. A reply is queued on the shared
  reply queue or refused. Unchanged.
- **NIO client.** It writes into its buffer or refuses at once. Its one selector thread serves every
  connection, so its replies are handed off as Netty's are.
- **In-JVM clients** (`LocalConnection`, `ConvexLocal`), used by the REST API, its faucet and x402.
  A request is committed when the server's work queue accepts it, and the caller waits inside the
  server's retry (`AThreadedComponent.offerBlocking`, a fixed 8 s). To follow the deadline rule:
  - the request's deadline reaches that retry, the `Function<Message, Predicate<Message>>` from
    `Server.processMessage`;
  - closing a `LocalConnection` wakes the wait;
  - an interrupt during the wait reports `:INTERRUPTED`, not `:LOAD`.

## Related fixes

These are independent of the steps below unless a step names them.

- **`ConnectionManager.handleStatusFailure` treats every `:LOAD` as a sign the peer is still
  there.** Once the status poll's deadline is on the request, a peer that never reads our messages
  produces `FULL_CLIENT_BUFFER` and is never retired. Count `:LOAD` from `:CLIENT` as unresponsive,
  and keep `:LOAD` from `:PEER` as not. Needed with step 3.
- **`Result.fromException` turns unknown exceptions into `:EXCEPTION` with a stack trace.** That
  includes the `RejectedExecutionException` for a refused send. `LatticeProtocolEndpoint` returns
  that result to the remote peer when our own buffer refused an acquisition request. Map the refusal
  explicitly, and send no stack trace to a peer.
- **The lattice ingress thread can wait up to 8 s on its own inbound queue.**
  - This happens when an acquisition completes at once, in `beginLatticeAcquisition`, because another
    acquisition has just stored the missing data. Its completion then runs on the ingress thread.
  - Only that thread empties the queue, so when the queue is full the wait always runs to 8 s.
    Lattice ingress stalls meanwhile, and the value is then dropped.
- **`Convex.getSequence()` sets the cached number outside the client's lock.**
- **`observeTransactionResult` clears the cached number on a `:SEQUENCE` for an older transaction**
  while later ones are in flight. The next lookup can then reissue their numbers.

## Steps

Each step updates the docs it changes.

1. **Close and the request's state.** Close closes the send queue instead of emptying it. Each
   request records whether it was queued, and close fails one that was not with
   `ClosedBeforeSendException`. `failureResult` chooses `:CLIENT` or `:COMM` from that state, and
   `FULL_CLIENT_BUFFER` moves to `:CLIENT`.
2. **The deadline and sequence numbers within today's blocking send.** The reply timer starts once
   the message is queued. The wait for room ends at the request's deadline or the transport's 8 s,
   whichever is first, and a request whose deadline passes first fails with `SendTimeoutException`.
   The cached sequence number advances only once a transaction is queued.
3. **The deadline as a parameter:** the protected `Convex` methods and
   `sendMessage(Message, long)`, with the `*Sync` methods passing their own timeout. The
   `ConnectionManager` fix comes with this step.
4. **Replies and timeouts off the transport thread,** once the callers listed above are checked for
   reliance on callback order.
5. **`transact` blocks until queued in every case,** and its sending from a virtual thread goes. This
   needs step 4, so that no callback on a transport thread calls it.
6. **The transport's fixed 8 s goes,** so every wait for room is bounded only by its deadline.
7. **In-JVM clients,** as above.

## Decisions

Open, in this order:

1. **Replies off the transport thread.** Confirm the hand-off at the network transports and the
   request timer, after checking which callers rely on one connection's callbacks running in order.
   Check too whether the event loop fails pending requests when it closes a connection, which would
   need the same hand-off.
2. **A timeout of zero.** It means no deadline, so after step 6 a send waits for room until close or
   interrupt, as `BlockingQueue.put` does. Nothing in the main code sets it on a client;
   `LatticeInboundVerifier` passes zero for requests on server-side connections, which never wait.
3. **The longest wait for room rising from 8 s to the deadline,** for GUI calls on the Swing event
   thread.
4. **CAD011.** Whether to propose "request never sent" as an example of `:CLIENT`, so that its
   `:COMM` example "timeout" reads as a timeout after sending.

Carrying a request's deadline in the message, so the server can drop work nobody is waiting for,
would change CAD015 and is out of scope.
