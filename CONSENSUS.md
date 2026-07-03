# Convex Consensus: Design Principles and Security

This document describes the implementation-level design of Convergent Proof of Stake
(CPoS) consensus in this repository: the principles the implementation must preserve,
how time is handled, and the security analysis of the main attack surfaces. It is the
consensus counterpart to `UPGRADE.md` (network upgrades) and is grounded in the actual
code, with file references.

Spec-level material lives in the design repository (CADs); operational peer/staking
detail is in [CAD016 (peer staking)](https://docs.convex.world/docs/cad/staking) and
[CAD017 (peer operations)](https://docs.convex.world/docs/cad/peerops).

## CPoS in brief

Convex peers converge on a single ordering of Blocks without leader election or
round-based voting. Each peer maintains an **Order** — its view of the Block ordering,
plus consensus points — inside a signed **Belief** that it gossips to other peers.
Belief merge (`convex.core.cpos.BeliefMerge`) combines the Orders of other peers with
the peer's own, weighted by effective stake, and adopts the stake-weighted winning
ordering. Because every peer repeatedly adopts the majority view of others, orderings
converge exponentially fast; consensus is the fixed point of this merge.

Confirmation proceeds through **consensus levels** (`CPoSConstants.CONSENSUS_LEVELS`
= 4): level 0 is the raw Block vector, and each higher level records the longest
prefix agreed by at least `CONSENSUS_THRESHOLD` (2/3) of effective stake *at the level
below* (`BeliefMerge.updateLevel`) — **proposal** (1), **consensus** (2), **finality**
(3), each necessarily a prefix of the one beneath it. The State is executed against the
**finalised** prefix (`Peer.updateState` applies Blocks up to the finality point,
Peer.java:499); consensus and proposal are the intermediate confirmations that precede
it. The thresholds give classic BFT-style tolerance of up to 1/3 adversarial stake.

**Effective stake** is not raw stake: `State.computeStakes()` (State.java:658) decays
each peer's stake by time since it last produced a Block
(`Economics.stakeDecay`, constants in `CPoSConstants`), down to a floor of
`PEER_DECAY_MINIMUM` (0.1%). Inactive or partitioned peers therefore lose consensus
weight automatically, and cannot silently hold back convergence. Peers below
`MINIMUM_EFFECTIVE_STAKE` are excluded from Block production entirely
(`State.checkBlock`, State.java:227).

## The determinism boundary

The single most important structural principle: **the state transition is a pure,
deterministic function of (State, Block); everything that depends on a peer's local
environment is peer-local policy in belief merge.** Getting a rule on the wrong side
of this boundary is how consensus implementations fork.

| Deterministic (consensus-critical) | Peer-local (policy) |
|---|---|
| `State.applyBlock` / `checkBlock` — validity, execution | Which Blocks a peer *proposes* |
| `applyTimeUpdate`, `applyScheduledTransactions` | Which Orders a peer adopts or ignores |
| `applyUpgrades` (see UPGRADE.md) | When a peer *confirms* (votes to advance consensus) |
| Juice and fee computation | Proposal-switching patience (`KEEP_PROPOSAL_TIME`) |
| Effective-stake computation from State | The peer's wall clock |

Consequences:

- **Replay safety.** Any peer must be able to replay history from genesis and reach
  bit-identical state. Therefore `checkBlock` may consult only the State and the Block
  — never the wall clock, the local store, or the peer's configuration.
- **Validity is forever.** A Block judged valid at position *n* must be valid at
  position *n* on every peer at any time. Anything time-of-observation-dependent
  cannot be a validity rule.
- **Policy is free.** Belief merge runs on a single peer's inputs and clock and its
  choices are visible to others only through the (signed) Orders it publishes. Two
  peers may legitimately disagree in policy without forking, because consensus only
  advances where a stake supermajority's policies *overlap*.

The practical test for any proposed rule: *if two honest peers could evaluate it
differently at the same Block position, it must be policy, not validity.*

## Time in consensus

There are two clocks, and they must never be conflated:

1. **Consensus time** — the State's timestamp global, advanced only by confirmed
   Blocks (`State.applyTimeUpdate`, State.java:336, monotonic). All on-chain,
   deterministic behaviour keys off this: scheduled transactions, memory pool growth,
   stake decay, and network upgrade activation (`applyUpgrades`, first step of
   `prepareBlock`, State.java:258).
2. **Peer wall-clock time** — `Peer.timestamp`, advanced (forward only) by the Server
   polling the local clock (Peer.java:96–99, 306), and passed into belief merge as
   `BeliefMerge.getTimestamp()`. This is policy input only.

Block timestamps are set by the proposing peer from its wall clock and are an
arbitrary signed long — they are *claims*, bounded by the rules below.

### Backdating (deterministic bound)

`State.checkBlock` rejects Blocks older than consensus time minus
`MAX_BLOCK_BACKDATE` (15 minutes; State.java:237), and Blocks misordered relative to
the proposing peer's previous Block (State.java:232). These are valid deterministic
rules because they compare Block time against *State* time — the same on every peer.

### Forward-dating (peer-local confirmation policy) — #595

There is deliberately **no forward validity bound**: "too far in the future" is
relative to the observer's clock, which differs between peers and between original
execution and replay. A far-future Block cannot be *invalid* — but honest peers
**decline to advance consensus past it until their own clocks reach its timestamp**.
The design is deliberately two-staged, separating the safety-critical core from the
subtler liveness half.

**Stage (i) — confirmation clamp (safety, minimal).** In belief merge, after
`updateConsensus` computes this peer's consensus points (BeliefMerge.java:211), clamp
each level's point so it never exceeds the length of the longest Block prefix all of
whose timestamps lie within `wallClock + MAX_BLOCK_FORWARD` (the *horizon*), floored
at the peer's current point so nothing is ever retracted. This is a small, monotone,
purely local change: the peer publishes consensus points that never finalise — and so
never execute — a future-dated Block.

Network confirmation at each level requires ≥2/3 effective stake to agree at the level
below, and execution happens at finality, the top level. So if an honest supermajority
clamps its points, **a future-dated Block cannot be finalised (hence cannot execute,
hence cannot advance the consensus clock) before real time reaches its timestamp**, no
matter who proposed it. The clock-teleport, and with it the #413-amplified mass
withdrawal, is closed. Safety rests only on the 2/3 threshold and the standard
honest-majority (< 1/3 adversarial stake) assumption. The clamp needs no global
timestamp ordering of Blocks — the horizon is simply the first out-of-window Block
scanned from the front, wherever it happens to sit.

Its accepted cost is a **liveness wedge under active attack**: because the
out-of-window Block still occupies its position in the ordering, legitimate Blocks
behind it also wait until the horizon advances past it. This is bounded (it clears as
real time passes), attributable (the Block is signed by a staked peer) and punishable
under governance. Safety is bought at the price of attack-time throughput behind the
offending Block — an acceptable trade for the minimal, obviously-correct core.

**Stage (ii) — ordering hygiene (liveness, subtle).** Removing out-of-window Blocks
from the *unconfirmed tail* of the ordering (and re-admitting them once the clock
catches up) so legitimate Blocks flow *around* a deferred one instead of queueing
behind it. This is where `filterBlocks` (BeliefMerge.java:399, the reserved no-op
stub) belongs. It rewrites the Block vector, so it interacts with proposal points,
proposal-switching patience (`KEEP_PROPOSAL_TIME`), prefix scoring and re-admission
ordering — none of it obvious. It is a follow-up, must ship with a belief-merge
simulation suite, and is **not required for safety**.

`MAX_BLOCK_FORWARD` is a small clock-skew allowance (order of seconds — generous for
NTP-disciplined peers). Note the intentional asymmetry with the 15-minute backdate
bound: the backward bound is state-relative and deterministic (a validity rule), the
forward bound is wall-clock-relative and policy (a confirmation rule). They answer
different questions and live on opposite sides of the determinism boundary.

Status: design agreed; stage (i) tracked in
[#595](https://github.com/Convex-Dev/convex/issues/595), stage (ii) as its liveness
follow-up. Until stage (i) lands, the mitigation for upgrade scheduling is generous
activation lead time (see UPGRADE.md).

## Security analysis

### Threat model

Peers are public-by-default infrastructure and must be robust to arbitrary malicious
messages. An attacker may control peers with stake (up to the BFT bound), submit
arbitrary transactions, and craft arbitrary Belief/Order/Block data. Consensus safety
must not depend on any peer-local trust decision; client trust in a *specific* peer
(e.g. for transaction submission) is a separate, explicit choice.

### Attack surfaces and mitigations

- **Clock teleport (future-dated Blocks).** A staked peer proposes a Block dated far
  in the future; if confirmed, `applyTimeUpdate` jumps consensus time, wedging
  schedules and — post-#413 — firing every scheduled network upgrade at once (mass
  peer withdrawal). Mitigated by the stage (i) confirmation clamp above (#595): an
  honest 2/3 stake supermajority that refuses to finalise past its horizon prevents
  the teleport. Highest-priority hardening before production upgrade scheduling.
- **Backdated / replayed Blocks.** Bounded deterministically by `MAX_BLOCK_BACKDATE`
  and the per-peer monotonic Block timestamp rule (`checkBlock`). A Block cannot be
  resurrected outside the window or reordered within a peer's own sequence.
- **Future-dated Orders (gossip level).** Belief merge ignores Orders timestamped
  beyond the peer's own clock (BeliefMerge.java:121), so an attacker cannot make
  their Belief artificially "newest" to win timestamp-based tie-breaking.
- **Stake-weight manipulation.** Consensus advancement requires 2/3 of *effective*
  stake; effective stake decays for peers not producing Blocks, so parked or
  partitioned stake loses influence (`computeStakes` + `Economics.stakeDecay`).
  Sub-`MINIMUM_EFFECTIVE_STAKE` peers cannot produce Blocks at all. Delegated stake
  adds to a peer's weight but remains withdrawable by the delegator only
  (per-staker isolation; see `StakingTest`).
- **Peer-record manipulation.** All peer mutation (`set-peer-data`,
  `set-peer-stake`, eviction of a well-staked peer) is gated to the peer's
  *controller address* — matching the peer's public key with one's own `*key*`
  confers nothing (#601; adversarial tests in `CoreTest`). Eviction of the last peer
  is refused as a liveness guard (Context.java:2121). Sub-threshold peers may be
  evicted by anyone, which is deliberate garbage collection of dead weight.
- **Front-running by the submitting peer.** A peer sees transactions before
  including them in a Block and could insert its own first. This is inherent to the
  submission model and **by design**: the mitigation is client choice of a trusted,
  well-staked peer (whose stake is forfeitable under governance), not a protocol
  patch. Do not treat proof-of-concept demonstrations as vulnerabilities.
- **Transaction replay.** Prevented deterministically by per-account sequence
  numbers, checked in the state transition, not by any peer policy.
- **Message-level DoS.** Bounded message sizes (`MAX_MESSAGE_LENGTH`), bounded
  missing-data requests (`MISSING_LIMIT`), bounded transactions per Block
  (`MAX_TRANSACTIONS_PER_BLOCK`), and juice metering on all execution (including
  compile/expand). Malformed data from untrusted peers must fail closed — parsing
  and merge paths must never throw into the peer loop.
- **Upgrade-boundary behaviour.** A peer that cannot apply a scheduled upgrade
  withdraws from consensus deterministically rather than diverging; the trigger is a
  consensus-visible, unforgeable on-chain schedule, so withdrawal is not selectively
  targetable. Full analysis in UPGRADE.md ("withdrawal as an attack surface").

### Known open items

- **Forward-timestamp handling** — design above;
  [#595](https://github.com/Convex-Dev/convex/issues/595). Stage (i) (confirmation
  clamp) closes the safety hole; stage (ii) (ordering hygiene) is the liveness
  follow-up.
- **Best-efforts stake withdrawal before an unsupported upgrade** —
  [#597](https://github.com/Convex-Dev/convex/issues/597); prevents a
  withdrawn-but-still-staked cohort from stalling the remaining supermajority.
- **Fork recovery** is currently disabled (`ENABLE_FORK_RECOVERY = false`);
  enabling it is tracked in [#492](https://github.com/Convex-Dev/convex/issues/492).
  With it disabled, peers filter Orders inconsistent with their own consensus rather
  than reorganising.
- **Proposal switching patience** (`KEEP_PROPOSAL_TIME`) is a fixed 100ms; the code
  notes possible randomisation to reduce synchronised flapping.
- **`create-peer` permits any 32-byte key.** An account may create (and control) a
  peer record for a public key it does not own; the key holder cannot be impersonated
  (Block signatures still require the private key), but the record itself can be
  squatted. Accepted for now; revisit if peer-identity registration becomes
  contested.
- **Eviction juice.** `evictPeer` extends the juice limit per delegated stake refund
  so eviction always completes (Context.java:2129); the loop is bounded by the number
  of delegators but is effectively unpriced. Accepted: refunds only move value to its
  owners.

## Design decisions

| Question | Decision |
|---|---|
| Forward bound: validity or confirmation policy? | **Confirmation policy** in belief merge. A wall-clock-dependent validity rule would break replay determinism — two honest peers could disagree on the same Block. |
| Split into two stages? | **Yes.** Stage (i) is a monotone clamp on the peer's own consensus points after `updateConsensus` — minimal and obviously correct, and it alone closes the safety hole. Stage (ii) (ordering hygiene in `filterBlocks`) is the subtler liveness half and a separate follow-up, so fork-risky vector rewriting is not bundled with the safety fix. |
| Symmetric with the backdate bound? | **No, deliberately.** Backdate is state-time-relative (deterministic, 15 min, lenient); forward is wall-clock-relative (policy, seconds, tight). |
| Hard deterministic backstop (reject Blocks dated beyond state time + large constant)? | **No.** It reintroduces the determinism hazard for zero gain: the confirmation clamp already prevents early execution, and a backstop could invalidate a legitimately-delayed Block on replay boundaries. |
| Does stage (i) rely on Blocks being timestamp-sorted? | **No.** `appendNewBlocks` never globally re-sorts, so out-of-window Blocks are not necessarily a suffix. The horizon is the first out-of-window Block from the front; the clamp caps confirmation there regardless of position. |
| Can the clamp stall consensus? | **Honest case, no** — it never retracts (monotone, floored at the current point) and in-window Blocks are unaffected. **Under active attack, yes, partially** — Blocks queued behind an out-of-window Block wait until the horizon advances. That wedge is bounded and attributable; stage (ii) removes even that. |

## Testing principles

Consensus code changes carry fork risk and are held to a higher standard:

- **Determinism tests**: identical inputs must produce bit-identical States across
  construction paths and replay (`SnapshotStateTest` replays recorded testnet Blocks
  against a golden state hash — any tier-2 behaviour change that moves it must be
  version-gated, see UPGRADE.md).
- **Policy tests use injected clocks**, never sleeps: the peer clock is injectable
  (introduced with #413's freeze tests), so timestamp scenarios (deferral, boundary
  crossing, skewed peers) are deterministic.
- **Adversarial tests** accompany every consensus-adjacent operation: authorization
  (non-controller rejection), conservation (total supply invariant after any
  stake/evict sequence), malformed input (wrong-length keys, bad types), and
  liveness guards (last-peer eviction).
- **Belief-level tests** (`BeliefMergeTest`, `BeliefVotingTest`) exercise merge
  convergence with multiple peers, differing stakes and orderings.
