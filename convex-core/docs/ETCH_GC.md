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
| `Etch.visitIndex` + `EtchUtils.EtchCellVisitor` | `convex.etch` | full index scan — enumerates *every* entry (migrate-all mode, statistics, verification). **Racy under concurrent writes**: only for write-quiescent stores |
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
| `StoreTransfer.transfer(dest, ref, status)` | **implemented (July 2026)** — copy one reachable tree into `dest` at the given status; the source is implicit in the ref's store binding. Post-order descent via the standard persistence machinery, pruning on dest-resident entries at sufficient status (INV-1). Status policy is the caller's (`PERSISTED` for data movement, `ANNOUNCED` when migrating a peer's own store). Currently rides `storeRef` recursion — the iterative-descent fix remains a shared TODO. |
| `EtchUtils.migrate(source, dest)` | **implemented (July 2026)** — ensure *everything* in an Etch `source` is persisted in `dest`: index scan (`visitIndex`) driving a transfer of each entry at its recorded status (capped at `MAX_STATUS`). Source must be **write-quiescent** (`visitIndex` is racy under concurrent writes); destination may be non-empty and live; destination root untouched. Lives in `convex.etch` because it needs index enumeration. |
| `StoreTransfer.verify(store, rootHash)` | **implemented (July 2026)** — iterative, duplicate-safe walk of the tree resolving from the given store only; returns missing hashes (empty = fully present). |
| `Etch.copyEntry(hash, targetEtch)` | (optimisation, later) raw entry copy — `key|flags|memorySize|length|encoding` verbatim, no decode/re-encode |
| `EtchStore.startGC/completeGC/cancelGC` | lifecycle: fresh target + read-fallback/write-redirect + cutover. GC = `startGC()` → `transfer(this, rootRef)` over the store's own old/target split → `completeGC()` (returns the new store). `cancelGC()` = reverse `migrate(target, original)` + root copy-back |

**Strictness follows the destination store.** An Etch destination is strict: missing
source data propagates as `MissingDataException`, source read failures as
`StoreException`. `MemoryStore` is lenient by design (remote-acquisition semantics: it
stores what it can and caps achieved status). Lenient behaviour over Etch (CLI tools
skipping damaged subtrees) is the caller's per-subtree concern, not the primitive's.

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
                        │                                          old store = legacy view
                        │
                        └── cancelGC() ──▶ roll back target→old ──▶ IDLE (original store,
                                                                     nothing lost)
```

### Refs and store identity

`RefSoft` binds to the store it belongs to. Two consequences shape the lifecycle:

- **The result of GC is a new store.** `completeGC()` returns a fresh `EtchStore`
  wrapping the target file; the old `EtchStore` object becomes a functional legacy view, not mutated into the
  new one. Callers swap their handle (peer server store field, `Stores` context) to the
  returned store.
- **During the cycle, all refs handed out by the collecting store stay bound to the old
  store object** — including refs for values physically written to the target file. The
  target is internal until cutover. This is what makes `cancelGC()` non-disruptive (no
  app-visible store ever disappears) and concentrates the entire swap at `completeGC()`.

After cutover the old store remains open and readable — refs bound to it keep
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
retention contract) or after cutover but before the old store is closed.

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

### Phase 1: `startGC()` — implemented (July 2026, phase 3a)

Synchronised on the store:

1. Refuse if a cycle is in progress, or if a stale target file exists (a stale target
   may hold data from an interrupted cycle — adopting or deleting it blindly risks data
   loss; recovery is a separate step, phase 3e).
2. Create the target Etch file (fresh header, empty index), bind it to this store, and
   copy the current root hash into it — all **before** publishing the volatile `target`
   field, so readers never observe a half-initialised target.
3. From publication:
   - **Writes** go to the target (`getWriteEtch()`).
   - **Reads** check the target first, then fall back to the old file.

**Cycle-boundary linearisation**: each top-level persist snapshots its write target
once and threads it through the recursive descent. A persist in flight when `startGC()`
runs therefore completes entirely against the old file and linearises as *before* the
cycle (retained only per the retention contract). The alternative — reading the target
per-write — could split one tree across the two files, leaving a parent in the target
claiming `PERSISTED` whose children exist only in the old file: a silent INV-1
violation that the sweep would then trust.

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

Concretely (implemented, phase 3a), during a cycle `storeRef`'s existence check reads
the **target file only**: neither the old file nor the shared cache can prove target
residency (cache entries don't record which file backs them). A target hit with
sufficient status early-returns; anything else forces the normal recursive write path,
which copies the cell (and, at `PERSISTED`+ levels, its children) into the target.
Outside a cycle the cached check is unchanged — the hot path costs one volatile load
and an untaken branch, and cache hits are entirely untouched.

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
    Ref<ACell> root = getRootRef();       // resolves via target-then-old read path
    StoreTransfer.transfer(this, root);   // descends, copies old→target, prunes on INV-1
}
```

(Destination is this same `EtchStore` because the store's own read/write paths implement
the old/target split during a cycle; the ref's binding supplies the source, so the same
`StoreTransfer.transfer(dest, ref, status)` form covers distinct stores.)

Implementation notes (implemented July 2026, phase 3c — `EtchStore.transferGC()`):

- **Iteration, not recursion.** The sweep is an explicit-stack post-order walk
  (stack-safe for arbitrarily deep trees). Each per-entry persist then finds its
  children already target-resident and recurses at most one level, so reusing the
  recursive `storeRef` machinery per entry is safe. (Converting general persistence to
  an iterative descent remains a separate TODO.)
- **Ordering.** Post-order DFS, children immediately before parent — see
  [Copy ordering and locality](#copy-ordering-and-locality).
- **Per-entry status preservation.** Each entry is transferred at the status it holds
  in the *old file* (floor `PERSISTED`, capped at `MAX_STATUS`). A uniform `PERSISTED`
  sweep would demote `ANNOUNCED` entries (novelty re-broadcast storm after cutover); a
  uniform `ANNOUNCED` sweep would forge peer commitments. Per the status invariants,
  each preserved level is earned in the target by the post-order copy itself.
- **INV-1 pruning doubles as dedup.** A subtree already target-resident at sufficient
  status is skipped; shared subtrees need no visited-set because DFS completes one
  occurrence before a sibling occurrence expands.
- **Cycle-end abort.** The sweep checks per iteration that its cycle is still live and
  aborts with `IllegalStateException` if cancelled mid-sweep — completion must never be
  claimed for a dead cycle. A concurrent-sweep guard rejects overlapping sweeps (which
  would also destroy DFS locality).
- **Missing children.** The primitives are strict over Etch (missing source data
  propagates as `MissingDataException`); a GC cycle over a store with pre-existing holes
  fails rather than silently producing a smaller hole-free-looking store. Lenient
  skip-with-warning behaviour is a CLI-level policy for damaged stores, applied
  per-subtree by the tool, not baked into the primitive.
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

### Phase 4: cutover — `completeGC()` — implemented (July 2026, phase 3d)

Synchronised on the store, and the user's explicit call. Returns the **new store**:

1. Check `isGCComplete()`; refuse otherwise — hard requirement, no force override (the
   failure mode of an early cutover is silent data loss).
2. `flush()` the target: everything must be durable before committing to it.
3. Mark completion, then construct the result: a new `EtchStore` wrapping the target
   `Etch` (fresh caches; the constructor repoints the target file's store binding, so
   refs decoded from it — including via the old store's reads — bind to the new store,
   deliberately: they outlive the old store's close).
4. Write the `gc-complete` marker (recording which generational target file completed,
   plus its root hash) and return the new store. A crash before the marker reads as an
   abandoned cycle: recovery rolls the target back and the cutover simply "didn't
   happen" — nothing lost either way.

**The old store is NOT retired — whether and when to stop using it is the caller's
decision.** After cutover it remains a fully functional view: reads fall back across
both files, and writes route to the target — the successor's file — so code still
holding the old handle keeps working correctly through a gradual handover, with nothing
thrown and nothing lost. What the completed state forbids is only what would now be
wrong: closing the successor's file (`close()` skips the target once completed),
cancelling (reverse-migrating the successor's live file), re-completing (a second
successor), or starting a new cycle on the legacy view. The caller swaps handles at its
own pace and closes the old store when nothing depends on refs bound to it (see
[Refs and store identity](#refs-and-store-identity)). Old-file disposal follows the file
lifecycle below.

### Cancelling — `cancelGC()`

Cancel is **not** simply deleting the target: every write since `startGC()` — novelty,
status upgrades, root updates — exists only in the target file. Cancel must roll the
target's contents back into the original store, which is exactly the reverse migration
primitive:

Implemented (July 2026, phase 3b):

1. Under lock: redirect writes back to the old file (`cancelling` flag); reads keep the
   target fallback, and **root reads keep coming from the target** (still authoritative
   until copy-back — `getRootHash` is target-aware, not write-target-aware).
2. Drain in-flight target writes: because each persist snapshots its write target for
   its whole descent (3a), a brief lock on the target is NOT sufficient — persists
   *register* in a counter while writing to the target and re-check the cancelling flag
   after registering (Dekker-style), so a drained zero count is conclusive. The reverse
   migration's index scan requires a **write-quiescent** source; a missed entry here
   would be silent data loss.
3. `EtchUtils.migrate(target, this)` — everything in the target is persisted back;
   destination writes route to the old file by the cancelling state, and the persistence
   existence check reads the old file only (the target could otherwise satisfy it and
   skip the copy). Idempotent, so a failed cancel may simply be retried.
4. Copy the target's root hash back (its tree is now present by step 3). `setRootData`
   is synchronised on the store, so the copy-back cannot stomp a newer root.
5. Under lock: drop the target, close it, delete the file. Readers that still hold the
   target reference tolerate the closed target (documented exception to the three-state
   read contract: by protocol everything it held is in the old file). Deletion may fail
   while mapped buffers pin the file (Windows) — harmless, see file naming below.

Because all application-visible refs were bound to the collecting store throughout the
cycle, cancellation is invisible to the application beyond transient ≤2x reads while
step 2 runs. Nothing persisted during the cycle is lost.

`close()` must close *both* files if a cycle is in progress (fixing the current leak).
Closing a store mid-cycle without cancelling abandons the cycle; the startup recovery
below reconciles it.

### File lifecycle and naming

Target files are named **generationally**: `<file>~`, `<file>~1`, `<file>~2`, … —
`startGC()` picks the first name not in use. An existing target file is *neither adopted
nor deleted*: it is either stale (interrupted cycle — may hold data recoverable by the
roll-back) or a cancelled target still pinned by mapped buffers (Windows cannot delete a
mapped file). Generational naming means neither case ever blocks a new cycle, and stale
data is preserved for recovery, which scans `<file>~*`.

**Bounded naming (long-running servers)**: targets are named off the store's *logical
base file* (`EtchStore.getBaseFile()`), which is inherited across cutovers and deferred
adoptions — so successive in-process GC cycles produce `f~`, `f~1`, `f~2`, … with small
numbers reused after cleanup, never nested `f~~~…` growing one character per cycle.

**One marker, no chains**: `<base>.gc-complete` always names the CURRENT store file and
is *rewritten* by every `completeGC()`. (An earlier chain design — one marker per hop —
broke under in-process file deletion and name reuse: deleted intermediates left dangling
links, and reused names made the marker graph cyclic.) Superseded files carry a
`<file>.gc-defunct` tombstone written *at cutover time*: it is the discriminator between
"retained content verifiably elsewhere — delete, never roll back" and an abandoned cycle
that must be rolled back. Tombstone-before-marker ordering makes the crash window safe:
a crash between the two writes reads as "cutover didn't happen" and the target rolls
back, losing nothing.

The typical file-name sequence, GC-ing periodically:

```
running on f          cycle 1: target f~    cutover: marker→f~, f defunct
close old view:       f deleted             (now running on f~)
running on f~         cycle 2: target f~1   cutover: marker→f~1, f~ defunct
close old view:       f~ deleted            (now running on f~1)
running on f~1        cycle 3: target f~    (name reused — f~ is free again)
...
restart:              recovery installs the marker-named file as f; steady state = one file f
```

Renaming is awkward on Windows: memory-mapped files cannot be renamed or deleted while
mappings exist, and Java offers no reliable explicit unmap for `MappedByteBuffer` — the
mapping dies only when the buffer is garbage collected. So in-process deletions may be
deferred (tombstones make that safe) and the rename back to `f` happens at startup,
before anything is mapped. Deferral is temporary even without a restart: `startGC()`'s
name scan retries deletion of tombstoned files (whose buffers have typically been GC'd
by the next cycle) and reuses their names, so on Windows the generation numbers stay
small — roughly the number of cycles that run before the JVM collects the previous
store's buffers, not one per cycle — and pinned garbage is reclaimed in-process rather
than only at restart. (A future migration of Etch's region management to the FFM API's
`Arena`-scoped mapping would make unmapping deterministic and remove the deferral
entirely — and also removes the 2GB `MappedByteBuffer` region/margin scheme wholesale:
see [#636](https://github.com/Convex-Dev/convex/issues/636), gated on a Java 25
baseline.)

**Automatic recovery — implemented (July 2026, phase 3e)**: `EtchStore.create(file)`
calls `EtchUtils.recover(file)` before mapping anything. Configured stores use
`EtchStore.create(file, config)` and carry the same compiled configuration through
recovery, direct opens and GC targets; a v3 target gets its own fresh file salt.
Recovery reconciles every GC-related on-disk state, with detailed operator-facing log
messages, and is idempotent (an interruption mid-recovery leaves a state the next run
recognises and resumes).

Recovery metadata is not trusted. When GC residue exists, every participating non-empty
Etch file is header-validated and, for encrypted v3, authenticated with the caller's
configuration before the first marker deletion, rollback, file deletion or adoption.
A wrong key or mismatched file policy therefore changes none of the source, target or
marker files. This is a bounded header-only preflight, not a body scan.

Normal v3 opening also requires every participating file to be cleanly closed. A process
crash leaves v3 files in the `OPEN` state, so ordinary `EtchStore.create` stops before
changing the GC layout; the caller must deliberately select the unsafe maintenance and
repair workflow. V1 and v2 have no clean-close state and retain their historical
best-effort rollback behaviour:

- **Completed cutovers are adopted.** The marker-named current file is installed as
  `<file>` after the superseded original is **deleted**. This deletion is the disk
  reclamation: each cutover was hard-gated on a verifiably complete sweep, so everything
  the superseded file held that was retained lives on in the current file, and
  everything else is garbage by the retention contract. (`close()` on a completed
  legacy-view store likewise deletes its old file in-process.) Operators wanting an
  archive copy the file *before* invoking `completeGC()`.
- **Adoption may be deferred.** If deletion/renaming fails (files pinned by mappings
  from this same process on Windows), recovery opens the marker-named current file
  directly — correct data, never stale — and retries on the next start.
- **Defunct files are deleted, never rolled back.** The `.gc-defunct` tombstone marks
  superseded cutover originals and cancelled targets: rolling them back would
  re-introduce collected garbage.
- **Abandoned cycles are rolled back.** A target with neither marker reference nor
  tombstone holds writes that exist nowhere else: its entries are best-effort migrated
  into the live store file, tolerating a torn tail from the crash (unreadable entries
  are skipped and counted, never abort recovery); the root advances only if its tree
  then verifies fully present; the target is deleted. The migration binds refs to a
  store over the *source* file so parents visited before their children (index order is
  hash order) can resolve their descent.

For v1 and v2, the original file is never modified during a cycle except by roll-back
(which only adds entries and monotonically merges flags), a completed cutover is adopted
idempotently, and an interrupted cycle is rolled back rather than discarded. For v3,
an unclean process crash instead stops automatic reconciliation before any mutation;
the explicitly selected repair workflow determines what can be recovered from the
synced root and validated physical tail.

Automatic deletion is limited to files whose retained content is *verifiably* elsewhere:
superseded originals and intermediates after a hard-gated cutover, and targets whose
contents have been rolled back. That deletion is the point — without it, GC would merely
rename garbage rather than reclaim it.

## Store-to-store migration

`EtchUtils.migrate(source, dest)` ensures everything from the source store is
persisted in an existing destination store. This is the general form; GC is the special
case of a fresh destination plus root-only coverage plus cutover.

Differences from GC:

- **Coverage is the whole source, not just the reachable set.** `Etch.visitIndex` with an
  `EtchCellVisitor` enumerates every entry; each non-embedded entry is transferred with
  its flags. (Entries reachable from the source root get transferred anyway via the root
  pass; the index scan additionally carries over `STORED`-level and currently-unreachable
  data, which is the point of "everything".)
- **Source must be write-quiescent.** The index scan (`Etch.visitIndex`) is inherently
  racy under concurrent writes — index restructuring (chain collapses, slot repointing)
  can cause entries to be missed — so migrate must only run against a source receiving
  no writes. Reads on the source are fine.
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

## CLI — implemented (July 2026, phase 4)

Three subcommands alongside `etch info`, `etch validate` etc. in `convex-cli`, all thin
wrappers over the tested machinery. Offline operation is enforced by Etch's exclusive
file lock.

`convex etch validate -e <store.etch>` performs the strict offline check: it walks the
selected logical index, validates pointer and collision-chain structure, independently
hashes and decodes each bounded CAD3 record, and confirms that the selected root's
branch tree is complete. Aggregate counts are exact while `--max-failures` bounds only
the diagnostic detail retained and printed. These checks are maintenance-only and add
no hashing or decoding work to normal Etch reads.

```
convex etch gc -e <store.etch> [--output <out.etch>]
```
(`convex.cli.etch.EtchGC`) In-place by default: runs the full lifecycle
(`startGC → transferGC → verifyGC → completeGC`), then attempts adoption so the user
ends up with `<store.etch>` collected; if the collected file cannot yet be renamed
(mapped-file pinning), the command says so and the next open installs it automatically.
Reports sizes before/after and percentage reclaimed. With `--output`, collects the root
tree into a fresh file and leaves the source untouched (note: status levels above
`PERSISTED`, e.g. `ANNOUNCED`, are preserved in-place but not via `--output`). Always
verifies before cutover and fails — original untouched — if verification reports
anything missing. Strict on missing source data (a corrupt/truncated source aborts with
a pointer to `etch validate`); lenient skip-with-warning modes were considered and
dropped — silently producing a smaller store from damaged input is the wrong default.

```
convex etch migrate -e <source.etch> --into <dest.etch> [--set-root]
```
(`convex.cli.etch.EtchMigrate`) Runs `EtchUtils.migrate` into the destination (created
if absent, may be non-empty); every source entry arrives at its recorded status; the
destination root is unchanged unless `--set-root`. Reports entries processed and
destination size.

```
convex etch recover -e <store.etch>
```
(`convex.cli.etch.EtchRecover`) Runs GC recovery explicitly (it also runs automatically
on every open) and reports the resulting store state, including whether adoption was
deferred. Detailed recovery actions log via the `convex.etch.recovery` logger.

Live mode for a running peer (trigger `startGC`/`transferGC`/`completeGC` over the peer admin
API) is a natural follow-up but out of scope for the first iteration.

## API summary

| Method | Phase | Notes |
|---|---|---|
| `EtchStore.startGC()` | begin | **implemented (3a)**: guarded, fully-initialised-before-publication target; `etch`/`target` volatile; write redirection + target-first read fallback + target-only persistence check live. |
| `EtchStore.isGCInProgress()` | any | **implemented (3a)**. |
| `StoreTransfer.transfer(dest, ref, status)` | sweep | **implemented**; post-order copy with INV-1 pruning, source implicit in ref binding. |
| `EtchUtils.migrate(source, dest)` | migration | **implemented**; full index scan + per-entry transfer at recorded status into an existing store. |
| `StoreTransfer.verify(store, rootHash)` | pre-cutover | **implemented**; store-only reachability walk, returns missing hashes (empty = complete). |
| `EtchStore.transferGC()` | sweep | **implemented (3c)**: iterative post-order sweep, per-entry status preservation, INV-1 pruning, cycle-end abort. |
| `EtchStore.isGCComplete()` | sweep done | **implemented (3c)**: sweep finished; sticky (INV-1 + cycle write path); reset by start/cancel. |
| `EtchStore.verifyGC()` / `EtchUtils.verify(Etch, Hash)` | pre-cutover | **implemented (3c)**: full walk against the target file ONLY (store reads fall back to the old file, so they cannot verify), no pruning. |
| `EtchStore.completeGC()` | cutover | **implemented (3d)**: returns the **new `EtchStore`** on the target file; old store stays a functional view (caller decides retirement); writes marker. |
| `EtchStore.cancelGC()` | any | **implemented (3b)**: flag flip + registered-writer drain + reverse `migrate` + root copy-back + target retirement; idempotent on retry. |
| `Etch.copyEntry(Hash, Etch)` | sweep | later optimisation; raw entry copy preserving flags/memorySize. |
| `convex etch gc` / `migrate` / `recover` | CLI | **implemented (phase 4)**: thin wrappers over the lifecycle, `EtchUtils.migrate` and `EtchUtils.recover`. |

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
- **Refs held across cutover**: any ref bound to the old store fails with
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

## Runnable example

`convex.core.examples.EtchGCExample` (test source tree, not part of the suite) exercises
a full online GC cycle under concurrent load and verifies the result end-to-end. Run it
with:

```
./mvnw -B -pl convex-core test-compile exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=convex.core.examples.EtchGCExample
```

~20 seconds: 4 writers continuously update a shared lattice root (superseding their
payloads — the garbage), 4 readers verify every store read against an in-memory model
throughout, and the cycle (`startGC` → `transferGC` → `verifyGC` → `completeGC`) runs
mid-stream, including a deliberate window where workers keep using the old handle after
cutover (the legacy-view handover) and a second, quiescent cycle at the end. Everything
unexpected throws noisily; verification covers new-file-only completeness, root ≡ model,
deterministic payload recomputation, sampled pre-cycle garbage absence, and successors
surviving their predecessors' close. Typical trace:

```
[  5025ms] Before GC: old file 6,772,215 bytes | 12,796 payload writes | 6,398 root updates
[  5133ms] startGC(): writes now redirect to target
[  7137ms] transferGC() swept root tree in 0 ms
[ 10140ms] VERIFY completeness sticky under live traffic: OK
[ 10150ms] completeGC(): successor store - workers still using the OLD handle (legacy view)
[ 16154ms] Workers stopped: 41,644 payload writes | 34,975 reads (34,975 verified)
[ 16159ms] VERIFY garbage collected - 12,792/12,792 sampled pre-cycle values absent: OK
[ 16167ms] SPACE: quiescent GC 21,740,770 bytes -> 525,641 bytes (97.58% reclaimed)
[ 16205ms] RESULT: PASS - final state correct and complete
```

Three instructive observations from the run, worth knowing before interpreting GC
behaviour on a live system:

- **The sweep can be near-instant.** Under a high root-update rate, the cycle write path
  lands the entire live set in the target before the sweep even runs — `transferGC()`
  then finds everything INV-1-pruned. The sweep is a completeness *guarantee*, not
  necessarily where the copying work happens.
- **The busy-cycle file is usually LARGER than the original — by design.** Everything
  persisted after `startGC()` is retained (retention contract), which under sustained
  write load dwarfs the collected pre-cycle garbage. The true reclamation figure comes
  from a quiescent cycle (writers idle): the example's second cycle reclaims ~97%.
- **~512 KiB is the floor for any Etch file** (the level-0 index block of 65,536 8-byte
  slots); a "fully collected" store never shrinks below that.

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
4. **Root data round-trip** — `setRootData`/`getRootRef`, including the distinct
   zero-initialised `UNSET_HASH` and explicitly written `NULL_HASH` states. Both
   read as null root data without requiring a stored entry; `getRootHash()`
   preserves which state applies.
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

### Phase 2 — new primitives (implemented July 2026: `StoreTransferTest`)

- `StoreTransfer.transfer`/`verify` and `EtchUtils.migrate` against
  `MemoryStore`↔`EtchStore` pairs in both directions, before any GC lifecycle exists —
  including lazy-source transfer, repeat-transfer no-op (INV-1 pruning observed via
  unchanged Etch data length), status preservation, missing-children strictness (Etch)
  vs leniency (MemoryStore), and mixed-status migration.
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
- Cutover: `completeGC()` returns a store on the target file; the old store stays a
  functional view (reads via both files, writes routed to the successor's file) until
  the caller closes it; closing it must not affect the successor; refs bound to the old
  store fail after its close; carry-across via `Cells.persist(value, newStore)` yields a
  working, correctly-flagged ref whose whole tree reads back from the new store alone.
- Concurrency: sweep racing live writes and root updates (futures and completion
  signals, no sleeps); assert INV-1 post-conditions.
- Crash simulation: kill between each phase boundary (marker present/absent, torn target
  tail), reopen, assert adoption/roll-back recovery — post-`startGC` writes survive.

### Phase 4 — CLI and scale

- CLI (implemented: `EtchCLITest.testEtchGCMigrateRecover`): in-place GC round-trip
  (root intact, garbage absent), `--output` collection, migrate with `--set-root`, and
  explicit recover.
- Scale (future): sweep over a deep/large generated State; soak via `EtchStressTest`
  patterns.

## Resolved design decisions

- **No copy-on-read.** Copies to the new store are always explicit (sweep, live writes,
  explicit keeps). Reads are side-effect free. See the retention contract.
- **No store-level multi-root support.** Peers either persist extra values explicitly
  during the cycle or fold them into the main root before cutover; retention is exactly
  what they explicitly keep plus anything persisted after GC starts.
- **The GC result is a new store.** Refs reference their store, so cutover must yield a
  new `EtchStore`; the old store remains a functional view until closed by the user, at which point refs
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
