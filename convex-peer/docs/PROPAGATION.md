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
4. CPoS consensus participation takes priority over bulk cross-replication. A
   peer's latest own signed Order remains sendable when full-Belief propagation
   is congested.
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
queue. It writes the already encoded messages only while the channel is
writable, with one flush after a drain batch. A small, replaceable priority slot
is separate from the bulk queue. Slow or non-reading receivers therefore cannot
block the propagator thread, the Netty event loop, other peer connections, or
CPoS order publication.

## CPoS scheduling

`BeliefPropagator` treats two outputs differently:

- The local peer's latest signed `Order` is a small BELIEF message carrying the
  Order's novel cells inline whenever they fit the priority message limit, so a
  receiver can merge it immediately. It is offered to the per-connection
  priority slot and supersedes an older unsent Order. Because announcing the
  Order consumes announce-novelty whether or not the message is ultimately
  sent, novelty carried by quick updates is retained and folded into the next
  full Belief broadcast — a superseded priority message therefore delays eager
  delivery by at most one full-broadcast interval rather than losing the cells
  from every delta.
- A full Belief is cross-replication. When due, its novelty is encoded once as a
  bounded DATA-ahead sequence ending in a BELIEF root, then offered
  independently to each peer.

The own Order is offered before any full-Belief encoding. Full Beliefs are
attempted at most once per `BELIEF_FULL_BROADCAST_DELAY` (currently 500 ms),
while intervening updates can publish only the latest own Order. Congestion may
therefore reduce a peer temporarily to direct consensus participation without
making bulk propagation a prerequisite for consensus progress.

Inbound CPoS DATA and BELIEF messages share one ordered, byte-bounded processing
queue off the Netty event loop. This preserves the DATA-before-root order for a
connection while keeping decoding and store work away from network I/O.

## Memory budgets

Defaults are deliberately conservative application policy, not additional wire
protocol limits:

| Retained or materialised state | Default bound | Control |
|---|---:|---|
| Lattice delta message or DATA body | 4 MiB | `LatticePropagatorConfig.maxDeltaMessageSize` |
| One eager lattice propagation | 16 MiB | `LatticePropagatorConfig.maxDeltaBroadcastSize` |
| Node inbound processing queue | 1,024 messages and 16 MiB | `inboundQueueSize`, `maxInboundQueueBytes` |
| Belief delta message or DATA body | 4 MiB | `:max-belief-delta-message-size` |
| One eager Belief propagation | 16 MiB | `:max-belief-delta-broadcast-size` |
| Trusted CPoS DATA/BELIEF queue | 200 messages and 16 MiB | `Config` constants |
| Untrusted CPoS DATA/BELIEF queue | 10 messages and 4 MiB | `Config` constants |
| Ordinary outbound queue, per connection | 128 messages and 16 MiB | `Config` constants |
| Priority outbound slot, per connection | one message, at most 64 KiB | `Config` constants |
| Novelty references collected per attempt | at most 65,536, also byte-budgeted | `Cells.MAX_NOVELTY_CELLS` |

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
- `convex.core.data.Cells.NoveltyCollector` — bounded novelty collection
- `convex.core.message.BoundedMessageQueue` — count- and byte-bounded FIFO
- `convex.node.LatticePropagator` — lattice chunking and root fallback
- `convex.peer.BeliefPropagator` — own-Order priority and full-Belief cadence
- `convex.peer.AConnectionManager` — non-blocking shared-sequence fan-out
- `convex.net.impl.netty.NettyConnection` — per-connection queues and priority slot
