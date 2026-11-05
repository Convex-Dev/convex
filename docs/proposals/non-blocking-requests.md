# Client requests that never block

- Status: Proposed
- Last updated: 2026-11-05
- Specification: [CAD015](https://docs.convex.world/docs/cad/peercomms) makes messaging
  asynchronous and matches results to pending request IDs;
  [CAD011](https://docs.convex.world/docs/cad/errors) gives `:COMM` as the error source for
  communication failures and timeouts. Neither covers client send queues or timeouts, so
  nothing here changes the wire protocol or a CAD.
- Current design: [CLIENT_BACKPRESSURE.md](../../convex-peer/docs/CLIENT_BACKPRESSURE.md),
  [BACKPRESSURE.md](../../convex-peer/docs/BACKPRESSURE.md),
  [MESSAGING.md](../../convex-peer/docs/MESSAGING.md).

## Summary

A client request (`AConnection.request` and the `Convex` calls built on it) returns its
future at once and never blocks the calling thread. When the connection is backed up, the
request waits in a per-connection waiting area until the send queue has room. One deadline
per request covers waiting, sending and the reply.

Putting a message on the send queue commits it. Before that, a request can be dropped; after
it, the message is sent unless the connection closes first. The result says which:
`FULL_CLIENT_BUFFER` means the request was never queued, `:TIMEOUT` means it was queued but no
reply came in time.

## Why

- `request()` returns a future, but first blocks the caller for up to 8 s
  (`Config.DEFAULT_INTERNAL_TIMEOUT`) waiting for room on the send queue. That limit is
  unrelated to the request's own timeout, which defaults to 20 s.
- The reply timer starts before that wait. A request can time out while its caller is still
  blocked, then be queued and sent anyway. A timeout and a send failure can race to set the
  result, and `:TIMEOUT` cannot say whether anything was sent.
- Callbacks on a request's future run on whichever thread completes it: the Netty event loop
  for a reply, the JDK's single shared timer thread for `orTimeout`, a peer's handler thread
  for an in-JVM client. A request chained from such a callback is refused at once on the event
  loop and on a handler thread, and can block the shared timer thread for up to 8 s.
- `Convex.transact` sends while holding the client's monitor, so one blocked send stalls every
  other transaction on that client.
- The `*Sync` methods start their own timer only after the blocking send returns:
  `requestStatusSync(2000)` can take about 10 s.

## Parts

| Part | Role | Class | Lifetime |
|---|---|---|---|
| Request | One correlated exchange: its deadline, its state (waiting or queued) and its future | `AConnection.RequestFuture` | From `request()` until a reply, the deadline, a cancel or a close |
| Waiting area | Requests not yet queued, in call order | New, per client connection | Connection |
| Send queue | Committed messages, emptied only by the event loop | `NettyConnection` outbound `BoundedMessageQueue` | Connection |
| Event loop | Moves waiting requests onto the send queue as room frees; writes the send queue to the socket | Netty | Connection |
| Netty write buffer, TCP | Carry committed messages to the peer | Netty, kernel | Connection |
| Client | Turns outcomes into Results; sequences transactions | `Convex`, `AConvexConnected` | Client |
| Server | Pauses reading, queues work, sheds load with `:LOAD` | Unchanged ([BACKPRESSURE.md](../../convex-peer/docs/BACKPRESSURE.md)) | Peer |

## Rules

### Commit

A message is committed when it goes onto the connection's send queue. Before that, a request
can be dropped: its deadline passes, it is cancelled, or the connection closes. After it, the
message is sent unless the connection closes first. A deadline or a cancel after commit only
stops the wait for the reply. Nothing but the event loop takes messages from the send queue;
closing the connection discards what is left in it.

### Waiting for room

`request()` never blocks. If no request is waiting and the send queue has room, the message is
committed at once. Otherwise the request joins the waiting area. As room frees, the event loop
commits the oldest waiting request that is still wanted. Raising the send queue's limits, as
happens when a connection is identified as a peer's, frees room too.

The send queue stays small (128 messages or 16 MiB for a client), because everything in it is
committed. The backlog waits in the waiting area, where it can still be dropped.

The waiting area is bounded by a message count and by bytes. As on the send queue, a request
is let in while the bytes already waiting are under the limit, so a request larger than the
limit is not refused outright. A request that finds the waiting area full is refused at once.

### Deadline

Each request has one deadline, counted from the call. It covers waiting for room, sending and
the reply. A timeout of zero means no deadline: the request waits until it is committed,
cancelled or closed, and then for its reply. A request whose deadline passes is dropped if it
is still waiting, and times out if it was committed. Timeouts complete the future on a virtual
thread, never on the JDK's shared timer thread.

### Outcomes

| What ended the request | Sent? | Connection reports | Client Result |
|---|---|---|---|
| A reply | Yes | The reply | The reply's Result |
| Deadline, while waiting | No | Not-sent exception | `FULL_CLIENT_BUFFER` (`:LOAD`, source `:COMM`) |
| Waiting area full | No | Not-sent exception | `FULL_CLIENT_BUFFER` |
| Cancel, while waiting | No | `CancellationException` | None: the future is cancelled |
| Close, while waiting | No | `ClosedChannelException` | `CLOSED_CONNECTION` |
| Deadline, after commit | Unknown | `TimeoutException` | `:TIMEOUT` (source `:COMM`) |
| Close, after commit | Unknown | `ClosedChannelException` | `CLOSED_CONNECTION` |

`FULL_CLIENT_BUFFER` already means "never sent" today. With the commit rule, `:TIMEOUT` comes
to mean "sent, no reply in time".

### Synchronous calls

The `*Sync` methods put their timeout on the request instead of applying it with `get`, so
their results say whether the request was sent. Waiting for each result paces a synchronous
caller.

### Ordering

Requests on one connection are committed in call order, apart from those that are dropped.

The client signs and submits transactions in call order, so they reach the peer in sequence
order. A transaction dropped before it is queued leaves a gap: transactions from the same
client that were already signed with later sequence numbers fail with `:SEQUENCE`. A caller
that needs each transaction's outcome before sending the next uses `transactSync`; a caller
that pipelines transactions handles `:TIMEOUT` and `:SEQUENCE`.

The client reserves a sequence number when it signs and forgets it only on `:SEQUENCE`. When
a transaction is known not to have been sent and no later transaction has been signed since,
the client rewinds its cached sequence number, so a synchronous caller's next transaction does
not fail needlessly.

## Who sees a change

These apply to network clients (`ConvexRemote`); in-JVM clients are a later step.

- **Asynchronous callers are no longer slowed.** They are bounded by the waiting area's cap
  instead. The load tools that rely on being slowed queue instead: the GUI stress panel,
  `LatencyBenchmark`, `ServerTest.testServerFlood` and the `TransactionThroughput` example.
- **Requests chained on the event loop queue instead of being refused under load.** This covers
  the second challenge in lattice peer verification, `ConnectionManager`'s fallback status
  request and `P2PNode` initialisation.
- **`transact` no longer holds the client's monitor while waiting for room.**
- **GUI calls on the Swing event thread** no longer risk blocking it.
- **Synchronous callers** see only more accurate results.

## API changes

- **Public signatures:** none change.
- **Contracts:**
  - `AConnection.request` and `sendRequest` never block, commit as above and report the
    outcomes above.
  - `Convex.transact` loses "may block briefly" and states the gap rule.
  - The `*Sync` methods' timeouts cover the whole request.
- **Protected or internal:**
  - The request's state and deadline live in `AConnection`, with a hook for the transport to
    commit or drop a waiting request.
  - The waiting area extends the send queue in `NettyConnection`.
- **Blocking send:** once one-way `Convex.message()` stops blocking too, nothing calls
  `AConnection.sendMessage(Message)` any more.
- **Deferred:** a public signal that completes when a request is committed, for producers that
  want to pace on commit rather than on results. Nothing needs it yet.

### Implementation requirements

- Committing at once, joining the waiting area, and the event loop freeing room and committing
  waiting requests all happen under one lock. Otherwise a request can be left waiting with room
  free and nothing to move it.
- A request is claimed for commit or for dropping exactly once, under that lock. So a cancel or
  deadline that races with the commit gives a result consistent with what was sent.
- Waiting requests are matched by identity, not by `Message.equals`, which compares payloads.
- Waiting requests expire by their own deadline. They do not wait for the queue to drain, which
  never happens on a stuck connection.

## Other transports

- **Server-side connections** (`NettyServerConnection`) never wait. A request is committed when
  the shared reply queue accepts it, and refused otherwise. Unchanged.
- **NIO client.** It writes into its buffer or refuses at once. Unchanged.
- **In-JVM clients** (`LocalConnection`, `ConvexLocal`). These are what the REST API, its faucet
  and x402 use.
  - A request is committed when the server's work queue accepts it.
  - Today the caller waits up to 8 s inside the server's retry, holding the client's monitor for
    transactions.
  - This needs its own design. One constraint: don't add a hand-off to another thread for every
    message on the fastest path.

## Related fixes

These are independent of the steps below.

- **`ConnectionManager.handleStatusFailure` treats every `:LOAD` as a sign the peer is still
  there.** Once the status poll's deadline is on the request, a peer that never reads our
  messages produces `FULL_CLIENT_BUFFER` and is never retired. Count our own full buffer
  (`:LOAD`, source `:COMM`) as unresponsive, and keep the peer's `:LOAD` (source `:PEER`) as not.
  Needed with step 2.
- **`Result.fromException` turns unknown exceptions into `:EXCEPTION` with a stack trace.** That
  includes the `RejectedExecutionException` for a refused send.
  `LatticeProtocolEndpoint` returns that result to the remote peer when our own buffer refused
  an acquisition request. Map "not sent" explicitly, and send no stack trace to a peer.
- **The lattice ingress thread can wait up to 8 s on its own inbound queue.**
  - This happens when an acquisition completes at once, in `beginLatticeAcquisition`, because
    another acquisition has just stored the missing data. Its completion then runs on the
    ingress thread.
  - Only that thread empties the queue, so when the queue is full the wait always runs to 8 s.
    Lattice ingress stalls meanwhile, and the value is then dropped.

## Steps

Each step updates the docs it changes.

1. **Within today's blocking send:**
   - A request gets its state and one timer, started at the call.
   - The caller's wait for room ends at the request's deadline or the transport's 8 s, whichever
     is first. It is claimed under the queue's lock, so a request whose deadline has passed is
     never queued.
   - The result says whether the request was queued.
   - Transactions known not to have been sent rewind the cached sequence number, as above.
   - The time-limited send stays protected.
   - Callers see a difference only when a send gives up. A client set to a timeout under 8 s
     stops waiting at its deadline. A transaction that was never sent no longer makes the next
     one fail with `:SEQUENCE`.
2. **The `*Sync` methods put their timeout on the request.** With this, `ConnectionManager`
   counts our own `:LOAD` as unresponsive.
3. **The waiting area.** `request()` stops blocking on network clients, the cap applies, and the
   rule for broadcasts takes effect.
4. **One-way `Convex.message()`.** Decide what happens to the blocking send.
5. **In-JVM clients.**

## Decisions

Taken one at a time, in this order.

1. **The connection-level exception for "not sent".** Either a new type, or keep
   `RejectedExecutionException`. Also whether `Result.fromException` maps it. Needed for step 1.
2. **Close before commit and close after commit.** Report them differently, or both as
   `CLOSED_CONNECTION` as today.
3. **At the waiting area's cap.** Refuse (recommended) or block. Blocking would bring back waits
   on threads borrowed from callbacks.
4. **Broadcasts and other non-request messages** (`trySendMessage`) **while requests are
   waiting.**
   - Option one: they go straight onto the send queue, which is what happens in effect today.
     Steady broadcasts could then starve waiting requests.
   - Option two: they wait behind waiting requests, or are refused.
5. **The blocking `sendMessage(Message)`.** Remove it, or keep it as a send that never waits.
6. **The in-JVM design.**
7. **Values.** The waiting area's count and byte limits. Whether the transport's 8 s wait goes
   with step 3, when no caller waits for room any more.

Carrying a request's deadline in the message, so the server can drop work nobody is waiting
for, would change CAD015 and is out of scope.
