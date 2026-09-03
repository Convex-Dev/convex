# Etch Garbage Collection

Etch is append-only, so a long-running peer accumulates every Belief, State and
intermediate value it has ever persisted. Garbage collection reclaims that space by
**copying collection**: live data is copied into a fresh file while the store stays in
use, and the old file is discarded once the copy is verifiably complete. The normative
procedure (retention contract, lifecycle, crash-recovery outcomes, migration, costs)
is [CAD049](https://docs.convex.world/docs/cad/etch_gc), built on the store model and
persistence-status rules of [CAD048](https://docs.convex.world/docs/cad/stores). This
document is the contributor's view of the JVM implementation in `convex.etch` and
`convex.core.store`: the invariants the code relies on, how the read and write paths
change during a cycle, how cutover and cancellation work, and the on-disk file
lifecycle that makes recovery unambiguous.

## Key points

- **INV-1: an entry present in the *target* file with status ≥ `PERSISTED` has its
  entire reachable tree in the target.** It holds because the target starts empty and
  every `PERSISTED`-level write descends children first. It replaces mark state
  entirely: the sweep and every live write prune wherever they meet a target-resident
  persisted entry, so total copy work is O(live data) with no visited-set.
- **Retention is explicit.** What survives is the root tree, everything persisted after
  `startGC()`, and anything the application explicitly persists during the cycle.
  Reads never copy; there is no copy-on-read and no store-level multi-root feature.
  `Cells.persist(value, store)` is the "keep" operation.
- **Status is store-relative and a store records only what it proves.** A ref's status
  is a claim about its bound store; the write boundary records exactly the level the
  write achieved, never a level carried in from a foreign ref. INV-1 pruning is sound
  only because of this.
- **Reads have three outcomes**: a value, proven absence (`null` or
  `MissingDataException`), or failure (`StoreException`: the store cannot look). A
  failure is never evidence of absence.
- **The result of GC is a new store.** `completeGC()` returns a fresh `EtchStore` on
  the target file; the old store stays a functional legacy view until the caller
  closes it. Refs bound to a closed store fail even for migrated values: the binding is
  dead, not the data.
- **Cutover is hard-gated on verified completeness.** `completeGC()` refuses unless the
  sweep has finished; `verifyGC()` walks the current root against the target only.
- **Cancel rolls back, never discards.** Writes made during a cycle exist only in the
  target, so `cancelGC()` migrates the target back into the original file.
- **GC is migration plus cutover.** `EtchUtils.migrate(source, dest)` moves *everything*
  from one store into an existing one; GC is the special case of a fresh destination,
  root-tree coverage and cutover plumbing.
- **Post-order copy gives locality for free.** Children are written immediately before
  their parent, so each subtree occupies a contiguous byte range in the append-only
  target.

## Refs, status and store identity

`RefSoft` binds to the store it was read from or persisted into. Four rules govern
that binding; `EtchStatusIntegrityTest` pins them with regression tests.

- **S1: status is store-relative.** A `RefSoft`'s status is a claim about its bound
  store. A `RefDirect` carries no binding; its status claims persistence "somewhere in
  this process" and is coherent only under single-store usage.
- **S2: store entry flags are authoritative for that store.** The flags byte records
  only what has been achieved *in that store*: `STORED` means this entry is present,
  `PERSISTED` means the whole subtree is present, `ANNOUNCED` adds a peer-level
  commitment. The write path (`EtchStore.storeRef`, via `Ref.withStatus`) records
  exactly the status the write achieved, then merges monotonically with the store's
  own existing flags. Status carried by an incoming ref is never evidence for another
  store.
- **S3: rebinding drops status.** `RefSoft.withStore` moves no data and is store
  plumbing, not application API: it is sound only immediately after the store's own
  write, where the data is present by construction. Applications move values between
  stores by re-persisting them (`Cells.persist` or `StoreTransfer.transfer`), which
  descends the tree and returns a ref honestly flagged for the destination.
- **S4: tree coherence.** All `RefSoft`s in one cell tree bind to the same store
  (`Refs.checkConsistentStores` checks this). A store never returns, attaches or
  caches a ref bound to a foreign store; `AStore.isForeign` is the guard and the
  Etch cache rejects foreign refs as a backstop.

The read contract follows from S1: `null` is proven absence, `MissingDataException`
means the store looked and the value is not there, and unchecked `StoreException`
means the store cannot look (closed, or an IO failure). `refForHash` performs no
liveness pre-check; the exception arises only when a read actually fails, and
application code should not attempt to handle it.

`Ref.MARKED` exists as a status level but is unused: INV-1 pruning replaces mark
state.

## Lifecycle

```
        startGC()            transferGC()               completeGC()
IDLE ─────────────▶ COLLECTING ───────────▶ (verified complete) ────▶ new EtchStore returned,
                        │                                             old store = legacy view
                        └── cancelGC() ──▶ roll target back into old ──▶ IDLE (nothing lost)
```

Throughout a cycle every ref the collecting store hands out stays bound to that
store object, including refs for values physically written to the target file. The
target is internal until cutover, which is what keeps cancellation invisible to the
application and concentrates the whole handle swap at `completeGC()`.

### Start

`startGC()` is synchronised on the store. It refuses if a cycle is in progress or a
stale target file exists (a stale target may hold data from an interrupted cycle;
adopting or deleting it blindly risks loss, so recovery handles it separately). It
creates the target file with a fresh header, empty index and a copy of the current
root hash, and only then publishes the volatile `target` field, so no reader sees a
half-initialised target.

**Cycle-boundary linearisation.** Each top-level persist snapshots its write target
once and threads it through its whole recursive descent. A persist in flight when
`startGC()` runs therefore completes entirely against the old file and linearises as
before the cycle. Reading the target per write could split one tree across two files,
leaving a parent in the target claiming `PERSISTED` whose children exist only in the
old file: a silent INV-1 violation the sweep would then trust.

### Read and write paths during a cycle

Reads (`readStoreRef`): cache hit, else target, else old file, else miss. The worst
case is two index lookups instead of one, and the L1/L2 caches in `ACachedStore` sit in
front of both files, so hot-path amplification is well below the 2x bound. Reads never
mutate the target.

Writes (`storeRef` / `storeTopRef`): all physical writes go to `getWriteEtch()`, the
target while collecting. The existence check that normally lets a persist early-return
reads the **target file only**: neither the old file nor the shared cache can prove
target residency. A target hit at sufficient status returns; anything else takes the
normal recursive write path, which copies the cell and, at `PERSISTED` and above, its
children into the target. Outside a cycle the hot path costs one volatile load and an
untaken branch.

Root updates need no special casing: `setRootData` persists at `PERSISTED` through the
same path, so every root set during a cycle has its full tree in the target. The old
file's root hash is never touched.

### Sweep

`transferGC()` copies the current root's reachable tree into the target. It is an
explicit-stack post-order walk (stack-safe for deep trees) that persists each entry
through the store's own target-then-old read path, so per-entry recursion is at most
one level deep. Points that matter:

- **Per-entry status preservation.** Each entry is transferred at the status it holds
  in the old file, floored at `PERSISTED`. A uniform `PERSISTED` sweep would demote
  `ANNOUNCED` entries and cause a novelty re-broadcast storm after cutover; a uniform
  `ANNOUNCED` sweep would forge peer commitments.
- **Pruning doubles as deduplication.** A subtree already target-resident at
  sufficient status is skipped, so shared subtrees are copied once without a
  visited-set.
- **Strict on missing data.** Etch destinations are strict: a hole in the source
  fails the sweep with `MissingDataException` rather than producing a smaller store
  that looks complete. Lenient skipping is a per-subtree CLI policy, not a property
  of the primitive.
- **Cycle-end abort.** The sweep checks per iteration that its cycle is still live and
  aborts if cancelled; overlapping sweeps are rejected.
- **Single writer.** `Etch.write` is synchronised per file, so sweep output
  interleaves safely with live novelty writes. The sweep is deliberately not
  parallelised across subtrees, which would destroy the contiguous layout.

Under a high root-update rate the cycle write path often lands the whole live set in
the target before the sweep runs, and the sweep finds everything pruned. The sweep is
a completeness *guarantee*, not necessarily where the copying happens.

### Completion and verification

After `transferGC()` returns, the root at sweep end is fully in the target (INV-1) and
every root set since `startGC()` is too (write-path guarantee), so the current root's
tree is entirely in the target and stays that way. `isGCComplete()` reports this.
`verifyGC()` is the belt-and-braces check: a full walk of the current root resolving
against the target file **only** (store reads fall back to the old file, so they cannot
verify), with no pruning, returning any missing hashes.

Not transferred, by design: anything unreachable from the current root and not
explicitly kept, including values held only at `STORED` level such as stale Belief
fragments and orphaned message data.

### Cutover

`completeGC()` is synchronised and refuses unless `isGCComplete()`; there is no force
override, because the failure mode of an early cutover is silent data loss. It flushes
the target, constructs a new `EtchStore` over the target file (fresh caches; the
target file's store binding is repointed so refs decoded from it bind to the new
store and outlive the old store's close), writes the completion marker and returns
the new store.

The old store is not retired. It remains a functional view: reads fall back across
both files and writes route to the successor's file, so code still holding the old
handle keeps working through a gradual handover. What it forbids is only what would
now be wrong: closing the successor's file, cancelling, re-completing, or starting a
new cycle on the legacy view. The caller closes the old store when nothing depends on
refs bound to it; from then on those refs throw `StoreException` on uncached reads.

### Cancel

Every write since `startGC()` exists only in the target, so `cancelGC()` is a reverse
migration:

1. Under lock, redirect writes back to the old file. Reads keep the target fallback
   and root reads keep coming from the target, which stays authoritative until the
   copy-back.
2. Drain in-flight target writes. Because each persist snapshots its write target for
   its whole descent, a brief lock is not enough: persists register in a counter while
   writing to the target and re-check the cancelling flag after registering, so a
   drained zero count is conclusive. The reverse migration's index scan requires a
   write-quiescent source.
3. `EtchUtils.migrate(target, this)`: everything in the target is persisted back into
   the old file, with the existence check reading the old file only. Idempotent, so a
   failed cancel can be retried.
4. Copy the target's root hash back, then drop, close and delete the target.

Application-visible refs were bound to the collecting store throughout, so
cancellation is invisible beyond the transient 2x read cost while step 2 runs.
Closing a store mid-cycle without cancelling abandons the cycle; startup recovery
reconciles it.

## File lifecycle and recovery

Target files are named generationally off the store's logical base file
(`EtchStore.getBaseFile()`): `f~`, `f~1`, `f~2` and so on, with `startGC()` taking
the first name not in use. An existing target is neither adopted nor deleted at
start: it is either stale (recoverable data) or a cancelled target still pinned by
memory mappings. Naming off the base file, which is inherited across cutovers, keeps
generation numbers small rather than growing `f~~~` one character per cycle.

Two on-disk markers make every crash window unambiguous:

- `<base>.gc-complete` names the **current** store file and is rewritten by every
  `completeGC()`. One marker, never a chain.
- `<file>.gc-defunct` is a tombstone on a superseded file, written at cutover
  *before* the marker. It discriminates "retained content verifiably elsewhere:
  delete, never roll back" from an abandoned cycle that must be rolled back. A crash
  between tombstone and marker reads as "cutover did not happen" and the target rolls
  back, losing nothing.

Windows cannot rename or delete a mapped file, and Java offers no explicit unmap for
`MappedByteBuffer`, so in-process deletions may be deferred. Tombstones make that safe;
`startGC()` retries deletion of tombstoned files and reuses their names once the
previous store's buffers have been collected. Migration of Etch mapping to the FFM
`Arena` would make unmapping deterministic ([#636](https://github.com/Convex-Dev/convex/issues/636)).

`EtchStore.create(file[, config])` runs `EtchUtils.recover` before mapping anything.
Recovery is idempotent and logs its actions on the `convex.etch.recovery` logger. It
first header-validates every participating non-empty file (authenticating encrypted v3
headers with the caller's configuration) before any marker deletion, rollback or
adoption, so a wrong key or mismatched policy changes nothing on disk. Then:

- **Completed cutovers are adopted.** The marker-named file is installed as `<file>`
  and the superseded original deleted. That deletion is the disk reclamation. If
  renaming fails (pinned mappings), recovery opens the marker-named file directly and
  retries adoption on the next start; it never opens stale data.
- **Defunct files are deleted, never rolled back.** Rolling them back would resurrect
  collected garbage.
- **Abandoned cycles are rolled back.** A target with neither marker reference nor
  tombstone holds writes that exist nowhere else; its entries are migrated into the
  live file, tolerating a torn tail from the crash, and the root advances only if its
  tree then verifies complete.

**Etch v3 differs on unclean crashes.** Recovery accepts only cleanly closed v3 files.
A process crash leaves a v3 file in the `OPEN` state, and ordinary `EtchStore.create`
stops before changing the GC layout; the operator must deliberately select the
maintenance and repair workflow described in [ETCHv3.md](ETCHv3.md). V1 and v2 files
have no clean-close state and keep the best-effort rollback behaviour above.

The steady state after a restart is one file `f`. A typical sequence:

```
running on f      cycle 1: target f~    cutover: marker -> f~, f defunct
close old view:   f deleted             (now running on f~)
running on f~     cycle 2: target f~1   cutover: marker -> f~1, f~ defunct
restart:          recovery installs the marker-named file as f
```

## Store-to-store migration

`EtchUtils.migrate(source, dest)` ensures everything in an Etch source is persisted in
an existing `AStore` destination, driven by a full index scan (`Etch.visitIndex` with
an `EtchUtils.EtchCellVisitor`) that transfers each non-embedded entry at its recorded
status. Compared with GC:

- **Coverage is the whole source**, not just the reachable set, so `STORED`-level and
  currently unreachable data comes across too.
- **The source must be write-quiescent.** Index restructuring (chain collapses, slot
  repointing) can hide entries from a concurrent scan. Reads are fine.
- **The destination may be live and non-empty.** Transfer uses the destination's
  normal `storeTopRef` path, so it composes with concurrent use, novelty handling and
  monotonic flag merging.
- **Roots need a policy.** By default the destination root is untouched; `--set-root`
  adopts the source root. Merging two roots is a lattice-level operation above this
  layer.
- **No cutover.** Neither store changes identity; the caller closes the source when
  satisfied.

Strictness follows the destination: an Etch destination propagates missing source
data as `MissingDataException`, while `MemoryStore` is lenient by design (it stores
what it can and caps the achieved status, matching remote-acquisition semantics).

## Copy ordering and locality

`StoreTransfer.transfer` writes each cell's children immediately before the cell
itself. In an append-only file that places every subtree in one contiguous byte
range with the parent adjacent to its children, so later traversals (state reads,
Belief merges, sync serving) touch sequential mapped pages instead of scattering
across historical write order. An offline collection yields one DFS linearisation of
the root; an online cycle approximates it, with each swept subtree still contiguous.
The sweep currently decodes and re-encodes each cell; a raw entry copy would halve
the CPU cost and is a possible later optimisation, not a correctness matter.

## Costs and risks

| Operation | Normal | During a cycle |
|---|---|---|
| Cache-hit read | 1x | 1x |
| Read of migrated or new data | 1x | 1x |
| Read of unmigrated data | 1x | 2x |
| Write of novel data | 1x | 1x plus one failed target lookup |
| Write touching an unmigrated tree | 1x | one-off copy of that subtree, within the O(live data) total |
| Disk | one file | both files, transiently |
| Extra heap | none | none (INV-1 pruning replaces a visited-set) |

- **A busy-cycle file is usually larger than the original, by design.** Everything
  persisted after `startGC()` is retained, which under sustained load dwarfs the
  collected garbage. The true reclamation figure comes from a quiescent cycle.
- **~512 KiB is the floor for any Etch file** (the level-0 index block), so a fully
  collected store never shrinks below that.
- **Refs held across cutover** fail with `StoreException` once the old store is
  closed. Remedy: re-persist into the new store while the value is still resolvable,
  or re-fetch by hash.
- **Naive rebinding** with `withStore` produces refs whose flags claim data the store
  does not hold: a latent `MissingDataException`, or a false `PERSISTED` claim
  propagated onwards.
- **Crashes** at any point lose at most the unflushed tail of the target; the
  original file is only ever appended to by rollback.
- **Free disk space** must cover the expected collected size before starting.
- **An empty root** collects to an empty store plus header. Correct, if surprising.

The scheme assumes only content-addressed immutable entries, a single root, monotonic
status and a `PERSISTED`-style whole-tree level, so any `AStore` implementation can
adopt the same four phases and INV-1 pruning; the transfer and migrate primitives are
already store-agnostic.

## Where the code lives

| Concern | Location |
|---|---|
| Lifecycle | `EtchStore.startGC()`, `transferGC()`, `isGCComplete()`, `verifyGC()`, `completeGC()`, `cancelGC()`, `isGCInProgress()`, `getBaseFile()` |
| Tree transfer with INV-1 pruning | `convex.core.store.StoreTransfer.transfer(dest, ref[, status])`; `StoreTransfer.verify(store, rootHash)` |
| Whole-store migration | `convex.etch.EtchUtils.migrate(source, dest)`; `EtchUtils.verify(etch, rootHash)` |
| Recovery | `EtchUtils.recover(file[, config])`, called by `EtchStore.create` |
| Status rules | `Ref.withStatus`, `AStore.isForeign`, `Refs.checkConsistentStores`; pinned by `convex.etch.EtchStatusIntegrityTest` |
| Index enumeration and validation | `Etch.visitIndex`, `EtchUtils.EtchCellVisitor`, `EtchUtils.FullValidator` |
| CLI | `convex etch gc [-o file]`, `etch migrate --into <dest> [--set-root]`, `etch recover`, `etch validate [-m N]` in `convex.cli.etch` |
| Tests and example | `EtchGCLifecycleTest`, `StoreTransferTest`; `convex.core.examples.EtchGCExample` (test tree, not in the suite) runs a full online cycle under concurrent load |

The CLI commands are thin wrappers over the tested machinery; Etch's exclusive file
lock enforces offline operation. `etch gc` verifies before cutover and fails with the
source untouched if anything is missing. With `-o` it collects the root tree into a
fresh file and leaves the source alone (status above `PERSISTED` is preserved in place
but not via `-o`). Live-mode GC over a running peer's admin API is a natural follow-up
and not yet provided.

## Related

- [CAD049: Etch Garbage Collection](https://docs.convex.world/docs/cad/etch_gc) — the normative procedure
- [CAD048: Lattice Stores](https://docs.convex.world/docs/cad/stores) — store model, status ladder, read outcomes
- [CAD047: Etch Storage Format](https://docs.convex.world/docs/cad/etch) — the on-disk format being collected
- [ETCHv3.md](ETCHv3.md) — v3 header, durability boundary and the repair workflow that replaces automatic rollback for dirty v3 files
