# Delta Propagation, Backpressure and Memory Bounds

This document describes the Convex reference implementation of lattice and CPoS
delta propagation. Wire formats and protocol limits remain normative in CAD015
and CAD036; queue sizes, eager-materialisation budgets and scheduling policy are
implementation choices documented here.

## Design invariants

The propagation path maintains five distinct invariants:

1. A complete inbound value limit and an encoded message limit are separate.
   Large store-backed values can arrive through many bounded DATA messages or
   later hash-based acquisition; no single frame needs to contain the value.
2. A delta is encoded once per propagation attempt. Its immutable `Message`
   objects are shared across destination peers rather than reconstructed for
   each peer.
3. Backpressure from one receiver must not delay sends to other receivers.
4. CPoS consensus participation comes before bulk cross-replication. A peer's
   own signed Order is always sent; the Belief that relays other peers' Orders
   is skipped for a receiver whose outbound queue is congested.
5. All retained queues and eager encoded working sets are bounded by bytes as
   well as, where useful, by item count.

## Delta construction and fan-out

`Message.createDataMessages` partitions novel non-embedded cells into DATA
messages no larger than the application delta-message limit. It also accepts a
total encoded-byte budget for the propagation attempt. If that budget is
exhausted, construction stops and omitted cells remain available through the
store and normal pull-based recovery.

The resulting `Message` sequence is constructed once. `AConnectionManager`
passes those same immutable message instances to every connected peer. The
default Netty transport retains the already encoded message body and creates
only destination-specific framing/wrapper state. The legacy NIO transport may
still allocate a framed buffer per connection, but it does not reconstruct the
CAD3 DATA payload or delta.

For a lattice update, `LatticePropagator` first publishes the complete value to
its store and advances its announced cursor. Network encoding is then
best-effort:

- A delta that fits is sent as one `LATTICE_VALUE`.
- A larger delta becomes bounded DATA messages followed by a root-only
  `LATTICE_VALUE`.
- If a cell cannot fit the configured delta-message limit, or the eager budget
  is exhausted, the sender falls back to the root announcement. The receiver
  can acquire missing branches by hash.
- Periodic root-only sync detects a missed or dropped propagation attempt.

Advancing the store-backed state before encoding is important: an encoding or
queueing failure cannot lose the local publication or prevent later recovery.

## Per-peer backpressure

`AConnectionManager.broadcastSequence` uses non-blocking offers independently
for each peer. A full outbound queue stops the sequence for that peer only; the
manager continues immediately with the remaining peers. For lattice deltas it
then attempts a root-only fallback for the affected peer. Failure of that offer
is also safe because later root sync and pull-based acquisition recover the
state.

The default Netty connection has a count- and byte-bounded ordinary outbound
queue, holding the shared encoded messages on the heap until the channel can
take them. A connection to a peer is buffered for up to 256 MB before anything
is refused, and then only for that peer; a client connection gets 16 MiB. The
last message admitted may exceed the bound, so a large update is never refused
merely because the queue is nearly full. It writes the already encoded messages
only while the channel is writable, with one flush after a drain batch. A small, replaceable priority slot
exists for lattice control roots; CPoS does not use it. Slow or non-reading
receivers therefore cannot block the propagator thread, the Netty event loop or
other peer connections.

## CPoS scheduling

`BeliefPropagator` sends consensus updates in two layers. Each is built and then
offered to every peer with a non-blocking send before the next is built, so the
two reach each peer's ordered queue in this order:

- **Own Order** (inner layer): our latest signed `Order` with everything
  reachable from it that this peer has not yet announced, normally one new Block
  with its transactions. It is sent whenever our Order changes, and as a
  130-byte root-only keepalive every `BELIEF_REBROADCAST_DELAY` otherwise. It is
  our consensus vote, so it is offered before any relay work starts. A Block of
  any size is carried: when the update fits one message it is a single delta
  with the Order as its top cell, otherwise DATA messages of at most the message
  limit each precede the Order on the same ordered queue. Block production
  bounds Blocks by transaction count only and knows nothing of message sizes.
- **Belief** (outer layer): the Belief with everything not announced by the
  Order update, which is other peers' Orders and, the first time this peer
  relays them, their Blocks, shaped the same way. It is sent whenever
  any Order in the Belief changes and every `BELIEF_FULL_BROADCAST_DELAY`
  otherwise. It is withheld from a peer whose outbound queue is under pressure:
  the Belief is relay, not consensus, and the next change carries it again. A
  peer that refused the Order for want of queue space always counts as under
  pressure, so it is never offered the Belief alone.

The connection manager is message-agnostic. It offers whatever it is given to
every peer in call order, with one option to skip peers under outbound pressure;
which message is essential and which is optional is the propagator's decision.

Ordered delivery on one queue means data always precedes the Order that commits
it, so nothing is superseded and nothing is resent. Announcing is therefore
honest: a cell is announced when its messages are offered to the peers. What one
update materialises is bounded by bytes, never by cell count, at the peer queue
bound (`BeliefPropagator.MAX_UPDATE_BYTES`); novelty beyond it stays in the store
and the receiver pulls it. A peer whose queue cannot take the data is offered the
root alone, so it too learns what to pull. `UpdateAccumulator` does this shaping;
it is peer code, because what to carry eagerly is a peer's decision, not the data
layer's.

Every remaining way to miss data, a full queue or a relayed Order whose Blocks
this peer has not seen, ends in a request rather than a timer:
`ConnectionManager.alertMissing` acquires the missing hash from the peer that
sent the message, rate limited per hash, and the next update merges once the
data has arrived. The 2 s status poll remains the recovery of last resort.

Inbound CPoS DATA and BELIEF messages share one ordered, byte-bounded processing
queue off the Netty event loop. This preserves the DATA-before-root order for a
connection while keeping decoding and store work away from network I/O. A
trusted connection whose message does not fit is paused until it does: consensus
traffic is never timed out or dropped on the receiving side, so the pressure
reaches the sender's queue, which is where drops are decided. The queue admits
one message over its byte bound, so any legal frame is eventually accepted.

## Memory budgets

Defaults are deliberately conservative application policy, not additional wire
protocol limits:

| Retained or materialised state | Default bound | Control |
|---|---:|---|
| Lattice delta message or DATA body | 4 MiB | `LatticePropagatorConfig.maxDeltaMessageSize` |
| One eager lattice propagation | 16 MiB | `LatticePropagatorConfig.maxDeltaBroadcastSize` |
| Node inbound processing queue | 1,024 messages and 16 MiB | `inboundQueueSize`, `maxInboundQueueBytes` |
| Belief or own-Order delta message | 4 MiB | `:max-belief-delta-message-size` |
| Trusted CPoS DATA/BELIEF queue | 200 messages and 16 MiB | `Config` constants |
| Untrusted CPoS DATA/BELIEF queue | 10 messages and 4 MiB | `Config` constants |
| Outbound queue, per client connection | 128 messages and 16 MiB | `Config` constants |
| Outbound queue, per connection to a peer | 65,536 messages and 256 MB; the last message admitted may exceed it | `Config` constants |
| Priority outbound slot, per connection | one message, at most 64 KiB | `Config` constants |
| Materialised messages per consensus update | 256 MB | `BeliefPropagator.MAX_UPDATE_BYTES` |
| Lattice novelty retained per attempt | at most 65,536 cells, also byte-budgeted | `convex.node.NoveltyCollector` |

The complete inbound lattice-value limit is separately configured with
`LatticePropagatorConfig.maxInboundValueSize`; it does not need to equal the
delta-message limit. Public and trusted encoded frame limits are configured per
group with `maxMessageSize` and `maxTrustedMessageSize` and must remain within
the protocol maximum. `NodeConfig.maxMessageSize` independently protects each
standard `LatticeListener` before a connection has been assigned to a group.

These bounds cover application-retained encoded bodies and explicit queues. They
are not a whole-process heap bound: decoded cells, the store cache, Netty
buffers, socket buffers and concurrent connections also consume memory.
Capacity planning must account for those separately. In particular, multiplying
a count-only queue limit by the maximum frame size is avoided by enforcing an
independent encoded-byte limit.

## Receiver policy and recovery

Unsolicited DATA is staging only. It is accepted only after the connection has
been assigned an authorised propagator/store capability, and it does not merge
or publish a lattice value. The acquiror and its storage policy belong to the
receiving side. A later LATTICE_VALUE or BELIEF root activates normal processing
and requests any missing cells.

Dropping an eager delta is therefore a loss of realtime delivery, not permanent
data loss. Recovery paths are the root-only fallback, periodic lattice root
sync, CPoS polling/cross-replication and ordinary DATA_REQUEST acquisition.

## Relevant implementation

- `convex.core.message.Message` — bounded DATA construction
- `convex.peer.UpdateAccumulator` — shapes one consensus update into DATA messages then its root
- `convex.node.NoveltyCollector` — bounded novelty collection for lattice deltas
- `convex.core.message.BoundedMessageQueue` — count- and byte-bounded FIFO
- `convex.node.LatticePropagator` — lattice chunking and root fallback
- `convex.peer.BeliefPropagator` — own-Order then Belief update cadence
- `convex.peer.AConnectionManager` — message-agnostic non-blocking fan-out, optionally skipping peers under outbound pressure
- `convex.net.impl.netty.NettyConnection` — per-connection queues and outbound pressure signal
