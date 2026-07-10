# Etch Garbage Collection — Design

> "Garbage collection is left as an exercise for the reader." — `EtchStore.java`

This document specifies a garbage collection (GC) procedure for Etch, the append-only
content-addressed store used by Convex. The design generalises to other lattice store
implementations (see [Generalisation](#generalisation-to-other-lattice-stores)).

## Goals

1. **Online collection**: rebuild a garbage-collected store in parallel with the existing
   store running. New writes are redirected to the new (target) store; read misses fall
   back to the old store.
2. **Reachability-based, explicit retention**: the collected store retains the root
   value and all cells (transitively) reachable from it, plus anything explicitly
   persisted after collection starts. Everything else is garbage. There is no implicit
   retention (no copy-on-read): applications keep extra values by persisting them
   explicitly or folding them into the main root before cutover.
3. **Bounded marginal cost**: while a GC cycle is in progress, store operations cost no
   more than ~2x normal (one extra index lookup on the read path), plus a one-off
   migration cost proportional to live data.
4. **CLI operation**: can be run independently offline via `convex etch gc`.
5. **Store-to-store migration**: the same machinery must support migrating a store into
   an *existing* (non-empty) store — i.e. ensure everything from the old store is
   persisted in the destination. GC is then just migration into a fresh store plus
   cutover plumbing.
6. **Composable primitives**: build the implementation from small, well-tested,
   composable operations — reusing existing ones (`Cells.persist`, `storeTopRef`,
   `Refs.visitAllRefs`, `Etch.visitIndex`) wherever possible rather than writing a
   monolithic collector.
7. **Locality-aware layout**: related cells should be persisted sequentially in the new
   store. The target is append-only, so write order *is* physical layout — a
   depth-first copy clusters each subtree contiguously, improving page/cache locality
   for later tree traversals.
8. **Explicit cutover, user responsibility**:
   - The live user (peer operator / embedding application) is responsible for ensuring
     transfer is complete before closing the old store.
   - `Ref`s to values persisted only in the old store *may* become invalid (throw
     `StoreException` on read) once the old store is closed. Callers must not retain
     references to unreachable ("old") values across cutover.

## Non-goals

- **In-place compaction.** Etch is append-only by design; we never mutate or truncate the
  existing file. GC is copying collection into a fresh file.
- **Automatic scheduling.** When to collect, and when to cut over, are operator decisions.
- **Concurrent multi-process access.** Etch holds an exclusive file lock; GC operates
  within the single process that owns the store (or offline via the CLI).

## Background

### Current Etch structure

- Append-only file: 44-byte header (magic, version, data length, 32-byte root hash),
  followed by radix-tree index blocks and data entries (`Etch.java`).
- Data entries are `key (32) | flags (1) | memorySize (8) | length (2) | encoding (N)`.
- Entries are immutable once written, except the flags byte and memory size, which may be
  upgraded monotonically in place (`Etch.updateInPlace`).
- The index grows forever; deleted/unreachable data is never reclaimed. A long-running
  peer accumulates every Belief, State and intermediate value it has ever persisted.

### Existing partial plumbing

`EtchStore` already contains the beginnings of this design:

- `EtchStore.startGC()` creates a target Etch file (`<file>~`) and copies the root hash.
- `EtchStore.getWriteEtch()` returns the target during a GC cycle.

However the implementation is incomplete and currently *incorrect* if `startGC()` is called:

- `EtchStore.storeRef(...)` writes via `etch.write(...)` directly, so new writes do **not**
  go to the target (only `setRootData` / `getRootHash` use `getWriteEtch()`).
- `refForHash` / `readStoreRef` read only the old `etch`, so anything written to the
  target would be invisible to reads.
- There is no transfer (copy) procedure, no cutover, no abort, and `close()` ignores the
  target.

This design completes and corrects that plumbing.

### Ref status semantics (load-bearing for this design)

`Ref` status levels are monotonic: `UNKNOWN < UNVERIFIED < STORED < PERSISTED < ...`.
The key property we exploit:

> **`PERSISTED` means the cell *and its entire reachable tree* are in the store.**

`STORED` guarantees only the single top-level entry. This distinction drives the sweep's
pruning rule below.

## Building blocks

The collector is a composition of small primitives, most of which already exist and have
test coverage. New code should extend this table, not bypass it.

### Existing primitives

| Primitive | Where | Role in GC/migration |
|---|---|---|
| `Etch.read` / `Etch.write` | `convex.etch.Etch` | entry-level IO; `write` is the single synchronised append point |
| `Etch.updateInPlace` | `Etch` | monotonic flag/memorySize merge for already-present entries |
| `Etch.visitIndex` + `EtchUtils.EtchCellVisitor` | `convex.etch` | full index scan — enumerates *every* entry (migrate-all mode, statistics, verification) |
| `EtchUtils.FullValidator` | `convex.etch` | structural validation of a produced store |
| `AStore.storeTopRef` / `storeRef` | `convex.core.store` | recursive persist with status semantics — the write half of the sweep |
| `Cells.persist(cell, store)` | `convex.core.data.Cells` | "ensure this tree is `PERSISTED` in that store" — the sweep *is* this, given a store-aware read path |
| `Cells.visitBranchRefs` | `Cells` | enumerate non-embedded children of a cell (traversal step) |
| `Refs.visitAllRefs` / `accumulateRefSet` | `convex.core.data.Refs` | generic ref-tree walks (verification, counting) |
| `AStore.setRootData` / `getRootRef` | `AStore` | root management on both ends |
| `RefSoft.withStore` / `Ref.toSoft` | `convex.core.data` | store-pointer rebind only — moves no data. Internal plumbing for store implementations (used immediately after a physical write, where the data is present by construction); not part of the application API |

### New primitives

| Primitive | Role |
|---|---|
| `StoreTransfer.transfer(source, dest, ref)` | copy one reachable tree from `source` into `dest`, depth-first, preserving flags, pruning on dest-resident `PERSISTED` entries (INV-1). Iterative (explicit stack), locality-ordered. The core of everything below. |
| `StoreTransfer.migrate(source, dest)` | ensure *everything* in `source` is persisted in `dest`: index scan (`visitIndex`) driving `transfer` per top-level entry, then root transfer. Destination may be non-empty. |
| `StoreTransfer.verify(dest, rootHash)` | walk the tree in `dest` **only** (no fallback); return missing hashes. |
| `Etch.copyEntry(hash, targetEtch)` | (optimisation, later) raw entry copy — `key|flags|memorySize|length|encoding` verbatim, no decode/re-encode |
| `EtchStore.startGC/completeGC/cancelGC` | lifecycle: fresh target + read-fallback/write-redirect + cutover. GC = `startGC()` → `transfer(old, target, root)` → `completeGC()` (returns the new store). `cancelGC()` = reverse `migrate(target, original)` + root copy-back |

`StoreTransfer` (name TBD; `convex.core.store`) works at the `AStore` level so the same
primitives serve Etch-to-Etch GC, Etch-to-Etch migration, and any future store backend.

**Soundness of pruning into a non-empty destination**: INV-1 pruning ("`PERSISTED` in
dest ⇒ subtree in dest") is exactly the documented meaning of the `PERSISTED` status, so
it is sound for any destination whose flags are truthful — including existing populated
stores, not just fresh GC targets. Truthful flags are invariant S2 in
[Ref status invariants](#ref-status-invariants-proposed); the known violation V2 (foreign
ref status written verbatim) must be fixed before pruning can be trusted on stores that
have received cross-store writes. A `--paranoid` transfer mode can disable pruning and
descend everything for destinations of unknown provenance.

### Copy ordering and locality

`transfer` writes each cell's children immediately before the cell itself (post-order
DFS). In an append-only file this places every subtree in one contiguous byte range, with
the parent adjacent to its children — so later traversals (state reads, Belief merges,
sync serving) touch sequential mapped pages instead of scattering across the file in
historical write order. This is one of the main practical wins of GC beyond space
reclamation, and it falls out of the traversal order for free:

- **Offline mode** produces an ideal layout: the entire store is one DFS linearisation
  of the root.
- **Online mode** approximates it: live novelty writes interleave with sweep output, but
  each swept subtree remains contiguous.

For this reason the sweep should *not* be parallelised across subtrees within a single
transfer (interleaved appends would destroy clustering); the synchronised single-writer
`Etch.write` already enforces this.

## Design overview

A GC cycle has four phases, driven through `EtchStore`:

```
        startGC()            transfer / sweep              completeGC()
IDLE ─────────────▶ COLLECTING ───────────────▶ (complete) ─────▶ new EtchStore returned,
                        │                                          old store RETIRED
                        │
                        └── cancelGC() ──▶ roll back target→old ──▶ IDLE (original store,
                                                                     nothing lost)
```

### Refs and store identity

`RefSoft` binds to the store it belongs to. Two consequences shape the lifecycle:

- **The result of GC is a new store.** `completeGC()` returns a fresh `EtchStore`
  wrapping the target file; the old `EtchStore` object is retired, not mutated into the
  new one. Callers swap their handle (peer server store field, `Stores` context) to the
  returned store.
- **During the cycle, all refs handed out by the collecting store stay bound to the old
  store object** — including refs for values physically written to the target file. The
  target is internal until cutover. This is what makes `cancelGC()` non-disruptive (no
  app-visible store ever disappears) and concentrates the entire swap at `completeGC()`.

After cutover the retired store remains open and readable — refs bound to it keep
resolving, against both files — until the user closes it. Once it is closed, refs bound
to it throw `StoreException` on any uncached read, *even for values that were migrated*
(the ref's store pointer is dead, not the data). Values still soft-reachable in memory
or in the closed store's cache may continue to resolve until reclaimed — immutable data
cannot be stale. All transferred values remain retrievable by hash from the new store.
This is the "don't hang on to old things at cutover" contract, and the error names the
real cause (store closed) rather than masquerading as missing data.

The three-state read contract (July 2026, `StoreException` + `EtchStore.refForHash`):
`null` = proven absent; `MissingDataException` = the store looked and the value is not
there; `StoreException` (unchecked) = the store cannot look (closed, or IO failure). A
failed read is a fundamental failure of code or infrastructure assumptions — it is never
evidence of absence, and application code should not attempt to handle it. `refForHash`
stays lightweight: no pre-flight liveness check, the exception arises only when a read
actually fails.

**Carrying a ref across is a persist, not a pointer swap.** `RefSoft.withStore` only
rebinds the store pointer; it moves no data, and the ref's status flags (`PERSISTED`
etc.) would over-claim in a store that never received the tree. The correct operation is
to re-persist the value into the new store — `Cells.persist(value, newStore)` (or
`StoreTransfer.transfer(oldStore, newStore, ref)`) — which recursively ensures all
children are present and returns a ref correctly bound (and correctly flagged) to the
new store. This must happen while the value is still resolvable: either during the cycle
(a persist via the collecting store lands in the target — the explicit-keep path in the
retention contract) or after cutover but before the retired store is closed.

`RefSoft.withStore` is internal plumbing, not application API: it is sound only where
the data is known to be in the destination by construction (store implementations use it
via `toSoft` immediately after a physical write). It should not be `public` —
`withStore` becomes package-private, used only by `toSoft` and tests. `Ref.toSoft(store)`
must stay `public` for cross-package store implementations (`convex.etch`), but its
javadoc should mark it store-internal and point applications at `Cells.persist` for
moving values between stores.

The new store starts with cold L1/L2 caches; this is a transient warm-up cost, not a
correctness issue (caches are hash-keyed and immutable-valued).

### Ref status invariants (proposed)

Status flags are only meaningful relative to a store. Making that relativity explicit
gives four invariants that the transfer/GC machinery — and single-store robustness in
general — depend on:

- **S1 — status is store-relative.** A `RefSoft`'s status is a claim about its bound
  store. A `RefDirect` carries no binding; its status claims persistence "somewhere in
  this process" and is coherent only under single-store usage. The common shape — a
  direct top-level ref over soft children — is fine: the tree's claims are anchored by
  the children's (single) store.
- **S2 — store entry flags are authoritative for that store.** An entry's flags byte
  records only what has actually been achieved *in that store*: `STORED` ⇒ this entry
  present; `PERSISTED` ⇒ entire subtree present here; `ANNOUNCED` additionally records a
  peer-level commitment. The write path must therefore record the status *achieved by
  the write*, never status imported from a foreign ref. INV-1 pruning is sound only if
  S2 holds — an unearned `PERSISTED` flag in a store makes pruning skip a hole.
- **S3 — rebinding drops status.** Changing a ref's store binding invalidates its
  claims: `withStore(newStore)` must drop status to `UNKNOWN` (the soft value may be
  kept — the result is an honest "look it up here" pointer, equivalent to
  `createForHash` plus a cached value). Status is then re-earned, never asserted:
  reading through the store merges the store's authoritative flags back in (the existing
  `RefSoft.getValue` merge), and a proper persist re-establishes higher levels by
  descending and verifying/writing children. The one shortcut: a store may rebind at the
  achieved level immediately after its own write (post-descent `PERSISTED` writes keep
  `PERSISTED`; a bare top-level write justifies at most `STORED`).
- **S4 — tree coherence.** All `RefSoft`s within one cell tree bind to the same store
  (`Refs.checkConsistentStores` is the checker); direct refs are store-neutral. Store
  read/write paths must never return, attach or cache a ref bound to a foreign store.

**DECIDED (July 2026): recorded levels are only what is immediately provable for the
given store.** The write boundary caps status at the level the operation actually
achieved (a `STORED`-level write records `STORED` regardless of status carried by the
incoming ref; a `PERSISTED`-level write records `PERSISTED` because children were
descended first). Merging with the store's *own* existing entry flags stays monotonic —
those were earned by this store. Status carried by a ref is a claim about *its* store,
never evidence for another.

Two violations of these invariants were found in the `EtchStore`/`Etch` write path.
Enabled, deterministic regression tests in `convex.etch.EtchStatusIntegrityTest` prove
each defect; the same class carries positive controls that guard against over-capping
(earned persist, legitimate upgrade, no-downgrade):

- **V1 — FIXED (July 2026)** (broke S4 — `testForeignRefsRebindOnPersist`,
  `testSubStoredRequestDoesNotCacheForeignRefs`): persisting a foreign `RefSoft` whose
  top-level cell is embedded skipped the `toSoft(this)` rebind, returning — and caching
  in the destination's `refCache` — a ref still bound to the source store; the
  sub-`STORED` cache-only path likewise cached foreign-bound refs. Fix: new
  `AStore.isForeign(ref)` helper; the write boundary rebinds foreign refs after its own
  write (sound by construction), callers never cache foreign refs, and
  `EtchStore.addToCache` throws on any foreign ref as a defensive backstop —
  guaranteeing every ref served from the cache is for the correct store.
- **V2 — FIXED (July 2026)** (broke S2 — `testForgedStatusNotRecorded`,
  `testForeignStatusCappedAtStoredLevel`, `testAnnouncedNotAdoptedThroughPersist`):
  the write path recorded `max(carried, requiredStatus)` instead of exactly
  `requiredStatus`, because `withMinimumStatus` only raises and `Etch.appendData` writes
  the incoming ref's flags. Shapes: unproven status (forged, or earned in another store)
  written at `STORED` level recorded an unearned `PERSISTED` claim with no children; a
  foreign `ANNOUNCED`-flagged ref written at `PERSISTED` level recorded an unearned
  `ANNOUNCED` claim, which would make `Cells.announce` skip the value in novelty
  broadcasts. Fix: new `Ref.withStatus(int)` (exact status, preserves non-status flags)
  applied at the write boundary in `EtchStore.storeRef` — recorded status is exactly
  `requiredStatus`, with store-earned entry flags still merged monotonically by
  `updateInPlace`.

Note `Ref.MARKED` (status 5, "marked in the store for GC copying") already exists in
`Ref`; this design does not use it — INV-1 pruning replaces mark state entirely.

### Retention contract

What survives cutover is exactly what was **explicitly persisted** into the target:

1. **The root tree**: the current root and all reachable children, via the sweep (and
   maintained thereafter by every `setRootData`).
2. **Anything persisted after `startGC()`**: all writes go to the target, so any value
   the application stores or persists during the cycle is retained.
3. **Explicit keeps**: anything else the application wants to hang onto, kept by either
   - folding it into the main root before cutover (the usual pattern for peers), or
   - explicitly persisting it during the cycle — `Cells.persist(value, store)` is the
     keep operation: with the write path above, it migrates that tree into the target
     through the normal persistence machinery. No separate "pin" API is needed.

Nothing is retained implicitly. Reading a value during the cycle does *not* keep it;
copies to the new store are always the result of an explicit persist. Peers wanting
multiple roots (e.g. recent States for catch-up serving) reference them from the main
root before cutover rather than relying on any store-level multi-root feature.

### Phase 1: `startGC()`

Synchronised on the store:

1. Create the target Etch file (fresh header, empty index).
2. Copy the current root hash into the target.
3. Publish `target` (volatile field) — from this point:
   - **Writes** go to the target (`getWriteEtch()`).
   - **Reads** check the target first, then fall back to the old file.

### Phase 2: read/write paths during collection

#### Read path (`readStoreRef`)

```
cache hit?            → return               (unchanged, dominant case)
target.read(hash)     → hit? return          (new / already-migrated data)
etch.read(hash)       → hit? return          (old data, not yet migrated)
                      → miss? return null
```

Worst case is two index lookups instead of one — the ≤2x bound. The in-memory cache
(L1 `RefCache` + L2 `SoftCache` in `ACachedStore`) sits in front of both, so in practice
hot-path amplification is far below 2x.

Reads never mutate the target. There is deliberately **no copy-on-read**: migration into
the target happens only through explicit persistence (the sweep, live writes, and any
explicit keeps — see the retention contract below). This keeps the target's contents a
pure function of what was explicitly persisted, keeps the read path cheap and side-effect
free, and preserves the DFS layout of the sweep.

#### Write path (`storeRef` / `storeTopRef`)

All physical writes go to `getWriteEtch()` (the target while collecting). One critical
change to the existing logic:

> **During a GC cycle, an existing entry found only in the *old* file does not satisfy a
> persistence request.** The recursive persist must descend and copy such entries (children
> first) into the target, rather than early-returning on `existing.getStatus() >= requiredStatus`.

Concretely, `storeRef`'s "check store for existing ref" step distinguishes *where* the hit
came from. A target hit with sufficient status early-returns as today. An old-file hit
forces the normal recursive write path, which copies the cell (and, at `PERSISTED`+
levels, its children) into the target.

This establishes and maintains the central invariant:

> **INV-1**: an entry present in the *target* with status ≥ `PERSISTED` has its entire
> reachable tree present in the target.

INV-1 holds because (a) the target starts empty, (b) every `PERSISTED`-level write into
the target descends children first (post-order), and (c) in-place flag upgrades to
`PERSISTED` only happen via that same descent. Entries written to the target at `STORED`
level (e.g. partial message data) make no subtree claim, consistent with `STORED`
semantics.

Cost: the first persist that touches a large unchanged structure pays a one-off descent
that migrates it. Descent prunes wherever it finds a target-resident `PERSISTED` entry
(sound by INV-1), so total migration work across the whole cycle is O(live data), not
O(live data × writes).

#### Root updates

`setRootData` already routes through `storeTopRef(..., PERSISTED, ...)` and
`getWriteEtch().setRootHash(...)`. With the write-path change above, **every root update
during a GC cycle automatically guarantees the new root's full tree is in the target**
(INV-1). The old file's root hash is never touched.

### Phase 3: the transfer sweep

The sweep migrates the current root's reachable tree into the target. With the write-path
change it is conceptually just `Cells.persist` of the root through the store's
target-then-old read path:

```java
public void transferGC() throws IOException {
    Ref<ACell> root = getRootRef();          // resolves via target-then-old read path
    StoreTransfer.transfer(this, this, root); // descends, copies old→target, prunes on INV-1
}
```

(Source and destination are the same `EtchStore` here because the store's own read/write
paths already implement the old/target split during a cycle; the standalone
`StoreTransfer.transfer(source, dest, ref)` form covers distinct stores.)

Implementation notes:

- **Iteration, not recursion.** State trees can be deep; `transfer` must use an explicit
  stack rather than the current recursive `updateRefs` pattern (which already carries a
  stack-overflow TODO). This is worth fixing for `storeRef` generally.
- **Ordering.** Post-order DFS, children immediately before parent — see
  [Copy ordering and locality](#copy-ordering-and-locality).
- **Flag preservation.** Copied entries carry their existing flags (status, e.g.
  `ANNOUNCED`) and memory size from the old entry, merged via the normal
  `Ref.mergeFlags` path. A peer must not lose `ANNOUNCED` status across GC.
- **Missing children.** If a child is missing from the old store (pre-existing partial
  data), the sweep skips that subtree with a warning by default; a `strict` mode fails
  the cycle instead. The copied parent keeps its old flags — if the store previously
  (incorrectly) claimed `PERSISTED` for it, GC neither fixes nor worsens that.
- **Raw-copy optimisation (later).** The sweep currently decodes each cell and re-encodes
  on write; since encodings are canonical this is byte-identical but costs CPU. A raw
  entry copy (`Etch.copyEntry(hash, targetEtch)`) that reads
  `key|flags|memorySize|length|encoding` and appends verbatim, decoding only to enumerate
  child refs, halves the CPU cost. Not required for correctness.
- **Concurrency.** The sweep runs on a background thread. `Etch.write` is synchronised
  per-`Etch`, so sweep writes interleave safely with live novelty writes to the target.
  Reads are lock-free on mapped buffers (existing behaviour).

#### Completeness

After `transferGC()` returns:

- The root at the time the sweep *finished* is fully in the target (INV-1).
- Every root set *after* `startGC()` is fully in the target (write-path guarantee).

So a single completed sweep after `startGC()` means the *current* root's tree is entirely
in the target, and it stays that way. `isGCComplete()` reports this (sweep finished flag),
and a belt-and-braces `verifyGC()` walks the current root in **target-only** mode
(no old-file fallback, no pruning) and reports any missing hash — this is what the user
runs to satisfy their "ensure transfer is complete" responsibility.

What is *not* transferred, by design:

- Anything not reachable from the current root and not explicitly kept (that's the
  garbage).
- Values held only at `STORED`-or-below in the old file and no longer reachable — e.g.
  stale Belief fragments, orphaned message data.

Callers holding `RefSoft`s to such values keep working while the soft reference is alive
and while the old file remains open; afterwards they get `MissingDataException` (store
open, value genuinely absent) or `StoreException` (store closed). This is the documented
contract.

### Phase 4: cutover — `completeGC()`

Synchronised on the store, and the user's explicit call. Returns the **new store**:

1. Check `isGCComplete()`; refuse otherwise (a `force` variant may override, accepting
   possible data loss).
2. `flush()` the target.
3. Construct the result: a new `EtchStore` wrapping the target `Etch` (fresh caches;
   `Etch.setStore(newStore)` repoints the target file so refs decoded from it bind to
   the new store).
4. Retire this store: writes now throw `IllegalStateException`; reads keep resolving
   against both files so refs bound to the old store keep working.
5. Write the `gc-complete` marker; return the new store.

The caller then swaps its handle to the returned store and closes the retired store once
it no longer depends on refs bound to it (see
[Refs and store identity](#refs-and-store-identity)). Old-file disposal follows the file
lifecycle below.

### Cancelling — `cancelGC()`

Cancel is **not** simply deleting the target: every write since `startGC()` — novelty,
status upgrades, root updates — exists only in the target file. Cancel must roll the
target's contents back into the original store, which is exactly the reverse migration
primitive:

1. Under lock: redirect writes back to the old file (state `CANCELLING`); reads keep the
   target fallback.
2. `StoreTransfer.migrate(target, original)` — everything in the target is persisted
   into the old file, flags preserved (idempotent: `updateInPlace` merges flags for
   entries the old file already has).
3. Copy the target's current root hash back to the old file (its tree is now guaranteed
   present by step 2).
4. Under lock: drop the target fallback; close and delete the target file. State `IDLE`.

Because all application-visible refs were bound to the collecting store throughout the
cycle, cancellation is invisible to the application beyond transient ≤2x reads while
step 2 runs. Nothing persisted during the cycle is lost.

`close()` must close *both* files if a cycle is in progress (fixing the current leak).
Closing a store mid-cycle without cancelling abandons the cycle; the startup recovery
below reconciles it.

### File lifecycle and naming

Target file: `<file>~` (as currently in `startGC()`), e.g. `etch.db~`.

Renaming is awkward on Windows: memory-mapped files cannot be renamed or deleted while
mappings exist, and Java offers no reliable explicit unmap for `MappedByteBuffer`. So:

- **After cutover, the store keeps running on the `~` file.** No rename is attempted
  while live.
- Cutover writes a sidecar marker `<file>.gc-complete` (containing the new root hash)
  and best-effort attempts to delete/rename the old file to `<file>.old` — this usually
  succeeds on POSIX and may fail harmlessly on Windows.
- **Adoption at startup**: `EtchStore.create(file)` checks, before mapping anything:
  - If `<file>.gc-complete` and `<file>~` exist: rename `<file>` → `<file>.old`
    (if still present), rename `<file>~` → `<file>`, delete the marker, open as normal.
  - If `<file>~` exists *without* the marker: a GC cycle died mid-flight. The `~` file
    may hold writes made after `startGC()` that exist nowhere else, so it is **not**
    deleted blindly: startup performs an offline roll-back — the same
    `migrate(target, original)` used by `cancelGC()`, best-effort over the target's
    recorded data length (tolerating a torn tail from the crash), adopting the target's
    root hash only if its tree verifies as fully present — then deletes the `~` file
    and opens as normal.

Crash safety, therefore: the original file is never modified during a cycle except by
roll-back (which only adds entries and monotonically merges flags), a completed cutover
is adopted idempotently, and an interrupted cycle is rolled back rather than discarded —
writes made during the cycle survive a crash up to the usual unflushed-tail window.

Operators wanting to reclaim disk immediately can delete `<file>.old` once they are
satisfied; the design never deletes user data automatically except a `~` file whose
contents have been rolled back or adopted.

## Store-to-store migration

`StoreTransfer.migrate(source, dest)` ensures everything from the source store is
persisted in an existing destination store. This is the general form; GC is the special
case of a fresh destination plus root-only coverage plus cutover.

Differences from GC:

- **Coverage is the whole source, not just the reachable set.** `Etch.visitIndex` with an
  `EtchCellVisitor` enumerates every entry; each non-embedded entry is transferred with
  its flags. (Entries reachable from the source root get transferred anyway via the root
  pass; the index scan additionally carries over `STORED`-level and currently-unreachable
  data, which is the point of "everything".)
- **Destination may be live and non-empty.** Transfer uses the destination's normal
  `storeTopRef` path, so it composes with concurrent use, novelty handling and flag
  merging (`updateInPlace`) on the destination.
- **Roots need a policy.** Default: leave the destination root untouched and report the
  source root hash; `--set-root` adopts the source root. (Merging two roots is a
  lattice-level operation above this layer.)
- **No cutover phase** — nothing changes about either store's identity; the source is
  simply closed by the user when satisfied.

Use cases: consolidating an archived store into a live one, moving a peer's data into a
shared store, seeding a fresh store from several sources, and (with a fresh destination)
GC itself.

## CLI: `convex etch gc`

New subcommand alongside `EtchInfo`, `EtchValidate` etc. in `convex-cli`
(`convex.cli.etch.EtchGC`):

```
convex etch gc -e <store.etch> [--output <out.etch>] [--verify] [--strict] [--force]
```

Offline mode (the store must not be in use — Etch's exclusive file lock enforces this):

1. Open source, open target (default `<store.etch>~`, or `--output`).
2. Copy root hash; run the transfer sweep (no live writes, so this is a single pass).
3. `--verify` (default on): target-only walk from root; report cell count and bytes
   before/after.
4. Close both; perform the marker + rename dance from the file lifecycle above so that,
   where the platform allows, the user ends up with `<store.etch>` collected and
   `<store.etch>.old` as the uncollected original. Where rename fails (Windows mappings),
   print the manual swap instructions — the adoption-at-startup logic also covers it.
5. `--strict` fails on any missing child; default skips with a warning and a non-zero
   summary count.

Output reports: entries copied, entries discarded (index scan count minus copied), bytes
reclaimed.

A sibling subcommand exposes migration:

```
convex etch migrate -e <source.etch> --into <dest.etch> [--set-root] [--strict]
```

which runs `StoreTransfer.migrate` into an existing destination (created if absent) and
reports entries transferred / already present. Both subcommands are thin wrappers over
the same primitives.

Live mode for a running peer (trigger `startGC`/`transferGC`/`completeGC` over the peer admin
API) is a natural follow-up but out of scope for the first iteration.

## API summary

| Method | Phase | Notes |
|---|---|---|
| `EtchStore.startGC()` | begin | exists; keep. Makes `etch`/`target` volatile. |
| `StoreTransfer.transfer(source, dest, ref)` | sweep | new core primitive; iterative post-order DFS copy with INV-1 pruning. |
| `StoreTransfer.migrate(source, dest)` | migration | new; full index scan + transfer into an existing store. |
| `StoreTransfer.verify(dest, rootHash)` | pre-cutover | new; dest-only reachability walk, returns missing hashes (empty = complete). |
| `EtchStore.transferGC()` | sweep | new; `transfer` over the store's own old/target split; blocking, call from background thread. |
| `EtchStore.isGCInProgress()` | any | new; `target != null`. |
| `EtchStore.isGCComplete()` | sweep done | new; sweep finished (INV-1 makes this sticky). |
| `EtchStore.completeGC()` | cutover | new; returns the **new `EtchStore`** on the target file; retires this store; writes marker. |
| `EtchStore.cancelGC()` | any | new; rolls back target contents into the original file (reverse `migrate` + root copy-back), then deletes the target. |
| `Etch.copyEntry(Hash, Etch)` | sweep | later optimisation; raw entry copy preserving flags/memorySize. |
| `convex etch gc` / `convex etch migrate` | CLI | new subcommands over the same primitives. |

Required fixes to existing code (bugs once GC is actually used):

- `storeRef` must write via `getWriteEtch()`, not `etch` directly.
- `readStoreRef`/`refForHash` must check target first, then old.
- `storeRef` existing-entry early-return must not trust old-file hits during a cycle.
- `close()` must close the target if present.
- Convert `storeRef` recursion to an explicit stack (pre-existing risk, becomes acute
  when the sweep re-persists an entire State).
- Reduce `RefSoft.withStore` to package-private (internal plumbing/tests only); javadoc
  `Ref.toSoft` as store-internal, directing applications to `Cells.persist`.

## Costs

| Operation | Normal | During GC |
|---|---|---|
| Cache-hit read | 1x | 1x (unchanged) |
| Store read, migrated/new data | 1x | 1x (target hit) |
| Store read, un-migrated data | 1x | 2x (target miss + old hit) |
| Write, novel data | 1x | 1x + one failed target lookup |
| Write touching un-migrated tree | 1x | one-off copy of that subtree (amortised into the total O(live data) migration budget) |
| Disk usage | 1 file | old + live subset (transiently; bounded by old + collected sizes) |
| Extra heap | — | none required (INV-1 pruning replaces a visited-set) |

## Risks and edge cases

- **User cuts over early** (`completeGC(force)`, or closes old file out-of-band): reachable
  data may be missing from the target → `MissingDataException` at runtime. Documented
  contract; `verifyGC()` exists precisely so users can check first.
- **Refs held across cutover**: any ref bound to the retired store fails with
  `StoreException` on uncached reads once that store is closed — even if its value was
  migrated (the binding is dead, not the data). Remedy: re-persist the value into the
  new store while it is still resolvable (recursive, ensures children), or re-fetch by
  hash if it was transferred. Documented contract (per requirements).
- **Naive `withStore` rebinding**: rebinding a ref to the new store without persisting
  its tree produces a ref whose flags claim data the store does not hold — a latent
  `MissingDataException` (or worse, a false `PERSISTED` claim propagated onwards).
  Mitigated by making `withStore` package-private; `toSoft` remains public for store
  implementations but is documented as store-internal.
- **Crash mid-cycle**: original file only ever appended to by roll-back; startup
  recovery migrates the `~` file's contents back rather than discarding them. Writes in
  the unflushed tail of the target at crash time are lost (the same window as any
  unflushed write).
- **Crash during cancel**: cancel's migrate is idempotent; startup recovery simply
  re-runs it. Safe.
- **Crash between marker write and old-file disposal**: adoption-at-startup completes
  the swap idempotently.
- **Long cycles on a busy peer**: the write path keeps migrating hot data, so the sweep
  converges; disk transiently holds both files — operators need free space ≥ expected
  collected size.
- **`updateInPlace` flag upgrades**: during a cycle these route to the target copy (the
  write path copies-then-upgrades); the old file may retain stale (lower) flags, which is
  fine because it is discarded.
- **Empty root**: stores used with `Hash.EMPTY_HASH`/nil root collect to an empty
  store (plus header). Correct, if surprising — the CLI should warn when the root is
  empty.

## Generalisation to other lattice stores

The scheme assumes only: content-addressed immutable entries, a single root, monotonic
status flags, and a `PERSISTED`-style "tree is fully stored" status level. Any lattice
store with those properties (e.g. alternative `AStore` backends, Covia lattice nodes,
`convex.ts` client stores) can adopt the identical four-phase structure:

1. redirect writes to the target; reads check the target first and fall back to the old
   store;
2. persistence requests never trust old-store-only entries (INV-1);
3. sweep = re-persist root, post-order, pruning on target-resident fully-persisted
   entries;
4. explicit user-driven cutover; old-store refs invalid after close.

The transfer/migrate primitives are likewise store-agnostic: anything implementing the
`AStore` contract can be a source or destination.

INV-1 is the portable core: it removes the need for a visited-set or mark bitmap, which
is what makes online copying collection cheap enough for a live peer.

## Testing plan

Test-first: the phases below are ordered so that each lands *before* the code it
protects. Phase 0 runs entirely against existing code.

### Existing coverage (verified July 2026)

- `convex.etch.EtchTest` — raw `Etch` write/read round-trips, radix chain edge cases
  (clash, fill, chain collapse), random-write index enumeration via `visitIndex`,
  `FullValidator`, large-store writes, and cross-store copy of *in-memory* values
  (`testCopyAcrossStores`, using `Refs.checkConsistentStores`).
- `convex.store.ParamTestStores` — parameterised store-contract tests over
  `MemoryStore` + `EtchStore`: `storeTopRef` status levels, nested descendants
  retrievable after `Cells.persist`, large-set persist.
- `convex.store.EtchStoreTest` / `MemoryStoreTest` / `StoresTest`,
  `convex.core.data.RefTest`, `EtchStressTest` — store behaviour, ref semantics, stress.

### Phase 0 — pin the contracts GC relies on (before any GC code)

These extend existing suites (`ParamTestStores` for store-level contracts, `EtchTest` /
`EtchStoreTest` for Etch-specific behaviour). All should pass against current code —
pure characterisation, protecting the "required fixes" refactors:

1. **Flag/status/memorySize preservation** — write at `STORED`, re-persist at
   `PERSISTED`: `updateInPlace` merges monotonically, flags never downgrade, memory size
   set exactly once; read-back returns identical flags. Foundation for "copy preserves
   flags".
2. **`PERSISTED` subtree contract (INV-1's foundation)** — after `Cells.persist` of a
   deep, branchy structure, *every* non-embedded descendant (walked via
   `Cells.visitBranchRefs`) is individually retrievable at ≥ `PERSISTED`. Existing tests
   check one nested child; INV-1 needs the universal claim pinned.
3. **Lazy cross-store persist — the transfer core scenario.** `testCopyAcrossStores`
   copies values still held in memory. The sweep operates on *lazily-loaded* refs:
   persist a large tree in store A, obtain a hash-only `RefSoft` from A
   (`RefSoft.createForHash`), persist it into store B, assert B alone serves the full
   tree with correct flags. The single most load-bearing missing test.
4. **Root data round-trip** — `setRootData`/`getRootRef` including the `nil` /
   `EMPTY_HASH` special cases in `AStore.getRootRef`.
5. **Reopen durability** — write entries + root, `flush()`, `close()`, reopen the same
   file: entries, flags and root intact. Existing tests only ever use fresh temp files;
   the file lifecycle work (adoption, roll-back) needs reopen semantics pinned first.
6. **Closed-store contract** — the three-state read contract: uncached reads on a
   closed `EtchStore` throw `StoreException`, cache hits still serve, absence on a live
   store gives null/`MissingDataException`. (Originally `readStoreRef` mapped
   `ClosedChannelException` to a null ref, making a closed store indistinguishable from
   an empty one — fixed July 2026.) This is the cutover failure mode; pinned explicitly.
7. **Concurrent writers** — parallel `storeRef` from multiple threads into one store
   (executor + futures, no sleeps per repo conventions); all values readable,
   `FullValidator` clean. Pins the thread-safety the background sweep leans on.
8. **Deep-structure persist** — characterise the current recursion depth limit of
   `storeRef` with a deliberately deep structure; becomes the regression test for the
   iterative-descent fix, then scales up.

### Phase 1 — shared test infrastructure

- `StoreAssertions` (test utility): `assertTreePresent(dest, rootHash)` — walk a tree in
  `dest` only, failing on any missing entry or downgraded flag; and
  `assertContainsAll(source, dest)` — `visitIndex` over source, assert every entry
  present in dest with flags ≥. These are the oracle for transfer/migrate/cancel/verify
  tests. `assertTreePresent` is essentially `StoreTransfer.verify` — build them
  together, testing the verifier against hand-built stores with known holes.
- Tree generators: random cell trees with controlled depth, branching and
  embedded/non-embedded mix (extending `convex.test.Samples` and existing generators).

### Phase 2 — new primitives (written alongside their implementation)

- `StoreTransfer.transfer`/`migrate`/`verify` against `MemoryStore`↔`EtchStore` pairs in
  both directions, before any GC lifecycle exists.
- Property: generate random cell trees, persist, add garbage, transfer to a fresh store,
  assert (a) destination contains exactly the reachable set, (b) root data equals
  original, (c) `verify` empty, (d) all flags preserved.
- Migration: migrate into a non-empty destination with overlapping data; assert flag
  merge (never downgraded), no duplicate entries, source fully readable from destination.
- Locality: after an offline transfer, assert each subtree occupies a contiguous byte
  range (checkable via entry positions during a `visitIndex` walk).
- Missing children: lenient mode skips subtree with warning; strict mode fails.

### Phase 3 — GC lifecycle (with the `EtchStore` changes)

- State machine: `startGC`/`completeGC`/`cancelGC` transitions; double-start rejected;
  read fall-through; write redirection; early-return correctness against old-file hits;
  `close()` during cycle.
- Cancel rollback: write novelty and update the root during a cycle, `cancelGC()`,
  assert every value and the latest root are present in the original store, the target
  file is gone, and refs issued during the cycle still resolve.
- Cutover: `completeGC()` returns a store on the target file; retired store still reads;
  retired store writes throw; refs bound to the retired store fail after close;
  carry-across via `Cells.persist(value, newStore)` yields a working, correctly-flagged
  ref whose whole tree reads back from the new store alone.
- Concurrency: sweep racing live writes and root updates (futures and completion
  signals, no sleeps); assert INV-1 post-conditions.
- Crash simulation: kill between each phase boundary (marker present/absent, torn target
  tail), reopen, assert adoption/roll-back recovery — post-`startGC` writes survive.

### Phase 4 — CLI and scale

- CLI: round-trip a peer store fixture, check size reduction and `etch validate` passes
  on the output.
- Scale: sweep over a deep/large generated State (stack-safety of the iterative
  descent); soak via `EtchStressTest` patterns.

## Resolved design decisions

- **No copy-on-read.** Copies to the new store are always explicit (sweep, live writes,
  explicit keeps). Reads are side-effect free. See the retention contract.
- **No store-level multi-root support.** Peers either persist extra values explicitly
  during the cycle or fold them into the main root before cutover; retention is exactly
  what they explicitly keep plus anything persisted after GC starts.
- **The GC result is a new store.** Refs reference their store, so cutover must yield a
  new `EtchStore`; the old store is retired and closed by the user, at which point refs
  bound to it become invalid. In-cycle refs stay bound to the old store, keeping the
  target internal until cutover.
- **Cancel rolls back, never discards.** Writes during a cycle exist only in the target,
  so `cancelGC()` (and crash recovery) migrate the target's contents back into the
  original store — the reverse of the migration primitive — before deleting the target.
- **`completeGC()` hard-requires `isGCComplete()`**, with an explicit `force` variant as
  the escape hatch, since the failure mode is silent data loss.
- **Recorded status is only what is immediately provable for the given store** (July
  2026). The write boundary caps incoming ref status at the level the operation
  achieved; stores never return or cache foreign-bound refs. Regression suite:
  `convex.etch.EtchStatusIntegrityTest` (red until the write-boundary fix lands, with
  green positive controls against over-capping). This is a precondition for INV-1
  pruning and lands before any GC implementation.

## Open questions

None currently.
