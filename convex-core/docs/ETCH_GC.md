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

- **Configuration survives cutover.** The successor inherits the resolved
  [Etch configuration](ETCH_CONFIG.md), including L1 cache capacity and L2
  enablement. Its caches are fresh and bound to the successor; runtime settings
  are not written to the file header and must be supplied again on reopen.
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
- **Generations share one coordinator.** `EtchGC` owns write routing, root locking,
  writer draining and file retirement. Starting GC on a successor also redirects
  writes through every open predecessor to the new target.
- **Cutover is hard-gated on verified completeness.** `completeGC()` refuses unless the
  sweep has finished, then independently walks the current root against the target
  only. `verifyGC()` also exposes that check before cutover.
- **Snapshot and collect can be combined.** `completeGC(backupFile)` retains the
  original file under a new backup name while returning the collected successor.
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
  this process" and is coherent only under single-store usage. Etch's cache accepts
  direct refs only at `UNKNOWN` status, except for the store-independent null ref.
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
  `RefCache` checks ownership on every insertion. L2 promotion reuses decoded cells
  without adopting an attached foreign ref or its persistence claims.

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

Every ref returned by a handle stays bound to that handle, including reads from
another generation's physical file. `Etch.read` accepts the requesting view for
decoding, so a closed intermediate handle cannot invalidate an older handle's refs.
Each handle retains its own caches; cache hits do not consult the coordinator.

The coordinator publishes an immutable routing state at lifecycle boundaries.
Persists capture it once and reuse it throughout their recursive descent, without
allocating routing state per operation. Only the current handle may manage GC.

### Start

Target creation in `startGC()` is synchronised by the shared coordinator. It refuses
if a cycle is in progress and skips existing target names (a stale target may hold data from an interrupted cycle;
adopting or deleting it blindly risks loss, so recovery handles it separately). It
creates the target file with a fresh header, empty index and a copy of the current
root hash, and only then publishes the routing state, so no reader sees a
half-initialised target.

**Cycle-boundary linearisation.** Top-level persists through all generations share a
read lock; `startGC()` takes it exclusively before taking the root lock and
publishing the target. Cutover and close also drain persists with this lock.
Persists already writing to the old file therefore finish before copying can
begin, including their status upgrades. Calling `startGC()` from a persistence
callback is rejected rather than trying to upgrade that shared lock. GC lifecycle
operations, close and root updates from persistence callbacks are rejected to avoid
lock-upgrade or writer-drain deadlocks. Root writes through every handle serialise
with cycle transitions through the same root lock.

Each persist snapshots its write target once and threads it through its whole
recursive descent. Reading the target per write could split one tree across two
files, leaving a parent in the target claiming `PERSISTED` whose children exist
only in the old file: a silent INV-1 violation the sweep would then trust.

### Read and write paths during a cycle

Reads (`readStoreRef`): cache hit, else target, else current file, else miss for the
current handle. A legacy handle additionally checks retained generations back to
its own file. It cannot read generations older than itself, so a successor cannot
resurrect collected garbage. The current handle needs at most two index lookups
during a cycle; an old handle's cold miss can visit each generation it retains.
Reads never copy data into the target.

Writes (`storeRef` / `storeTopRef`): all physical writes use the destination in the
captured routing state, the target while collecting. The existence check that
normally lets a persist early-return
reads the **target file only**: neither the old file nor the shared cache can prove
target residency. A target hit at sufficient status returns; anything else takes the
recursive copy path. Before writing an entry, that path reads its recorded status
from the handle's retained files and retains the higher of that status (capped at
`MAX_STATUS`) and the requested status. It never trusts status carried by an incoming ref.
Retaining `PERSISTED` or higher first ensures the entry's children are persisted
in the target, even for a `STORED` request. A retained announcement applies only
to that entry: children preserve their own old statuses, and are announced only
if the caller explicitly requests it. Outside a cycle persists through the current
handle take the shared lifecycle lock but perform no extra index read. Legacy
persists check the current file directly because their caches may contain data
omitted from the current generation.

Root updates need no special casing: `setRootData` persists at `PERSISTED` through the
same path, so every root set during a cycle has its full tree in the target. The old
file's root hash is never touched.

### Sweep

`transferGC()` copies the current root's reachable tree into the target. It is an
explicit-stack post-order walk (stack-safe for deep trees) that persists each entry
through the store's own target-then-old read path, so per-entry recursion is at most
one level deep. Points that matter:

- **Per-entry status preservation.** Each entry is transferred at the status it holds
  in the old file, floored at `PERSISTED`. Recording every entry as `PERSISTED` would demote
  `ANNOUNCED` entries and cause a novelty re-broadcast storm after cutover; a uniform
  `ANNOUNCED` sweep would forge peer commitments. The sweep requests `PERSISTED`
  and the GC write path retains each entry's own old status. Live writes use the
  same path, so a copied parent proves both subtree presence and status transfer
  before it can be pruned. This also covers old descendants reached only through
  a newly assembled root.
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

`completeGC()` holds the shared lifecycle and root locks and refuses unless
`isGCComplete()` and a fresh target-only verification succeeds; there is no force override. A missing root is
an error, distinct from an unset or nil root. It flushes the target and publishes
the tombstone and completion marker before constructing a new `EtchStore` over the
target file. The new store has fresh caches; refs decoded from the target bind to
it and outlive the old store's close. A failed marker publication leaves the cycle
available for retry or cancellation.

The old store remains a functional view through successive collections. All open
handles use the same current write destination and root. The caller closes a handle
when nothing depends on refs bound to it; from then on those refs throw
`StoreException` on uncached reads. A legacy handle cannot manage another GC cycle.

An open A can hold refs to values written into B during A's collection, including
values omitted by B's later collection into C. B's file therefore remains mapped
while A is open, even if B's handle has closed. Closing A releases any closed
intermediate generations that no remaining older handle needs. The coordinator
also prevents their filenames being reused, including on systems that allow
unlinking an open file. A retained backup remains independent of this retirement.

Closing the current handle stops writes through every generation. Older open
handles retain read access until they close; any abandoned target is closed when
the last dependent handle closes and is recovered on the next open.

### Snapshot and collect

To retain the pre-cycle store as a backup while replacing the live store:

```bash
convex etch gc -e store.etch --backup store-before-gc.etch
```

The API equivalent is `completeGC(backupFile)`. The backup is a hard link to the
original file, created before cutover, so retaining it requires no full-file copy.
It must be a new filename on the same filesystem, with hard-link support, and
outside the store's GC filenames. Existing files are never overwritten. The
collected store keeps the logical live path; the usual deferred adoption applies
if Windows mappings still prevent renaming.

The snapshot contains the original root and all original entries, including
unreachable data. Roots and values written during collection belong to the new
live store. API callers must close the old store before opening the snapshot and
must finish any persists that began before the cycle. Successful collection
retains the original file's disk space until the snapshot is removed. Treat the
backup as read-only; copy it to a different filesystem for an independent backup.
After cutover, live-store writes go to the collected file and cannot change the
snapshot. Close the source handle and all older handles before opening the snapshot,
so they release its file lock. On Windows, hard links require
the same NTFS volume; they do not support a backup path on a different drive.
For an encrypted store, both files retain the source encryption policy and require
the source key. The collected file gets a new v3 salt; the snapshot keeps the old
file and its existing salt.

`--backup` cannot be combined with `--output`, which already leaves the source in
place. A backup left by an interrupted operation is not a confirmed snapshot;
inspect recovery state before using it (see the crash-recovery follow-up below).

### Cancel

Every write since `startGC()` exists only in the target, so `cancelGC()` is a reverse
migration:

1. Under lock, redirect writes back to the old file. Reads keep the target fallback
   and root reads keep coming from the target, which stays authoritative until the
   copy-back.
2. Drain in-flight target writes through every handle. Because each persist snapshots its write target for
   its whole descent, a brief lock is not enough: persists register in a counter while
   writing to the target and re-check the cancelling flag after registering, so a
   drained zero count is conclusive. The reverse migration's index scan requires a
   write-quiescent source.
3. `EtchUtils.migrate(target, this)`: everything in the target is persisted back into
   the old file, with the existence check reading the old file only. Idempotent, so a
   failed cancel can be retried.
4. Copy the target's root hash back, then drop, close and delete the target.

If cancellation fails or is interrupted, ordinary persists continue into the
source. Root updates are rejected until `cancelGC()` succeeds on retry, because
the target's root remains authoritative for copy-back and crash recovery.

Application-visible refs remain bound to the handle that supplied them throughout
cancellation. Current-handle reads retain the two-file bound while step 2 runs;
legacy reads may also search their retained generations.
Closing a store mid-cycle without cancelling abandons the cycle; startup recovery
reconciles it.

## File lifecycle and recovery

Target files are named generationally off the store's logical base file
(`EtchStore.getBaseFile()`): `f~`, `f~1`, `f~2` and so on, with `startGC()` taking
the first name not in use. An existing target is neither adopted nor deleted at
start: it is either stale (recoverable data) or a cancelled target still pinned by
memory mappings. Naming off the base file, which is inherited across cutovers, keeps
generation numbers small rather than growing `f~~~` one character per cycle.

Two on-disk markers record cutover and retirement:

- `<base>.gc-complete` names the **current** store file and is rewritten by every
  `completeGC()`. One marker, never a chain. Metadata is written and forced to a
  temporary file, then atomically replaced; unsupported atomic replacement fails
  the operation without truncating the previous marker.
- `<file>.gc-defunct` is a tombstone on a superseded file, written at cutover
  *before* the marker. It discriminates "retained content verifiably elsewhere:
  delete, never roll back" from an abandoned cycle that must be rolled back. A crash
  between tombstone and marker reads as "cutover did not happen" and the target rolls
  back, losing nothing.

On Windows, mappings can prevent renaming or deleting a file. The FFM
`MemorySegment` backend closes its arenas and unmaps deterministically when the
store closes. It is the default for Etch v2/v3 when available on Java 22+.
With FFM, closing a completed legacy view with no older dependent handle removes
its original filename and `.gc-defunct` tombstone immediately when filesystem deletion succeeds; the
successor remains usable. The lifecycle tests check this before reopening or
adopting the collected file, both with and without a retained snapshot. If an older
handle still needs the file, its close triggers retirement instead.
Etch v1, Java 21 and explicitly configured `MappedByteBuffer` stores use the
compatibility backend, whose mappings may remain after close. Deletion and
adoption can then be deferred; `startGC()` and recovery retry them. A completion
marker directs opens to the collected file until adoption succeeds. The snapshot's
hard link preserves the original data through removal of the original name.

`EtchStore.create(file[, config])` runs `EtchUtils.recover` before mapping anything.
Recovery is idempotent and logs its actions on the `convex.etch.recovery` logger. It
first header-validates every participating non-empty file (authenticating encrypted v3
headers with the caller's configuration) before any marker deletion, rollback or
adoption, so a wrong key or mismatched policy changes nothing on disk. Then:

- **Completed cutovers are adopted.** The marker-named file is installed as `<file>`
  and the superseded original name deleted. Without a backup link, that deletion
  reclaims the disk space; a requested snapshot keeps the old data allocated. If
  renaming fails (pinned mappings), recovery opens the marker-named file directly and
  retries adoption on the next start; it never opens stale data.
- **Defunct files are deleted, never rolled back.** Rolling them back would resurrect
  collected garbage.
- **Abandoned cycles are rolled back.** A target with neither marker reference nor
  tombstone holds writes that exist nowhere else; its entries are migrated into the
  live file, tolerating a torn tail from the crash, and the root advances only if its
  tree then verifies complete.

**Etch v3 differs on unclean crashes.** Recovery requires a valid `CLEAN_CLOSED`
checkpoint, published by a successful sync or close. A subsequent mutation first
publishes `OPEN`; a crash before the next clean checkpoint makes ordinary
`EtchStore.create` stop before changing the GC layout. The operator must then
deliberately select the maintenance and repair workflow described in
[ETCHv3.md](ETCHv3.md). V1 and v2 files have no clean-close state and keep the
best-effort rollback behaviour above.

If recovery cannot identify an existing live store, it fails before changing the
layout, rather than creating an empty store beside recoverable targets. Filesystem
directory durability and the full crash-boundary matrix remain tracked in
[#748](https://github.com/Convex-Dev/convex/issues/748).

The steady state after a restart is one live file `f`, plus any requested snapshots.
A typical sequence:

```
running on f      cycle 1: target f~    cutover: marker -> f~, f defunct
close old view:   f deleted             (now running on f~)
running on f~     cycle 2: target f~1   cutover: marker -> f~1, f~ defunct
restart:          recovery installs the marker-named file as f
```

## Online checkpoint export

`EtchStore.exportCheckpoint(rootHash, destination)` exports the supplied root into
a new, independent Etch file, returning its canonical `File` after copying,
flush, checked close and publication. The source stays online: writes, root
changes and successive GC cycles may continue. The explicit hash selects an
immutable graph, including a historical root still available through this handle;
it is never replaced with the source's current root. Nil, unset and empty sentinel
hashes retain their exact root hash. Other embedded roots require a stored entry.
Missing roots or descendants fail the export.

```java
Hash root = live.getRootHash();
File checkpoint = live.exportCheckpoint(root, new File("backup.etch"));
try (EtchStore restored = EtchStore.create(checkpoint, live.getConfig())) {
    // Immediately usable while live continues serving the application.
    EtchVerifier.verifyPersisted(restored.getEtch()); // Separate full scan, if wanted.
}
```

`EtchCheckpoint` owns export; `EtchGC` provides a private read lease on the physical
files visible when export begins. The lease survives caller-initiated close,
cutover and cancellation without blocking ordinary application writes or holding
a lifecycle lock across copying. The coordinator delays closing/deleting those
files until the last lease releases them, and prevents GC from reusing their
filenames. Cancelled targets receive retirement markers before becoming detached,
so a crash during a later export cannot make recovery resurrect collected data.
This does not keep exported data in future live generations; it retains only the
old files for the duration of the export. Long exports can therefore delay disk
space reclamation. A hash captured before the call must still be available when
the lease is acquired.

GC and checkpoints use the same `EtchTreeTransfer` child-first walk and physical
destination pruning. Checkpoints decode through a private uncached read view,
without consulting or rebinding application caches. Each destination entry reaches
`PERSISTED` before inheriting that entry's recorded source `ANNOUNCED` status.
An announced parent does not announce its children. Source status is observed per
entry in the leased files during traversal; upgrades after that observation or in
later GC generations need not appear in the export. This is an exact data-root
snapshot, not a simultaneous snapshot of all mutable announcement flags.

The overload accepting `AMap<AString,ACell>` merges only supplied fields over
`live.getConfig().getMap()`, then compiles and validates the result once. The
four-argument overload also accepts an optional replacement key resolver. Null
overrides/resolver inherit the source policy; an explicit null map value clears
the public-key hint. For example, changing encrypted v3 output to v1 also requires
compatible cipher, index-encryption, hint and mapping settings. Invalid merged
combinations fail rather than resetting omitted options. Newly created encrypted
files always receive fresh file salts and derived cryptographic material. Runtime
cache/mapping settings are not encoded in the file; pass the chosen effective
configuration when reopening. Export's private working cache disables L2 to keep
its heap bounded.

The child-first copy establishes completeness as it writes. Export does not run
an additional verification pass. Callers can independently invoke
`EtchVerifier.verifyPersisted(etch)` on an open, quiescent checkpoint. This checks
the index, its stored root, every entry's persisted status and every branch
against that file alone. Copying uses O(depth) traversal storage plus a fixed L1
cache; this verification uses bounded index/entry working storage. Other checks
are available separately; see [Verification](#verification).

The destination must be absent, including an empty file or dangling symlink, and
must not collide with the source's GC namespace or have its own GC sidecars.
Export builds a private `.etch-checkpoint-*.partial` file in the destination
directory. After copying, forcing and closing it, publication creates a hard
link at the requested name and refuses an occupied name, including a competing
export. This links the newly built file, **not the live source**. The destination
filesystem must support hard links (NTFS on Windows); it may differ from the
source filesystem. No cross-filesystem rename is needed. This also works when
Windows MBB mappings prevent immediate deletion of the private staging filename.
Removing staging is best effort; the completed checkpoint needs neither that
name nor any source or GC sidecar.

Thread interruption cancels copying before publication and remains
set on return. Missing data and copy, flush, close or publication failures do not
report success or publish a partial checkpoint. Failure may leave a private
staging file, which can be removed once its mappings have gone. Publication is
the commit point: later staging cleanup failure cannot revoke a complete export.
After a process crash the final name, if published, names a complete, clean file.
File contents are forced, but survival of its newly created directory entry
across power loss depends on the filesystem/platform; the API does not promise
portable directory-entry durability. Interrupted exports have no resume protocol.

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

## Verification

`EtchVerifier` collects the explicit Etch verification operations. They operate
on the selected physical file, without falling back to live caches or another GC
generation. Export never invokes them automatically.

| Operation | Guarantee | Usage |
|---|---|---|
| `findMissing(etch, rootHash)` | Every branch reachable from this root is physically present; returns missing hashes | GC, recovery and repair; ignores status flags and unrelated entries |
| `verifyPersisted(etch)` | Stored root, every indexed entry and every branch are at least `PERSISTED`; also checks the index | Checkpoints or other entirely persisted files; throws on failure |
| `validate(file[, config[, options]])` | Strict index/extent checks, content hashes, canonical CAD3 encodings and root completeness | Offline integrity checks, including the `etch validate` CLI; returns a report |
| `IndexVisitor` with `etch.visitIndex(visitor)` | Hash paths and collision-chain structure, with entry counts | Lightweight index checks on an open file |

The open-file operations require the caller to keep the file open and
write-quiescent; they neither mutate nor close it. Strict validation opens a
read-only maintenance reader under an exclusive lock; encrypted files require
the key resolver in `config`. Use this operation for potentially corrupt files.
`findMissing` and `verifyPersisted` do not recompute content hashes or validate
canonical encodings. Strict validation does not require every record to claim
`PERSISTED`: an ordinary store can legitimately contain `STORED` records.

The root walk shares `StoreTransfer.verify` through a private uncached reader.
Index visitors and strict validation share hash-path and collision-chain checks.
`EtchRecordVerifier` remains the shared internal record checker for strict
validation and repair. The deprecated `EtchUtils.verify` and `FullValidator`
entry points delegate to `EtchVerifier`; the previous `EtchStrictValidator` API
has been renamed to `EtchVerifier`.

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
| Write of novel data | 1x | target lookup plus an old-file status lookup |
| Write touching an unmigrated tree | 1x | one-off copy of that subtree, within the O(live data) total |
| Disk | one file | both files, transiently |
| Traversal heap | none | sweep stack; cutover verification retains one hash per visited entry |

These lookup bounds describe the current handle. Keeping legacy handles open
retains their original and intermediate files, and cold legacy reads may search
those generations. Write routing remains constant-time and allocates no state
per operation, regardless of how many older handles remain open.

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
- **Crash durability** depends on filesystem metadata ordering as well as data
  flushes; the full guarantee and failure-injection coverage are tracked in #748.
  The original file is only ever appended to by rollback.
- **Free disk space** must cover the expected collected size before starting.
- **An empty root** collects to an empty store plus header. Correct, if surprising.


The scheme assumes only content-addressed immutable entries, a single root, monotonic
status and a `PERSISTED`-style whole-tree level, so any `AStore` implementation can
adopt the same four phases and INV-1 pruning; the transfer and migrate primitives are
already store-agnostic.

## Where the code lives

| Concern | Location |
|---|---|
| Shared routing, synchronisation and file lifetime | `convex.etch.EtchGC` |
| Online checkpoint export | `EtchStore.exportCheckpoint`, `EtchCheckpoint`; private file leases and `EtchReadView` |
| Shared GC/checkpoint child-first walk | `EtchTreeTransfer` |
| Lifecycle API | `EtchStore.startGC()`, `transferGC()`, `isGCComplete()`, `verifyGC()`, `completeGC()` / `completeGC(backupFile)`, `cancelGC()`, `isGCInProgress()`, `getBaseFile()` |
| Tree transfer with INV-1 pruning | `convex.core.store.StoreTransfer.transfer(dest, ref[, status])`; `StoreTransfer.verify(store, rootHash)` |
| Whole-store migration | `convex.etch.EtchUtils.migrate(source, dest)` |
| Verification | `EtchVerifier.findMissing`, `verifyPersisted`, `validate`, `IndexVisitor` |
| Recovery | `EtchUtils.recover(file[, config])`, called by `EtchStore.create` |
| Status rules | `Ref.withStatus`, `AStore.isForeign`, `Refs.checkConsistentStores`; pinned by `convex.etch.EtchStatusIntegrityTest` |
| Index enumeration | `Etch.visitIndex`, `EtchUtils.EtchCellVisitor` |
| CLI | `convex etch gc [--backup file \| --output file]`, `etch migrate --into <dest> [--set-root]`, `etch recover`, `etch validate [-m N]` in `convex.cli.etch` |
| Tests and example | `EtchGCLifecycleTest`, `EtchGCRecoveryTest`, `EtchConfiguredLifecycleTest`, `StoreTransferTest`, `EtchCLITest`, `EtchEncryptionCLITest`; `convex.core.examples.EtchGCExample` (test tree, not in the suite) runs a full online cycle under concurrent load |

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
