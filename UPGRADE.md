# Convex Network Upgrades

Design document for applying version upgrades to a running Convex network without breaking chain identity or consensus.

**Tracking issue:** [#413 — Protocol upgrade vector](https://github.com/Convex-Dev/convex/issues/413)

**Dependent issues** (cannot be fixed without this mechanism, because a naive fix changes the genesis hash):

- [#533 — `update` doesn't apply all arguments](https://github.com/Convex-Dev/convex/issues/533)
- [#528 — `add-mint` behaviour when `:max-supply` is not specified](https://github.com/Convex-Dev/convex/issues/528)
- [#354 — `schedule` cannot access local bindings](https://github.com/Convex-Dev/convex/issues/354)
- [#208 — Regression: cannot call library macros](https://github.com/Convex-Dev/convex/issues/208)

## Motivation

The Convex network must evolve. CVM semantics, core functions, encoding rules, juice costs, consensus parameters, and on-chain libraries all need room to improve after launch. A naive approach — simply change the code — breaks the network: peers running different versions compute different states, diverge from consensus, and the chain forks.

We need a mechanism that:

- Preserves the **genesis hash**. Genesis is bedrock identity — once set, it never changes.
- Allows **arbitrary state patches** at upgrade time (migrations, recompiled core, repaired data).
- Allows **transition function changes** (new opcodes, changed juice costs, new casts, fixes to core function bugs).
- Keeps all honest peers in **lock-step** so consensus never diverges across an upgrade boundary.

## Non-goals

- Changing the genesis state hash. If a change requires this, the correct route is a new network, not an upgrade.
- Per-peer opt-in behaviour. Upgrades are network-wide and binding.
- Dual-mode CVM runtime. Peers on the old version stop participating correctly at the activation point; there is no backwards-compatible execution path.

## Core principles

### 1. Genesis is immutable

Every Convex network is identified by the hash of its genesis state. Upgrades operate on the evolving lattice state *after* genesis — never on genesis itself. Replaying history from genesis must reproduce the current tip, including the effect of any upgrades that have fired.

### 2. An upgrade is a transition event recorded in the chain

Conceptually an upgrade is a special transition that peers apply at a known point in consensus. Like a regular transaction it:

- Has a deterministic effect given the pre-state.
- Is recorded in the chain, so replay reproduces it.
- Is observable to anyone syncing history.

Unlike a regular transaction it:

- Is not submitted by an account.
- Is not bound by CVM juice, access control, or environment rules.
- May mutate any part of the state, including protected core libraries, account code, or global constants.

### 3. Timestamp-gated activation

Each upgrade carries an **activation timestamp** — a specific instant in consensus time. The first block whose consensus timestamp is greater than or equal to the activation timestamp triggers the upgrade as its first step, before ordinary transactions in that block are applied.

Timestamp-gating (rather than block-height-gating) is chosen because:

- CPoS consensus already advances a well-defined monotonic consensus clock.
- Peer operators and users coordinate on wall-clock dates more naturally than block numbers.
- Block cadence varies, but timestamps are shared and stable.

Every peer runs the same decision — "is the next block's consensus timestamp ≥ activation?" — and reaches the same answer at the same point, because the timestamp is part of shared consensus state.

### 4. Upgrade = (state patch, version bump)

An upgrade is a pair:

- **State patch** — a pure function `State → State`. May do anything: rewrite core library code, set juice constants, insert a repaired value, convert a data structure format, register a new CNS entry, etc.
- **Version bump** — a change in the effective CVM version recorded in state. After the bump, the transition function for subsequent transactions uses the new code path (new opcodes, new juice, new core semantics, new encoding rules).

Both halves may be non-empty; either may be a no-op. A pure bug-fix in core semantics is typically "version bump, trivial state patch". A data migration with no semantic change is "no-op version bump, real state patch". Most upgrades are both.

## What an upgrade may do

An upgrade may do anything a normal transaction cannot:

- Replace the bytecode of a core library or actor.
- Adjust `*memory-price*`, juice table entries, or consensus parameters.
- Repair state that violates a new invariant.
- Introduce new CVM opcodes, cast rules, or error codes.
- Restructure encodings, provided the state patch rewrites existing cells into the new form.

An upgrade is **not** bound by:

- Juice limits.
- Account or controller permissions.
- Signature checks at application time (no transaction is being signed — peers apply the upgrade deterministically from its published definition).
- The "pure function of transaction input" rule that governs normal CVM execution.

## How an upgrade is published

An upgrade proposal contains:

| Field            | Purpose                                                                |
| ---------------- | ---------------------------------------------------------------------- |
| `id`             | Unique identifier (hash of the full upgrade definition)                |
| `activation`     | Consensus timestamp at which the upgrade fires                         |
| `target-version` | CVM version number the network moves to                                |
| `state-patch`    | Deterministic operation on `State` — compiled Java or encoded CVM form |
| `description`    | Human-readable changelog entry                                         |
| `signatures`     | Governance signatures authorising the upgrade                          |

The proposal is distributed to peers ahead of activation through release tooling, peer CLI, or on-chain registration. A peer that does not possess the upgrade definition by activation time stalls at the boundary and must update before resuming consensus participation.

## Determinism and consistency

The upgrade's effect must be **bit-identical** across every peer that applies it. This implies:

- The state patch is a pure function of the pre-state; it takes no external input, clock, or randomness.
- The patch is authored in compiled Java (or encoded CVM) shipped as part of the peer release — it is not user-supplied code at activation time.
- The `id` is the hash of the full upgrade definition (patch + target-version + activation). Peers verify they hold the same definition before applying.
- Replaying history from genesis, a peer with access to all historical upgrade definitions produces exactly the current tip.

A peer that applies a *different* patch — or applies the correct patch at the wrong timestamp — diverges from consensus and is treated as faulty.

## The chain record

After an upgrade fires, state records:

- The current CVM version.
- The history of applied upgrades: `id`, activation timestamp, target version.

This allows any peer replaying from genesis to know, at each point in history, which transition function and which on-chain libraries are in effect. Replay without access to a historical upgrade definition fails loudly rather than silently producing wrong state.

## Governance

*Who* may author and release an upgrade is orthogonal to this mechanism. Candidate policies:

- Foundation release with multi-signature approval (simple, pragmatic).
- On-chain governance vote gating activation.
- Staked-peer supermajority signalling.

This document specifies the mechanism. Governance policy is a separate decision recorded per-network.

## Rollback

Upgrades are one-way. A broken upgrade is corrected by a subsequent upgrade with a later activation timestamp, not by reversion. Reverting state across peers that have already advanced is a consensus fork, not a rollback. An upgrade authored to *undo* a prior upgrade is permitted and is itself a normal upgrade.

## Example scenarios

### Scenario A — fix a core function bug

Issue #533: `(update m k f a b)` fails to apply multi-argument `f`. The fix changes the `update` definition in core, which would alter the genesis hash if applied naively.

Upgrade approach:

- **State patch** — replace the compiled `update` binding in the core environment with the fixed version.
- **Version bump** — `target-version = N+1`.
- **Activation** — timestamp four weeks out, announced to peer operators.

Peers on version N execute the old (buggy) `update` up to activation. At activation every peer atomically replaces the core binding. From activation onward every peer executes the fixed `update`. Genesis hash unchanged. History intact.

### Scenario B — adjust juice costs

DoS analysis shows opcode X is under-priced.

- **State patch** — write new juice table entries into the global juice map.
- **Version bump** — `target-version = N+1`.
- **Activation** — timestamp chosen with operator notice.

### Scenario C — repair malformed state

A prior bug left a handful of actor environments holding non-canonical values. A targeted upgrade rewrites exactly those cells.

- **State patch** — a small audited function that updates the affected accounts.
- **Version bump** — none (no transition function change).
- **Activation** — next maintenance window.

## Open questions

- Serialisation format for an upgrade definition in the chain record.
- Whether upgrades should be expressible in CVM code (for auditability) or remain Java-only (for breadth of effect).
- Forward-compatibility policy: should a peer lacking a known-future upgrade stall at activation, or refuse to start at all?
- Etch interaction: some upgrades may require store migration beyond state replacement.
- Attestation: must peers announce the set of known upgrades they are prepared to apply, so divergence is detectable before activation?

## Status

Design draft. Not yet implemented. Intended for inclusion before mainnet launch, so the upgrade mechanism itself is in place before it is first needed.
