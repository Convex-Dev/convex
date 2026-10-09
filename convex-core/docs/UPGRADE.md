# Convex Network Upgrades

How protocol changes reach a running Convex network without changing its
identity or forking consensus. The normative specification is
[CAD050 (Network Upgrades)](https://docs.convex.world/docs/cad/network_upgrade);
this document is the contributor's view of the JVM implementation: the rules
that decide *how* a given CVM change must be made, the mechanism's moving parts
as they appear in the code, what happens when a peer cannot follow an upgrade,
and the test conventions that keep releases honest against both the live
network and the upgrade target.

## Key points

- **The protocol version is a count of applied upgrades**, starting at `0` at
  genesis. "vN" names the migration that produces version N; migrations are
  identified by position, never by name. Protocol versions are independent of
  software release versions.
- **Genesis is immutable.** Every change to consensus-visible behaviour after
  launch ships as a migration; replaying history from genesis must reproduce
  the current state exactly.
- **An upgrade is one atomic transition**: a pure `State → State` migration
  followed by a version increment of exactly one. The migration list in
  `Migrations` is positional, append-only forever, and every entry is pure.
- **The schedule is consensus state**: two globals, a version watermark and a
  vector of activation timestamps. Scheduling is governance-gated and validated
  purely against state, never against the local migration list.
- **Activation is the first step of block preparation**, fired by the first
  block whose timestamp reaches the activation, so the whole block runs under
  the new rules.
- **Any failure to apply an upgrade means withdraw, never an invalid block.**
  Withdrawal is a full consensus freeze; the peer stays alive for diagnostics
  and, best-efforts, sheds its stake beforehand.
- **Where a change lives decides how it is made**: state-resident core code is
  migrated; native semantics branch on the version at narrow seams; decoders
  stay permissive and the version gates what the CVM writes.
- **A tier-2 gate is mandatory iff replay evidence demands it**: if making the
  change unconditional moves the `SnapshotStateTest` hash, history exercised the
  old semantics.
- **v1 is the target.** It bundles the mechanism bindings and every bug known at
  genesis; while it is unscheduled its content is still editable. Tests run
  against v1 (`InitTest.UPGRADED`) by default; `Migrations.LIVE_VERSION`
  (currently `0`) pins what live peers run.

## Terminology

"Version" always means the on-chain protocol version, `State.getProtocolVersion()`.
A release *supports* protocol versions up to `Migrations.MAX_VERSION`, the
length of its migration list, and several releases may support the same version.
Operator-facing messages say "protocol version" explicitly to avoid confusion
with release numbers such as `0.8.16`.

## How an upgrade works

### On-chain format

Two entries in the globals vector, following the flat positional convention used
by the other globals:

| Global | Type | Meaning |
|--------|------|---------|
| `GLOBAL_PROTOCOL` (index 6) | `Long` | The protocol version: a watermark into the upgrade vector |
| `GLOBAL_UPGRADES` (index 7) | `Vector` of `Long` | The upgrade vector: entry `k` is the consensus timestamp at which the upgrade producing version `k+1` fires (or fired) |

Entries below the watermark are applied; entries from the watermark onward are
pending. Invariants holding in every committed state: `0 ≤ version ≤ count`;
activations are non-decreasing (equal timestamps fire in the same block, in
order); every pending activation is strictly later than the state timestamp;
absent globals read as version `0` and an empty vector, which is how states
created before the mechanism present themselves.

The pending region is managed strictly at the tail (schedule appends, unschedule
pops) because inserting mid-vector would silently re-bind later activations to
different migrations. The applied prefix is immutable: applying an upgrade only
advances the watermark. An upgrade's whole on-chain footprint is one timestamp;
what each version does is described in the release notes and `CHANGELOG.md`.

### Scheduling

Two core functions, implemented natively in `Core` and registered as
non-genesis definitions:

```clojure
(schedule-upgrade activation)   ;; append: returns the version it will produce
(unschedule-upgrade v)          ;; pop: v must be the last pending version
```

They are callable only from governance accounts, the genesis system accounts
with addresses below the core library (`#8`); richer policies are composed by
making a policy actor the controller of such an account. The activation must be
strictly in the future and not earlier than the last entry. Validation is a
pure function of state and deliberately never consults the local migration
list: otherwise transaction validity would depend on which release executed it
and peers would fork. A peer therefore processes the scheduling of a version it
cannot apply like any other transaction, runs normally while it is pending, and
stops exactly at the boundary. This is also defence in depth: a governance key
can only *time* migrations that reviewed, released code already contains.

### Migrations

`Migrations` holds the ordered list; `Migrations.get(k)` is the migration
producing version `k+1`. A migration is ordinary release source implementing
`Migrations.Migration` and may use anything shipped alongside it. Purity is a
hard contract, enforced by review and by the determinism tests: no clock,
randomness or I/O. The list is append-only forever because replay needs every
historical migration; that maintenance cost is accepted.

The v1 `Bootstrap` installs the scheduling bindings and the other non-genesis
core functions into the core environment, then applies the code fixes held as
resources under `convex/migrations/v1-*.cvx` (core, metadata and the standard
libraries). A single fix is never its own protocol version: v1 brings a network
fully up to date in one step. Once v1 has activated on any network, further
fixes need a v2.

### Activation

`State.applyUpgrades` runs first in `prepareBlock`, ahead of the block-number
bump, time update and scheduled transactions. While the entry at the watermark
has an activation at or before the block timestamp it applies that version's
migration and advances the watermark by one, so several overdue upgrades fire in
one block in order. When nothing is due the step is one comparison. A missing
migration (release too old) withdraws the peer, as below.

## Changing CVM behaviour: three tiers

Tier order is preference order.

1. **State-resident core: migrate, no branching.** Functions defined in
   `core.cvx` are compiled into account `#8` at genesis, so they are state.
   Replace the binding with the recompiled definition; replay is automatic
   because historical states carry historical code. Caveat: the compiler links
   core symbols statically, so code compiled before the upgrade embeds the old
   function. Whether a migration also sweeps account environments is a
   per-upgrade decision.
2. **Native semantics: branch on the protocol version.** Opcodes, native core
   functions, juice costs and cast rules are the transition function in Java.
   Dispatch on `state.getProtocolVersion()` at narrow seams (juice lookup,
   opcode dispatch, cast rules), never scattered through the runtime. Branches
   are permanent, exactly like the migration list. Whether a gate is needed at
   all is decided by replay evidence: flip the behaviour to unconditional and
   run `SnapshotStateTest`; if the replay hash moves, the gate is mandatory,
   otherwise the change ships unconditionally.
3. **Encoding changes: decoders stay permissive.** Cells are decoded outside any
   state context (Etch, network messages), so decoding cannot branch on the
   version. Decoders accept every historical form forever; the version gates
   what the CVM writes and canonicalises, and a migration rewrites existing
   cells where the new form is required.

### New core definition codes

Adding a decodable executable value (a core definition with a fresh code under
`CVMTag.CORE_DEF`) is a special tier-2 case. Decoding is stateless: a release
that knows the code decodes a real function, an older release decodes the same
bytes as an opaque value with identical re-encoding. State hashes agree while
the value sits in state, but *executing* it diverges, and anyone can inject such
a value by hand-crafting an encoding. That opens a fork window between releases
before the version gate activates.

Standing rule: **no release adds a core definition code beyond 506 unless it
ships, in the same release, at least an in-definition version gate keyed on the
version that introduces it** (the shape used by `char?`, code 506: applied below
its version it fails `:CAST` before any other work, so no invocation succeeds
pre-activation on any release and the residual skew is confined to juice in
attacker-paid failure paths). Full versioned materialisation, where every
function-materialisation seam treats a not-yet-active definition as a
non-function, becomes mandatory once v1 has activated on the live network; until
then a new code's window is subsumed by the all-stake floor that scheduling v1
already requires. Codes 501 to 505 shipped before the rule and their window is
accepted operationally; the governance pair is address-gated, and `gensym`,
`cat` and `splice` are pure utilities whose pre-activation execution is
harmless in itself.

## Failure modes and withdrawal

Every failure of upgrade application resolves to the same behaviour: produce no
state and withdraw from consensus. It must never become an invalid-block result,
because invalid-block outcomes are consensus history and a corrected release
would recompute them differently on replay. The whole machinery is guarded so
that nothing escapes as a Java `Exception` (which `applyBlock` would turn into
exactly that forbidden result); failures surface as `UpgradeError`, carrying the
version and cause, which passes through to the peer layer untouched.

Three classes, distinguished only for diagnostics:

- **Missing migration** (release too old): deterministic and known in advance
  from the schedule. Withdraw, log "update required", rejoin automatically once
  running a release with the migration. Never guess or skip.
- **Failing migration** (bug): throws identically on every peer of that
  release; the network stalls at the boundary until a corrected release ships.
  A stall is recoverable, divergence is not.
- **Environmental failure** (peer-local, for example missing store data): only
  the affected peer withdraws, with the cause preserved. This class must stay
  retryable: treating it as a permanent freeze would let a transient or induced
  local fault become a permanent outage.

**Withdrawal is a full consensus freeze.** A peer votes on block order through
belief merge independently of applying state, so stopping only the executor
would leave it rubber-stamping blocks it cannot validate. Withdrawal stops both
the state executor and the belief propagator; the peer stops applying, merging,
proposing and publishing, and its consensus state freezes at the last
pre-boundary state. It stays alive to serve queries and report its condition
("upgrade required: version N at T; this release supports M").

**Recovery differs by cause.** Deterministic failures are cured by a corrected
release: the peer freezes, does not auto-retry, and rejoins after an operator
update and restart. Environmental failures retry after acquiring the missing
data, exactly as missing data is handled outside an upgrade. The cause on
`UpgradeError` selects between the two.

**Early detection and stake withdrawal.** Because the schedule is on-chain and
`MAX_VERSION` is a release constant, a peer knows the moment a scheduling
transaction beyond its support lands that it will withdraw at that activation.
It warns the operator immediately (`Migrations.pendingBeyondSupport`) and, on a
best-efforts basis, reduces its own stake at a randomised instant in a window
before the activation, so a withdrawn-but-staked cohort does not deny the
remaining peers their supermajority. It never does so as the last viable peer,
and the behaviour is gated on the peer's `:auto-manage` setting. Peers also
advertise their supported version in status; that attestation is advisory,
the binding signal is the schedule.

## Bootstrap and fresh networks

Existing networks adopt the mechanism fully on-chain without touching genesis. A
governance transaction schedules v1 by embedding the `schedule-upgrade` cell
directly in compiled code (no binding exists yet and none is needed, because
validation is state-only); this creates the protocol globals. At activation the
v1 migration installs the bindings and the watermark advances to `1`. Replay
reproduces both steps. The operational precondition is that effectively all
stake runs a release carrying the mechanism, and the final v1 content, before v1
is scheduled: earlier releases can follow neither the scheduling transaction nor
the boundary, and lack the withdraw behaviour.

Fresh networks have no history to preserve and start at `MAX_VERSION`:
launchers that create a genesis apply all migrations via `Migrations.applyTo`.
A lower version can be pinned with `--protocol-version` (CLI) or
`:protocol-version` (peer config), for example `0` to mirror a network that has
not yet upgraded. This sets the initial state only; an explicitly supplied
`:state` is always respected as-is, and the on-chain schedule remains the only
way to upgrade a running network.

## Security summary

- Withdrawal is not selectively targetable: deterministic failures fire
  identically on every peer of a release, triggered by a finalised event, so no
  per-peer input can make one honest peer withdraw while others continue.
- The schedule cannot be forged: it requires a governance-signed transaction.
- Environmental failures staying retryable is a security requirement, not
  ergonomics.
- A future-dated block that reached finality could once teleport the consensus
  clock past an activation and force mass withdrawal. The forward-dating
  confirmation policy in `CONSENSUS.md` closes this; governance should still
  confirm attestation readiness and allow generous lead time. Best-efforts
  unstaking is tied to the early signal rather than the boundary precisely so a
  clock jump cannot also trigger mass auto-unstaking.

## Rollback and governance

Upgrades are one-way. A broken upgrade is corrected by a later upgrade; an
upgrade authored to undo a prior one is an ordinary upgrade. A pending upgrade
can simply be unscheduled. Upgrade authority is control of the governance
accounts below `#8`; governance policy (multi-signature keys, a vote contract as
controller, staked-peer signalling) is a per-network decision layered on top.

## Testing

### Default test state policy

Three test-state roles, each answering a different question:

- **Target**: `InitTest.UPGRADED` / `BaseTest.UPGRADED`, protocol version
  `MAX_VERSION`. The `ACVMTest` default: libraries, actors and CVM behaviour are
  tested against where the network is going.
- **LIVE**: `InitTest.LIVE` / `BaseTest.LIVE`, protocol version
  `Migrations.LIVE_VERSION`. The release gate that stops a release breaking live
  peers before they upgrade. The shared peer `TestNetwork` launches on it, and
  `CoreLiveTest` runs the core suite on it while it is an intermediate version.
  Bump `LIVE_VERSION` when, and only when, the live network applies an upgrade;
  everything pinned to LIVE follows.
- **Genesis**: `InitTest.STATE` / `BaseTest.STATE`, protocol version `0`. Pinned
  where genesis is the point: genesis-hash invariants, replay from genesis
  (`SnapshotStateTest`), behaviour that must hold from inception
  (`CoreGenesisTest`) and the intended-diff tests (`MigrationFixesTest`).

While LIVE equals genesis, `CoreLiveTest` disables itself as a duplicate of
`CoreGenesisTest`. Full suites are not run against historical versions that are
neither live nor target.

### Suites

- `ApplyUpgradesTest`: boundary (just before and exactly at activation),
  ordered catch-up, missing and failing migrations, and the rule that failure
  never becomes an invalid block.
- `BootstrapTest`: the real v1 migration through a signed block, its exact
  account and global footprint, repeatability, and symbol resolution before and
  after activation.
- `UpgradeSchedulingTest`: governance gating, validation, schedule and
  unschedule, and direct encoding of the bootstrap cells.
- `CoreGenesisTest` / `CoreUpgradedTest`: the same core behavioural suite on
  both semantic states; `MigrationFixesTest` owns the intended differences.
- Every migration has tests asserting its exact state delta and that nothing
  else changed.

Consensus-freeze tests inject the peer clock; there are no sleeps and no fixed
ports.

### Rehearsal programs

- `convex.peer.tools.RehearseNetworkUpgrade`: wholly local and deterministic.
  Seeded peers start at v0, reach consensus on a governance scheduling
  transaction, process traffic before, at and after activation, converge at v1,
  replay from genesis, repeat for identical hashes, and rehearse an unschedule
  abort. No sockets.
- `convex.peer.tools.VerifyNetworkUpgrade [host] [--report FILE]`: read-only
  against a live peer. Acquires genesis, state and belief, replays through the
  peer startup path, applies v1 locally, checks the migration footprint, and
  writes a strict JSON audit report. Remote state is evidence, not authority: a
  peer's own replay from genesis is definitive and a mismatch is never repaired
  by adopting the remote state.

## Risks

- **Migration purity.** One impure migration forks the network. Narrow, audited
  migrations plus the determinism tests.
- **Buggy migration means a network stall** until a corrected release ships, so
  migration quality is release-blocking. Boundary tests and testnet rehearsal
  before scheduling on Protonet.
- **Governance key compromise** can schedule or unschedule, but only activate
  migrations that already exist in released code. Controller-based
  multi-signature policies.
- **Readiness at activation.** Insufficient stake holding the migration stalls
  the network, worsened by withdrawn peers that keep their stake. Attestation,
  generous notice, best-efforts stake shedding, or unschedule before the
  boundary.
- **Bootstrap coordination.** Peers without the mechanism fork rather than
  withdraw; upgrade effectively all stake first.
- **Migration list growth** is a permanent, accepted cost, to be revisited only
  alongside a snapshot-trust sync policy.

## Where the code lives

- `convex.core.cvm.State`: `GLOBAL_PROTOCOL`, `GLOBAL_UPGRADES`,
  `getProtocolVersion`, `applyUpgrades` (called first in `prepareBlock`).
- `convex.core.cvm.Migrations`: the positional list, `Migration` and
  `CodeMigration`, `Bootstrap` (v1), `MAX_VERSION`, `LIVE_VERSION`, `applyTo`,
  `pendingBeyondSupport`; migration sources under
  `convex-core/src/main/cvx/convex/migrations/`.
- `convex.core.lang.Core`: `SCHEDULE_UPGRADE`, `UNSCHEDULE_UPGRADE` and the
  other `regNonGenesis` definitions.
- `convex.core.exceptions.UpgradeError`: the failure carrier.
- `convex.peer.CVMExecutor`, `convex.peer.Server`,
  `convex.peer.TransactionHandler`: freeze, early warning and best-efforts
  stake withdrawal.
- `convex.peer.Config`, `convex.cli.Helpers`: the `:protocol-version` and
  `--protocol-version` pins for fresh networks.

## Related

- [CAD050: Network Upgrades](https://docs.convex.world/docs/cad/network_upgrade),
  the normative specification.
- [CAD051: Convergent Proof of Stake](https://docs.convex.world/docs/cad/cpos)
  and `CONSENSUS.md` for the consensus clock and the forward-dating policy.
- [CAD016: Peer Staking](https://docs.convex.world/docs/cad/peerstake) and
  [CAD017: Peer Operations](https://docs.convex.world/docs/cad/peerops).
- The `protocol-versions` skill: conventions for which semantics to write
  against and when a version gate is mandatory.
- `CNS.md`: the registry changes proposed for the v1 bundle.
- Tracking issue [#413](https://github.com/Convex-Dev/convex/issues/413).
