# Convex Network Upgrades

Design for applying version upgrades to a running Convex network without breaking chain identity or consensus. Covers both the governing principles and the concrete implementation design.

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
- Allows **arbitrary state migrations** at upgrade time (data conversions, recompiled core, repaired data).
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

Each upgrade carries an **activation timestamp** — a specific instant in consensus time. The first block whose timestamp is greater than or equal to the activation timestamp triggers the upgrade as its first step, before ordinary transactions in that block are applied.

Timestamp-gating (rather than block-height-gating) is chosen because:

- CPoS consensus already advances a well-defined monotonic consensus clock.
- Peer operators and users coordinate on wall-clock dates more naturally than block numbers.
- Block cadence varies, but timestamps are shared and stable.

Every peer runs the same decision — "is this block's timestamp ≥ activation?" — and reaches the same answer at the same point, because the block timestamp is shared consensus data.

Checking the *block* timestamp is equivalent to checking the consensus clock: state time only advances via block timestamps, so the consensus clock cannot pass an activation before some block timestamp does — and that block fires the upgrade.

### 4. Upgrade = one atomic transition: migration + version increment

An upgrade is a **single atomic state transition** with two effects:

- **Migration** — a pure function `State → State`. May do anything: rewrite core library code, set juice constants, insert a repaired value, convert a data structure format, register a new CNS entry, etc. May be trivial (for a pure semantics change).
- **Version increment** — the protocol version recorded in state increases by **exactly 1**. Never optional, never more. After the increment, the transition function for subsequent transactions uses any new code path keyed on the new version (new opcodes, new juice, new core semantics, new encoding rules).

The protocol version is therefore simply a **count of applied upgrades**: version N means N upgrades have fired. An upgrade is identified by the **version number it produces** — migrations are not named: the migration producing version `k+1` is bound positionally in JVM code. De-duplication is inherent: the watermark passes each version exactly once. Several upgrades whose activations have all passed apply sequentially within one block, each incrementing the version.

### 5. The upgrade schedule is consensus state

Which upgrades are pending, and when they activate, must be knowledge shared *in consensus state* — not peer-local knowledge. This is a soundness requirement, not a convenience: a peer can only withdraw for an upgrade it knows about. If the schedule were distributed out-of-band, a peer that never received it would not withdraw at activation — it would keep executing old rules and silently fork. With the schedule on-chain, "apply, withdraw, or continue" is a deterministic function of state for every peer, including peers that lack the migration itself.

### 6. Upgrade logic lives in the JVM, not the CVM

Both the migrations and the machinery that schedules and applies them are **static JVM code**, reviewed and shipped in peer releases. Nothing consensus-critical about the mechanism is implemented as CVM-resident actor code: an actor enforcing its own governance checks would be upgradeable code that itself may need upgrading, and could harbour exactly the class of bugs this mechanism exists to fix. The CVM surface is limited to one native core function for scheduling; validation, application, and version accounting are all native.

## Current state of the code

The transition-function seam is ready; the version-tracking state is not. What exists today:

| Concern | Status | Location |
| ------- | ------ | -------- |
| Pre-transaction hook point | **Present** — `prepareBlock` runs block-number bump → time update → scheduled transactions, in order, before block transactions | `convex-core/.../cvm/State.java:250` (`prepareBlock`), `:182` (`applyBlock`) |
| Monotonic consensus clock | **Present** — `applyTimeUpdate` advances the clock and already performs threshold-crossing logic (memory-pool growth) | `State.java:269` |
| Governance accounts | **Present** — genesis system accounts occupy addresses below the core library: `FOUNDATION` #2, `GOVERNANCE` #6, `ADMIN` #7; core library at `CORE_ADDRESS` #8 | `init/Init.java:41-53` |
| Protocol global slot | **Stubbed, not wired** — `GLOBAL_SYMBOLS` reserves `PROTOCOL` at index 6 (`GLOBAL_PROTOCOL=6`, `// TODO: move to actor?`) | `State.java:74`, `:83` |
| Protocol global value | **Missing** — `INITIAL_GLOBALS` has only 6 entries (indices 0–5) | `Constants.java:97` |
| Version accessor | **Missing** — no `getProtocolVersion()`; nothing reads or writes globals index 6 | (absent) |
| Genesis wiring | Genesis uses `INITIAL_GLOBALS` verbatim, so state carries no version | `init/Init.java:173` |
| Scheduling core function / `Migrations` class / activation hook | **Missing** | (absent) |

## What an upgrade may do

An upgrade may do anything a normal transaction cannot:

- Replace the bytecode of a core library or actor.
- Adjust `*memory-price*`, juice costs, or consensus parameters.
- Repair state that violates a new invariant.
- Introduce new CVM opcodes, cast rules, or error codes.
- Restructure encodings, provided the migration rewrites existing cells into the new form.

An upgrade is **not** bound by:

- Juice limits.
- Account or controller permissions.
- Signature checks at application time (governance is verified when the upgrade is *scheduled*, via a normal transaction from a governance account — application is then deterministic and unsigned).
- The "pure function of transaction input" rule that governs normal CVM execution.

## Strategy for CVM core changes

Where the change lives determines the strategy. Three tiers, in order of preference:

### Tier 1 — state-resident core: migrate, no branching

Core functions defined in `core.cvx` are compiled into the core environment (account `#8`) at genesis — they are *state*, not JVM behaviour. Fixing one is a pure migration: replace the binding with the recompiled definition from the updated `core.cvx` shipped in the release. No version branch is needed anywhere in Java, and replay is automatic: historical states carry the historical compiled code.

**Static-linking caveat.** The compiler statically links core symbols (`Constants.OPT_STATIC`, `Constants.java:135`), so code compiled *before* the upgrade embeds the old function value directly. Replacing the `#8` binding fixes future compilations; already-deployed actors keep the embedded old function unless the migration also sweeps account environments and rewrites embedded references. Whether to sweep is a per-upgrade decision: a semantics fix (e.g. #533) probably should; leaving old actors behaving as-deployed is also coherent.

### Tier 2 — native semantics: branch on protocol version

Opcode implementations, native core functions, juice costs (`Juice.java` — JVM constants, not state), and cast rules *are* the transition function, in Java. These change by **version-keyed dispatch**:

```java
long cost = (state.getProtocolVersion() >= 3) ? Juice.NEW_COST : Juice.OLD_COST;
```

- The upgrade that activates the behaviour is a trivial (often identity) migration — the version increment alone flips the branch.
- Branches are **permanent**: replay from genesis needs every historical semantics, exactly as the `Migrations` list keeps every migration. Same append-only discipline.
- Keep branch points at narrow, well-defined seams — juice lookup, opcode dispatch, cast rules — not scattered ad hoc through the runtime. (This is also why the version is a bare `Long` global: the read sits on the hot path.)

This is **not** the "dual-mode CVM runtime" excluded in Non-goals. That exclusion means old *releases* don't keep participating after activation. Every current release implements all historical semantics for replay, but exactly one behaviour is live at any point in history — selected deterministically by the version in state.

### Tier 3 — encoding changes: decoders stay permissive

Cell encodings are read outside any state context (Etch, network messages), so decoding **cannot** branch on the state version. Instead: decoders remain backwards-compatible with all historical forms permanently; what the version gates is what the CVM *writes and canonicalises*. Where existing cells must take the new form, the migration rewrites them. (This matches existing CAD3 practice: decoders accept non-canonical forms; canonicalisation is a CVM-layer concern.)

## Implementation design

Four components: state (the protocol globals), a native core function (scheduling), peer software (the `Migrations` class), and the transition-function hook (activation).

### On-chain format: two flat globals

Two entries in the globals vector, following the existing flat positional convention (a related pair already spans two slots: `GLOBAL_MEMORY_MEM` + `GLOBAL_MEMORY_CVX`):

| Index | Symbol | Type | Meaning |
| ----- | ------ | ---- | ------- |
| 6 (`GLOBAL_PROTOCOL`, reserved today) | `protocol` | `Long` | Protocol version — the number of upgrades applied. |
| 7 (new) | `upgrades` | `Vector` of `Long` | The **upgrade vector**: entry `k` is the consensus timestamp (ms) at which the upgrade producing version `k+1` fires (or fired). |

This keeps the top-level State record format (`ACCOUNTS, PEERS, GLOBALS, SCHEDULE`) unchanged — globals is a vector, so it extends naturally — and the version stays a bare `Long`, the cheapest possible read for transition code that keys on it. (Nesting both into one slot was considered and rejected: its only real benefit, atomic update of the pair, is already guaranteed because the whole State is one immutable value.)

There is no per-entry structure at all. Migrations are not named on-chain — the migration for each version is bound positionally in JVM code (see the `Migrations` class below) — so an upgrade's entire on-chain footprint is a single timestamp.

The version is a **watermark** into the upgrade vector:

- Entries `[0, version)` are **applied**; entry `k` is the upgrade that produced version `k+1`.
- Entries `[version, count)` are **pending**.

Invariants, holding in every committed state:

1. `0 ≤ version ≤ count(upgrades)`.
2. Activations are non-decreasing along the vector. Equal timestamps are permitted — those upgrades fire in the same block, in order.
3. Every pending activation is strictly greater than the state timestamp (scheduling accepts only future activations, and application advances the watermark past any activation the block time has reached before the timestamp updates).
4. Absent globals read as defaults — version `0`, empty vector — which is how pre-bootstrap states present themselves.

Because migrations bind to versions by position, the pending region is managed strictly at the **tail**: scheduling appends, unscheduling pops. Inserting or removing mid-vector would silently re-bind later activations to different migrations.

The applied prefix is **immutable forever**. Applying an upgrade changes nothing in the vector — the watermark simply advances over the entry, a single `Long` update, preserving maximal structural sharing. The triggering block of a historical upgrade is not stored: it is derivable by replay, and the activation timestamp is the operationally meaningful datum. The format itself can be evolved by an upgrade, like everything else in state.

- Add `State.getProtocolVersion()` (the watermark; absent global → 0) and an upgrade-vector accessor (absent → empty), mirroring the existing `GLOBAL_*` accessors (`State.java:812`, `:901`).
- This resolves the `// TODO: move to actor?` on `State.java:83`: the values stay in globals — consensus-critical, always present, native to read — rather than in any account's environment.

**Genesis is never touched — on any network.** `Init` and `INITIAL_GLOBALS` remain exactly as they are (6 globals); every network, new or existing, starts at version `0` via the absent-reads-as-default rule. The protocol globals and the `schedule-upgrade` core binding are installed by **migration v1 (the bootstrap)**: migrations operate on the *evolving* state, so firing it extends the globals vector — version `1`, upgrade vector `[activation]` — and adds the core environment binding. Replay reproduces this from the recorded upgrade. What must precede activation is the **activation hook in peer code** — a software release, not a state change. The bootstrap upgrade is the one permitted exception to on-chain scheduling (there is nowhere to schedule it yet): its activation is hardcoded per-network in the peer release that carries it, and once the globals exist the watermark ensures it never refires. Bootstrap state changes need rigorous tests asserting the exact expected deltas.

### Scheduling: a native core function

Upgrades are scheduled by a new **core function** (provisional name `schedule-upgrade`), defined in the core environment (account `#8`) and implemented as static JVM code — per principle 6, no actor and no CVM-resident validation logic.

```clojure
(schedule-upgrade activation)   ;; append: schedules the next unscheduled version; returns the version it will produce
(unschedule-upgrade v)          ;; pop: removes the pending entry for version v, which must be the last (escape hatch if a migration will not ship)
```

- **`activation`** — the consensus timestamp at which the upgrade fires.
- **Gating** — callable only by **governance accounts: addresses below `#8`** (the genesis system accounts — `FOUNDATION` #2, `GOVERNANCE` #6, `ADMIN` #7, etc.; `Init.java:41-53`). Calls from any other address fail natively. Richer policies (multi-signature, an on-chain vote contract) are composed by setting such a policy actor as the *controller* of a governance account, without the upgrade mechanism knowing or caring.
- **Validation** — the activation must be strictly in the future and not less than the last entry in the vector; `unschedule-upgrade` removes only the tail pending entry. Together with the address gate, that is everything: these checks maintain the format invariants (non-decreasing activations; pending entries always beyond the watermark; no positional re-binding).

There is nothing to name or hash on-chain: migrations are selected purely by version number, bound positionally in JVM code. Trust derives from the governance-signed scheduling transaction and review of the release that ships the migration. The human-readable description of each upgrade lives in the release notes and `CHANGELOG.md`, keyed by version number; the on-chain record stays minimal.

**Scheduling is deliberately decoupled from migration availability.** Validation is a pure function of *state* — it must never consult the local migration list, or transaction validity would depend on which release executes it and peers on different releases would fork. A peer supporting version N processes the scheduling of version N+1 like any other transaction, keeps operating normally while the entry is pending, and stops exactly at the transition block — which, by definition, it cannot run (missing migration → withdraw). Peers that updated in time apply it and continue.

A useful defence-in-depth property: scheduling can only *time* migrations that exist in reviewed, released peer code. Compromise of a governance key alone cannot inject code into the network — it can only activate (or mis-time) something the release process already shipped.

### The `Migrations` class (JVM side)

The peer release carries a `Migrations` class holding the **ordered list of migrations** — position is identity: `Migrations.get(k)` is the migration producing version `k+1`. No names, no lookup keys.

```java
public class Migrations {

    /** Ordered: entry k produces protocol version k+1. Append-only. */
    private static final List<Migration> ALL = List.of(
            new Bootstrap()            // v1: schedule-upgrade binding + protocol globals
            // new FixUpdate533()      // v2: replace core `update` binding
    );

    public static Migration get(long k) { ... }

    public interface Migration {
        /** Pure function: same pre-state → same post-state. No clock, no randomness,
         *  no I/O. Not bound by Juice, permissions, or signature checks. */
        State apply(State preState);
    }
}
```

- A migration is ordinary source code in the release and may reference any new source shipped alongside it — a recompiled core function, a new opcode implementation, a data-conversion helper.
- A release supports protocol versions up to the length of the list; a due version beyond that is a **missing migration** (see failure modes).
- Purity is a hard contract. A migration that reads a clock, randomness, or external state forks the network. Enforced by review and by the determinism tests below.
- The list is append-only **forever**: replay from genesis requires every historical migration, so old migrations remain compiled into every future release. This is an accepted, permanent maintenance cost. (A future trust-a-snapshot sync policy could make ancient migrations optional for non-archival peers; that is a separate decision.)

### Activation in `prepareBlock`

The upgrade step is the **first** action in `prepareBlock` (`State.java:250`), ahead of the block-number bump, so the entire block — time update, scheduled and ordinary transactions alike — executes under the new transition function:

```java
private State prepareBlock(Block b) {
    State state = this;
    state = state.applyUpgrades(b.getTimeStamp());   // NEW — first step
    AVector<ACell> globals = state.getGlobals();
    long blockNum = state.getBlockNumber();
    state = state.withGlobals(globals.assoc(GLOBAL_BLOCK, CVMLong.create(blockNum + 1)));
    state = state.applyTimeUpdate(b.getTimeStamp());
    state = state.applyScheduledTransactions();
    return state;
}
```

`applyUpgrades(timestamp)` algorithm:

1. Read the version and upgrade-vector globals (absent read as `0` / empty). On a network whose state predates them, the pending set is the release-hardcoded bootstrap activation, appended as it fires.
2. While the entry at the watermark — position `version` in the upgrade vector — has `activation ≤ timestamp`:
   - Take `Migrations.get(version)`. **Not present (release too old) → withdraw** (see failure modes).
   - `state = migration.apply(state)`.
   - Advance the watermark: `version += 1`. Nothing else in the vector changes.
3. Return the migrated state.

Activations are non-decreasing, so "the entry at the watermark" is the entire selection logic, and de-duplication is positional — the watermark never revisits an entry. Multiple upgrades fire in one block if several activations have passed (e.g. a long gap between blocks) — the while-loop handles this. When nothing is due, the step is one comparison against the entry at the watermark.

### Failure modes

Every failure of upgrade application resolves to the same safe behaviour: **produce no state; the peer withdraws from consensus participation**. The governing rule: a failure must **never** become an invalid-block result. Invalid-block outcomes are consensus history, so a later release with a corrected migration would recompute those blocks differently on replay, splitting replay from the live network. By withdrawing, nothing is committed at the boundary; the corrected release then defines the single outcome — identical for rejoining peers and for replay from genesis.

Failure classes (distinguished only for operator diagnostics — behaviour is identical):

- **Missing migration** (deterministic, network-relative). The schedule is in state, so a peer *knows* an activation has arrived that its code version cannot apply. It withdraws and logs "update required"; it rejoins automatically once running a release containing the migration. It never guesses or skips.
- **Failing migration** (deterministic). A migration bug throws identically on every peer running that release: the whole network stalls at the boundary until a corrected release ships — deliberately, a stall is recoverable and divergence is not.
- **Environmental failure** (peer-local). Conditions such as missing local store data fail only on the affected peer, which withdraws with the underlying cause preserved; resync-and-retry may succeed with no release change. Other peers proceed normally.

Implementation contract: the **entire** upgrade machinery (vector reads, migration application, watermark advance) is guarded so that no exception escapes as a Java `Exception` — `applyBlock`'s catch-all (`State.java`) would convert it into exactly the forbidden invalid-block result. All failures surface as a dedicated `Error` (`UpgradeError`, carrying the version and cause) that passes through untouched to the peer layer. Fatal JVM conditions (e.g. out-of-memory) propagate unwrapped and use the peer's existing fatal handling.

Scheduling-time validation (above) ensures the transition function never sees a mis-authored schedule — malformed activations and unauthorised calls are rejected before they reach it.

### Attestation (advisory)

Peers advertise the highest protocol version their release supports — the length of the migration list — piggy-backed on status/handshake. This makes *readiness* visible before activation: operators can see what fraction of stake can apply a scheduled version and defer a planned activation if readiness is low. Attestation is advisory only — the binding signal is the on-chain schedule.

A peer should also warn its own operator **as soon as** the on-chain schedule contains a version beyond its supported version — "upgrade to version N scheduled at T; this release supports N-1; update before T" — rather than waiting to withdraw at the boundary.

## Determinism and consistency

The upgrade's effect must be **bit-identical** across every peer that applies it. This implies:

- The migration is a pure function of the pre-state; it takes no external input, clock, or randomness.
- The migration is compiled Java shipped as part of the peer release — it is not user-supplied code at activation time.
- The schedule is consensus state, so the *decision* to apply is identical everywhere; the version number indexes the identical migration in every peer's release.
- Replaying history from genesis, a peer running a release with all historical migrations produces exactly the current tip.

A peer that applies a *different* migration — or applies the correct migration at the wrong point — diverges from consensus and is treated as faulty.

## Replay and sync

- **Replay** (recomputing state from genesis) requires every historical migration; the applied prefix of the upgrade vector tells a replaying peer exactly which upgrades fire where. Reaching an activation without the corresponding migration **fails loudly** rather than silently producing wrong state.
- **New-peer sync** from a trusted state snapshot needs only the *pending* schedule (already in the snapshot) and a release carrying migrations for any activation it will cross.
- Schedule entries live on-chain and migrations ship in releases; no separate distribution channel is needed.

## Etch / store format

Most upgrades are pure `State → State` and need no store changes — new cells are written normally, old cells remain content-addressed and valid. A store-format conversion (beyond state replacement) is out of scope for the in-consensus mechanism and, if ever needed, is a peer-local operational step gated on the same activation — not part of the deterministic transition. Flag such upgrades explicitly in their release notes.

## Governance

Upgrade authority is **control of the genesis governance accounts** (addresses below `#8`), enforced natively by the scheduling function. Governance *policy* is then a matter of how those accounts' keys and controllers are managed per-network:

- Foundation-held keys with multi-signature approval (simple, pragmatic).
- A vote contract set as controller of a governance account, gating scheduling on an on-chain vote.
- Staked-peer supermajority signalling feeding either of the above.

This document specifies the mechanism. Governance policy is a separate decision recorded per-network.

## Rollback

Upgrades are one-way. A broken upgrade is corrected by a subsequent upgrade with a later activation timestamp, not by reversion. Reverting state across peers that have already advanced is a consensus fork, not a rollback. An upgrade authored to *undo* a prior upgrade is permitted and is itself a normal upgrade. (A *pending* upgrade that has not yet fired can simply be unscheduled.)

## Example scenarios

### Scenario A — fix a core function bug

Issue #533: `(update m k f a b)` fails to apply multi-argument `f`. The fix changes the `update` definition in core, which would alter the genesis hash if applied naively.

- **Migration** (tier 1) — replace the compiled `update` binding in the core environment with the fixed version, and decide whether to sweep account environments for statically-linked references to the old function (see the static-linking caveat).
- **Activation** — timestamp four weeks out; scheduled on-chain by a governance account, announced to peer operators, readiness tracked via attestation.

Peers on version N execute the old (buggy) `update` up to activation. At activation every peer atomically replaces the core binding. From activation onward every peer executes the fixed `update`. Genesis hash unchanged. History intact.

### Scenario B — adjust juice costs

DoS analysis shows opcode X is under-priced. Juice costs are JVM constants (`Juice.java`), so this is a tier-2 native change:

- **Code** — the release ships the new cost behind a version branch: `getProtocolVersion() >= N ? NEW : OLD`.
- **Migration** — identity; the version increment alone flips the branch.
- **Activation** — timestamp chosen with operator notice.

### Scenario C — repair malformed state

A prior bug left a handful of actor environments holding non-canonical values. A targeted upgrade rewrites exactly those cells.

- **Migration** — a small audited function that updates the affected accounts.
- **Activation** — next maintenance window.

The version still increments (it counts applied upgrades, not semantic changes) even though no transition-function code path keys on the new number.

## Resolved design decisions

| Question | Decision |
| -------- | -------- |
| Where upgrade logic lives | **Static JVM code** throughout — migrations *and* scheduling/validation. No actor: CVM-resident mechanism code would itself be upgradeable and bug-prone. The CVM surface is one native core function. |
| Scheduling authority | Native core function callable only by governance accounts (address < `#8`); richer policies via controllers of those accounts. |
| On-chain format | Two flat globals: `protocol` (`Long` version, a watermark) and `upgrades` (vector of activation timestamps; entry `k` fires the migration producing version `k+1`). No new cell type or encoding. |
| Nested vs flat globals | **Flat** — matches the positional globals convention (cf. the two memory-pool slots) and keeps the hot-path version read a bare `Long`. Nesting bought nothing: state commits are atomic regardless. |
| How peers learn of upgrades | **On-chain schedule** in the upgrade vector — consensus-visible, so withdrawing is sound. Attestation is advisory readiness only. |
| Forward-compat: withdraw vs refuse-to-start | **Withdraw at the boundary**, rejoin after update; never refuse to start. |
| Version semantics | Every upgrade increments the version by exactly 1 — the version *is* the watermark (the count of the applied prefix). Migrations are unnamed; identity is the version number, bound positionally in the `Migrations` class. |
| CVM core changes | Three tiers: state-resident core (`core.cvx` bindings) → pure migration, no branching; native semantics (opcodes, juice, casts) → permanent version-keyed branches at narrow seams; encodings → permissive decoders, version-gated writing/canonicalisation. |
| Etch interaction | Pure state migrations need none; store-format conversions are peer-local ops, not in-consensus. |
| Bootstrap | Uniform for **all** networks: genesis never carries the protocol globals — migration v1 extends the globals and installs the core function binding (version `1`, vector `[activation]`). Coordinated by peer release (one-time exception to on-chain scheduling). Genesis hash never changes, anywhere. |

## Remaining open questions

- **Withdrawn-peer CPoS mechanics** — what a withdrawn peer does with its stake and belief signing while non-participating, and whether prolonged withdrawal interacts with any future slashing policy.
- **Snapshot trust policy** — whether non-archival peers may sync from a snapshot without holding ancient migrations, and who blesses such snapshots.

## Implementation plan

Ordered so each step is independently testable and the genesis-affecting change lands once, early:

1. **Protocol global accessors.** `GLOBAL_UPGRADES=7` alongside `GLOBAL_PROTOCOL=6`; `getProtocolVersion` (absent → 0), upgrade-vector accessor (absent → empty). `INITIAL_GLOBALS` and genesis are **not** modified — every network starts at version 0 by default and is extended by migration v1.
2. **`Migrations` class + `Migration` interface**, with a trivial identity migration for tests.
3. **`schedule-upgrade` / `unschedule-upgrade` core functions**, with the sub-`#8` address gate and activation validation.
4. **`applyUpgrades` in `prepareBlock`**, including withdrawal on missing or failing migrations.
5. **Attestation**: status advertisement of the highest supported protocol version.
6. **First real upgrade** as validation: fix one of the dependent issues ([#533](https://github.com/Convex-Dev/convex/issues/533), [#528](https://github.com/Convex-Dev/convex/issues/528), [#354](https://github.com/Convex-Dev/convex/issues/354), [#208](https://github.com/Convex-Dev/convex/issues/208)) as a scheduled upgrade rather than a naive code change.

## Testing strategy

- **Determinism:** apply the same scheduled upgrade to the same pre-state on independent instances (separate stores, separate JVMs where practical); assert bit-identical post-state hashes. Apply twice from forked copies of the pre-state and compare.
- **Replay equivalence:** genesis → N blocks spanning an activation, replayed from genesis, reproduces the identical tip including the upgrade-vector record.
- **Boundary:** a block with timestamp exactly `== activation` fires the upgrade; the block just before does not; the same block's time update and ordinary transactions execute under the new transition function.
- **Version gating:** a tier-2 branch yields old semantics for pre-activation blocks and new semantics from the activation block onward, verified by replaying a history spanning the boundary.
- **Catch-up:** a state lagging several activations applies all due upgrades in vector order within the next block, incrementing the version once each.
- **Missing migration:** a peer whose migration list is too short for a due version withdraws (produces no post-state) rather than diverging, and rejoins once running a release that carries the migration.
- **Failing migration:** a migration that throws causes withdrawal (no post-state, no invalid-block result); after substituting a corrected migration for the same version, resumed execution and replay from genesis agree.
- **Scheduling validation:** calls from addresses ≥ `#8` fail; non-future or decreasing activations are rejected; `unschedule-upgrade` removes the tail pending entry and the activation then passes without effect.
- **Format invariants:** after arbitrary schedule/unschedule/apply sequences, the protocol globals satisfy the invariants (watermark bounds, non-decreasing activations, future-dated pending suffix, immutable applied prefix).
- **Migration state deltas:** every migration (bootstrap included) has tests asserting the *exact* expected state changes — and that nothing else in the state changed (compare untouched subtrees by hash).

Follow the project testing conventions: no `sleep`s and no fixed ports — wait on real signals (futures / sync APIs); a missing waitable is an API gap in the main code, not a reason to sleep.

## Risks

- **Migration purity.** A single impure migration forks the network. Mitigate with narrow, audited migrations, the purity contract, and the determinism tests.
- **Buggy migration = network stall.** A throwing migration stalls the network at the boundary until a corrected release ships, making migration quality a release-blocking concern. Mitigate with the boundary/determinism test suite and rehearsal on testnet before scheduling on Protonet.
- **Governance key compromise.** An attacker controlling a sub-`#8` account can schedule or unschedule upgrades — but can only activate migrations that already exist in released peer code; code injection additionally requires compromising the release process. Mitigate with controller-based multi-signature policies on the governance accounts.
- **Readiness at activation.** If insufficient stake holds the migration, the network stalls at the boundary. Mitigate with attestation, generous activation notice, operator communication — or `unschedule-upgrade` before the boundary.
- **Bootstrap coordination.** The one release-coordinated (off-chain) upgrade per existing network is the riskiest, since scheduling is not yet consensus-visible. Keep the bootstrap migration minimal: core function binding + protocol globals, nothing else.
- **Migration list growth.** Historical migrations accumulate in every release forever; accepted cost, revisit only alongside a snapshot-trust sync policy.

## Status

Design. Not yet implemented. The peer-code hook should be in place before launch, so the upgrade mechanism exists before it is first needed; every network (new or existing) adopts via the uniform bootstrap path — genesis is never modified.
