---
name: protocol-versions
description: Protocol versions, migrations and the v1 upgrade — which semantics to write against, and how to change CVM behaviour without forking the network. Use when changing core functions, juice costs, encodings or on-chain libraries.
---

# Protocol Versions and Migrations

The **protocol version** is the count of upgrades applied to a network,
starting at `0` at genesis. Genesis is immutable — it is the network's
identity — so every change to CVM semantics after launch arrives as a scheduled
migration that increments the version by exactly 1.

Full design: `convex-core/docs/UPGRADE.md`. Read it before authoring a
migration; this skill is orientation and conventions.

## Write Against v1

**v1 is the target.** It is the first upgrade, it bundles every bug known at
genesis, and it is what networks will be running. Unless you are specifically
reasoning about live or historical behaviour, v1 semantics are the semantics.

This is the project's own test convention — three state roles, each answering
a different question:

| Role | Version | Use |
|------|---------|-----|
| **Target** — `InitTest.UPGRADED` / `BaseTest.UPGRADED` | `MAX_VERSION` | **The `ACVMTest` default.** Libraries, actors and CVM behaviour are tested against where the network is going. |
| **LIVE** — `InitTest.LIVE` / `BaseTest.LIVE` | `Migrations.LIVE_VERSION` | The release gate: what live peers run today, so a release does not break them before they upgrade. |
| **Genesis** — `InitTest.STATE` / `BaseTest.STATE` | `0` | Pinned where genesis is the point: genesis-hash invariants, replay (`SnapshotStateTest`), and intended-diff contrast tests (`MigrationFixesTest`). |

`Migrations.LIVE_VERSION` is bumped **when, and only when, the live network
applies an upgrade**. Everything pinned to LIVE follows automatically.

## What v1 Contains

The bootstrap (installing `schedule-upgrade` / `unschedule-upgrade`) plus
fixes that could not ship any other way, because a naive fix would move the
genesis hash:

| Migration | Fixes |
|-----------|-------|
| `v1-core.cvx` | `update` / `update-in` variadic arities (#533); quasiquote of sets/maps, `~false`, `define` double-evaluation, `call` arity (#598) |
| `v1-trust.cvx` | `trusted?` now fails closed against defective monitors (#623) |
| `v1-fungible.cvx` | `add-mint` `:max-supply` defaults to unlimited rather than 0 (#528) |
| `v1-asset.cvx`, `v1-box.cvx`, `v1-delegate.cvx`, `v1-metadata.cvx`, `v1-multi-token.cvx`, `v1-nft-basic.cvx`, `v1-nft-simple.cvx` | library fixes (#600, #620, #621, #622, #623) |

Two of these change behaviour agents rely on — see the `trust` and `token`
skills, which document the v1 behaviour and flag the pre-v1 trap.

## Changing CVM Behaviour

Where the change lives determines the strategy.

**Tier 1 — state-resident core.** Functions defined in `core.cvx` are compiled
into account `#8` at genesis; they are *state*. Fix by migration: replace the
binding with the recompiled definition. No Java version branch.

Caveat: the compiler statically links core symbols, so already-deployed actors
keep the old embedded function unless the migration also sweeps environments.
Whether to sweep is a per-upgrade decision.

**Tier 2 — native semantics.** Opcodes, native core functions, juice costs and
cast rules are the transition function, in Java. These change by version-keyed
dispatch:

```java
long cost = (state.getProtocolVersion() >= 3) ? Juice.NEW_COST : Juice.OLD_COST;
```

**Whether a gate is needed is decided by replay evidence, not judgement.** Make
the change unconditional locally and run `SnapshotStateTest`: if the replay hash
moves, recorded history exercised the old semantics and the gate is mandatory;
if not, it ships unconditionally with no permanent branch. Branches are
permanent — replay from genesis needs every historical semantics.

**Tier 3 — encodings.** Decoding happens outside any state context, so it
**cannot** branch on version. Decoders stay permissive of all historical forms
forever; the version gates what the CVM *writes and canonicalises*. See the
`cad3-encoding` skill — this is why a permissive decoder is correct rather than
a bug.

## Authoring a Migration

- **Purity is a hard contract.** A migration is `State → State` with no clock,
  randomness or I/O. An impure migration forks the network.
- **The list is append-only forever.** Position is identity —
  `Migrations.get(k)` produces version `k+1` — so never insert or reorder.
- **Test the exact delta.** Every migration needs tests asserting precisely
  what changed *and* that nothing else did (compare untouched subtrees by hash).
- Failure at an activation boundary makes peers **withdraw** from consensus
  rather than produce a state — a stall is recoverable, divergence is not.

`MigrationFixesTest` owns the intended differences between genesis and
upgraded semantics; add to it when a migration changes observable behaviour.

## Fresh Networks

Launchers that create a new genesis (`local start`, `peer genesis`,
`peer start --genesis`, GUI local networks) apply all migrations at creation,
so a **fresh network starts at `MAX_VERSION`** — it has no history to preserve
and should not launch with known-fixed bugs.

Pin a lower version with `--protocol-version` (CLI) or `:protocol-version`
(peer config) to mirror a network that has not yet upgraded. This is how you
reproduce live behaviour locally while v1 is pending.
