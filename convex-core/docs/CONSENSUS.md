# Convex Consensus: Design Principles and Security

The implementation-level companion to
[CAD051 (Convergent Proof of Stake)](https://docs.convex.world/docs/cad/cpos),
which is the normative specification. This document is for contributors working
in `convex.core.cpos` and the peer's consensus loop: the structural principle
that keeps the network fork-free, how the two clocks are handled, the reasoning
behind the forward-dated block policy in `BeliefMerge`, and the attack surfaces
a change must not reopen. Staking economics and operator concerns are in
[CAD016](https://docs.convex.world/docs/cad/peerstake) and
[CAD017](https://docs.convex.world/docs/cad/peerops).

## Key points

- **The determinism boundary is the design.** The state transition is a pure
  function of (State, Block); anything that depends on a peer's environment is
  peer-local policy in belief merge. A rule on the wrong side forks the network.
- **Four consensus levels, 2/3 of effective stake each**: ordering, proposal,
  consensus, finality. State executes only against the finalised prefix.
- **Effective stake decays** with time since a peer last produced a block, so
  parked or partitioned stake loses influence automatically.
- **Two clocks, never conflated**: consensus time (state, advanced only by
  confirmed blocks) and the peer's wall clock (policy input only).
- **Backdating is a validity rule, forward-dating is a confirmation policy.**
  Blocks older than consensus time minus 15 minutes are invalid; blocks beyond
  the peer's wall clock plus a small allowance are never invalid, but honest
  peers refuse to confirm past them until their clocks catch up.
- **Belief merge ignores Orders dated beyond the peer's own clock**, so gossip
  timestamps cannot win tie-breaks.
- **Front-running by the submitting peer is by design**; the mitigation is
  client choice of a trusted, well-staked peer.
- **Untrusted input fails closed**: bounded message sizes, bounded missing-data
  requests, juice metering, and merge paths that never throw into the peer loop.

## CPoS in brief

Peers converge on one ordering of Blocks without leaders or rounds. Each peer
keeps an **Order** (its view of the ordering plus its consensus points) inside a
signed **Belief** it gossips. `BeliefMerge` combines the Orders of other peers
with the peer's own, weighted by effective stake, and adopts the stake-weighted
winner; repeated adoption of the majority view converges exponentially fast.

Confirmation proceeds through `CPoSConstants.CONSENSUS_LEVELS` levels. Level 0
is the raw Block vector; each higher level records the longest prefix agreed by
at least `CONSENSUS_THRESHOLD` of effective stake at the level below
(`BeliefMerge.updateLevel`): proposal, consensus, finality, each a prefix of the
one beneath. `Peer.updateState` applies Blocks up to the finality point. The
thresholds give BFT-style tolerance of up to one third adversarial stake.

Effective stake is computed by `State.computeStakes` using
`Economics.stakeDecay`: a peer's weight decays with time since its last Block
down to a floor of `PEER_DECAY_MINIMUM`. Peers below
`MINIMUM_EFFECTIVE_STAKE` are excluded from Block production by
`State.checkBlock`.

## The determinism boundary

| Deterministic (consensus-critical) | Peer-local (policy) |
|---|---|
| `State.applyBlock` / `checkBlock`: validity and execution | Which Blocks a peer proposes |
| `applyTimeUpdate`, `applyScheduledTransactions` | Which Orders a peer adopts or ignores |
| `applyUpgrades` (see `UPGRADE.md`) | When a peer confirms (votes to advance) |
| Juice and fee computation | Proposal-switching patience (`KEEP_PROPOSAL_TIME`) |
| Effective stake computed from State | The peer's wall clock |

Consequences:

- **Replay safety.** Any peer must replay history from genesis to bit-identical
  state, so `checkBlock` consults only the State and the Block: never the wall
  clock, the local store or peer configuration.
- **Validity is forever.** A Block valid at position *n* is valid at position
  *n* on every peer at any time. Anything time-of-observation-dependent cannot
  be a validity rule.
- **Policy is free.** Belief merge runs on one peer's inputs and clock; its
  choices are visible only through the signed Orders it publishes, and peers
  may differ without forking because consensus advances only where a stake
  supermajority's policies overlap.

The practical test for any proposed rule: *if two honest peers could evaluate it
differently at the same Block position, it must be policy, not validity.*

## Time in consensus

1. **Consensus time** is the State's timestamp global, advanced monotonically by
   `State.applyTimeUpdate` from confirmed Block timestamps. Scheduled
   transactions, memory-pool growth, stake decay and upgrade activation all key
   off it.
2. **Peer wall-clock time** is `Peer.timestamp`, advanced forward-only by the
   Server and exposed to merge as `BeliefMerge.getTimestamp()`. Policy input
   only.

Block timestamps are set by the proposing peer from its wall clock. They are
claims, bounded as follows.

### Backdating: deterministic bound

`State.checkBlock` rejects Blocks older than consensus time minus
`MAX_BLOCK_BACKDATE` (15 minutes) and Blocks not later than the proposing peer's
previous Block. Both compare Block time against State time, the same on every
peer, so they are sound validity rules.

### Forward-dating: two-stage confirmation policy

There is deliberately no forward validity bound: "too far in the future" is
relative to the observer's clock, which differs between peers and between
execution and replay. Without a policy, a staked peer proposing a far-future
Block that reached finality would jump the consensus clock (the "clock
teleport"), wedging schedules and firing every scheduled network upgrade at
once.

**Stage (i), confirmation clamp (safety).** After `updateConsensus` computes the
peer's consensus points, each level's point is clamped so it never exceeds the
longest Block prefix whose timestamps all lie within the *horizon*, the wall
clock plus `MAX_BLOCK_FORWARD` (a clock-skew allowance of tens of seconds),
floored at the previous point so nothing is ever retracted. The horizon is the
first out-of-window Block scanned from the front; no timestamp ordering is
assumed. Because each level needs 2/3 effective stake at the level below and
execution happens only at finality, an honest supermajority that clamps means a
future-dated Block cannot be finalised, executed, or advance the consensus clock
before real time reaches its timestamp. Alone, the clamp accepts a bounded,
attributable liveness wedge under attack: legitimate Blocks behind the offending
one wait until the horizon passes it.

**Stage (ii), ordering hygiene (liveness).** `filterBlocks` calls
`demoteFutureBlocks` on the winning order before consensus is computed, stably
moving out-of-window Blocks to the back of the unconfirmed tail. It is a stable
partition, not a timestamp sort: sorting by claimed timestamp would let a peer
back-date within the 15-minute window to jump ahead of others. In-horizon Blocks
keep their observation-based order; only far-future Blocks move, and only
behind the consensus point, which stage (i) guarantees they never crossed. The
stages compose: given `[F, N]` with `F` far-future, stage (ii) yields `[N, F]`
and stage (i) finalises `N` while holding `F`.

The asymmetry with the backdate bound is intentional: backward is
state-relative, lenient and deterministic; forward is wall-clock-relative, tight
and policy. They live on opposite sides of the boundary.

## Security

### Threat model

Peers are public-by-default infrastructure and must be robust to arbitrary
malicious messages. An attacker may hold stake up to the BFT bound, submit
arbitrary transactions, and craft arbitrary Belief, Order and Block data.
Consensus safety must not depend on any peer-local trust decision; a client's
trust in a specific peer for submission is a separate, explicit choice.

### Attack surfaces and mitigations

- **Clock teleport.** Closed by the stage (i) clamp; liveness under attack
  restored by stage (ii).
- **Backdated or replayed Blocks.** Bounded deterministically by
  `MAX_BLOCK_BACKDATE` and per-peer timestamp monotonicity in `checkBlock`.
- **Future-dated Orders.** Ignored in belief merge, so an attacker cannot make a
  Belief artificially newest.
- **Stake-weight manipulation.** Advancement needs 2/3 of *effective* stake;
  decay removes the weight of inactive peers and sub-minimum peers cannot
  produce Blocks. Delegated stake adds weight but is withdrawable only by its
  delegator (`StakingTest`).
- **Peer-record manipulation.** All peer mutation (`set-peer-data`,
  `set-peer-stake`, eviction of a well-staked peer) is gated to the peer's
  controller address; matching a peer's key with one's own `*key*` confers
  nothing. `Context.evictPeer` refuses to evict the last peer as a liveness
  guard; sub-threshold peers may be evicted by anyone, deliberately.
- **Front-running by the submitting peer.** Inherent to the submission model
  and by design. Do not treat proof-of-concept demonstrations as
  vulnerabilities.
- **Transaction replay.** Prevented by per-account sequence numbers in the state
  transition, not by peer policy.
- **Message-level denial of service.** `MAX_MESSAGE_LENGTH`, `MISSING_LIMIT`,
  bounded transactions per Block, and juice metering on all execution including
  compile and expand. Malformed data from untrusted peers must fail closed.
- **Upgrade boundary.** A peer that cannot apply a scheduled upgrade withdraws
  deterministically rather than diverging; the trigger is unforgeable on-chain
  state, so withdrawal is not selectively targetable. See `UPGRADE.md`.

### Open items

- **Fork recovery is disabled** (`ENABLE_FORK_RECOVERY`): peers filter Orders
  inconsistent with their own consensus rather than reorganising. Enabling it
  is tracked in [#492](https://github.com/Convex-Dev/convex/issues/492).
- **Proposal-switching patience** (`KEEP_PROPOSAL_TIME`) is fixed;
  randomisation to reduce synchronised flapping is a noted refinement.
- **`create-peer` accepts any 32-byte key**, so a peer record can be squatted
  for a key the creator does not own. The key holder cannot be impersonated,
  since Block signatures need the private key. Accepted for now.
- **Eviction refunds are effectively unpriced**: `evictPeer` extends the juice
  limit per delegated-stake refund so eviction always completes. Accepted,
  since refunds only move value to its owners.

## Design decisions

| Question | Decision |
|---|---|
| Forward bound: validity or confirmation policy? | **Policy**, in belief merge. A wall-clock-dependent validity rule breaks replay determinism. |
| Stage (ii): sort the tail, or partition it? | **Stable partition.** A timestamp sort makes claimed time the ordering key and enables timestamp-driven front-running within the backdate window. |
| Symmetric with the backdate bound? | **No.** Backdate is state-time-relative and lenient; forward is wall-clock-relative and tight. Different questions, different sides of the boundary. |
| Hard deterministic forward backstop? | **No.** It reintroduces the determinism hazard for no gain: the clamp already prevents early execution, and a backstop could invalidate a legitimately delayed Block on replay. |
| Can the clamp stall consensus? | **Honestly, no**: it is monotone and floored at the current point. Under attack, stage (i) alone queues Blocks behind an out-of-window one; stage (ii) removes that wedge. |

## Testing principles

Consensus code changes carry fork risk and are held to a higher standard:

- **Determinism**: identical inputs produce bit-identical States across
  construction paths and replay. `SnapshotStateTest` replays recorded Blocks
  against a golden hash; a change that moves it is consensus-visible and must
  be version-gated (see `UPGRADE.md`).
- **Injected clocks, never sleeps**: the peer clock is injectable, so
  timestamp scenarios (deferral, boundary crossing, skewed peers) are
  deterministic.
- **Adversarial tests** accompany every consensus-adjacent operation:
  authorisation, conservation of total supply, malformed input, liveness
  guards.
- **Belief-level convergence** (`BeliefMergeTest`, `BeliefVotingTest`) with
  multiple peers, differing stakes and orderings.

## Where the code lives

- `convex.core.cpos.BeliefMerge`: merge, `updateConsensus` / `updateLevel`,
  the stage (i) clamp, `filterBlocks` / `demoteFutureBlocks`.
- `convex.core.cpos.CPoSConstants`: every parameter named above.
- `convex.core.cvm.State`: `checkBlock`, `applyBlock`, `applyTimeUpdate`,
  `computeStakes`; `convex.core.util.Economics.stakeDecay`.
- `convex.core.cvm.Peer`: wall-clock timestamp and `updateState`.
- `convex.core.cvm.Context`: peer-record mutation and `evictPeer`.
- Tests: `convex.core.cpos.BeliefMergeTest`, `BeliefVotingTest`,
  `convex.core.cvm.SnapshotStateTest`, `convex.core.StakingTest`, and the
  adversarial cases in `convex.core.lang.CoreTest`.

## Related

- [CAD051: Convergent Proof of Stake](https://docs.convex.world/docs/cad/cpos),
  the normative specification including the parameter table.
- [CAD050: Network Upgrades](https://docs.convex.world/docs/cad/network_upgrade)
  and `UPGRADE.md`, for the activation that consensus time gates.
- [CAD016: Peer Staking](https://docs.convex.world/docs/cad/peerstake),
  [CAD017: Peer Operations](https://docs.convex.world/docs/cad/peerops).
- Forward-dating hardening: [#595](https://github.com/Convex-Dev/convex/issues/595).
