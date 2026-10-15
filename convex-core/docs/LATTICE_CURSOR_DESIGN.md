# Lattice Cursor Design

Cursors are mutable references to immutable CVM values with atomic updates. They give
applications a fork-and-sync model over lattice data: fork a working copy, make several
updates, then sync back through a lattice merge, with signing and timestamping applied
by the cursor the owning component wraps around the boundary. The cursor model is
specified in [CAD035](https://docs.convex.world/docs/cad/cursors); this document
explains the class hierarchy and the design choices in `convex.lattice.cursor`.

Component-owned working state and batched signing are described in
[LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md). They use existing cursor
operations. Independent component boundaries specialise only their private root's
`sync()` to run component-owned CAS reconciliation; ordinary atomic updates are
unchanged. Hosts still use `Root.onSync` for persistence and propagation.
CAD035 still describes the earlier lattice-aware hierarchy and needs updating
before this branch's design is normative.

## Key points

- Cursors know nothing about lattices. A cursor is a location with atomic update,
  navigation and an inherited write-policy context. Everything a lattice would supply
  arrives as a parameter from the component that owns the level: the `Shape` of a
  child position, the `Merge` of a fork, the `Signing` or `Stamping` rule of a
  boundary. Every `ALattice` implements `Shape` and `Merge`, so a component passes
  its declared lattice wherever a cursor asks for one.
- `path(keys...)` is plain navigation. `child(key, shape)` is navigation that declares
  the empty value of the child position, so a write can create it when absent and an
  update function receives `shape.zero()` instead of null. Components navigate one
  declared level at a time; `ALatticeApplication.resolve(path)` walks the root
  lattice to do this for a whole region path.
- Writes never promote `null` to a map silently. The base shape creates an absent
  base value; a missing intermediate below it is created only for a structural shape
  (JSON-like data) and otherwise throws.
- `fork(merge)` takes the merge function at fork time, so `sync()` converges with the
  parent however it has moved. `fork()` without one is write-back and overwrites a
  parent that changed in between; use it only where no concurrent writer exists.
  `AForkableCursor.merge(detached)` is a CAS that can fail.
- Navigation never inserts write interceptors. The component that owns an owner-keyed
  or stamped level wraps its cursor with `SignedCursor.forOwner` or
  `StampedCursor.create`, passing the lattice it declares, so authoring uses exactly
  the rule the host verifies with. A plain write through a signed envelope is refused.
- Every write is prepared exactly once per logical write, not once per CAS retry, so
  signing that reaches a wallet or remote signer is not repeated under contention.
- Hosts attach at the root: `Root.onSync(publish, merge)` installs the publication
  callback and the merge used when a write lands during publication. No cursor
  carries a store or a lattice.
- Ordinary root updates use `AtomicReference`; sync callbacks are serialised per
  root, while transactional roots additionally coordinate writes. Immutable values
  make concurrent reads safe. Sync follows the supplied merge's conflict rules,
  including directional ties and signing constraints.

## Class hierarchy

```
ACursor<V>                                 get / atomic update / path / child / context
└── AForkableCursor<V>                     fork(merge) / sync / detach / CAS merge
    ├── Root<V>                            atomic value holder; host seam onSync(publish, merge)
    │   └── TransactionalRootCursor<V>     root with a write lock for exclusive transactions
    ├── RootCursorView<V>                  live view of a root with its own context override
    ├── PathCursor<V>                      navigated into a sub-path, optionally with a shape
    ├── ForkedCursor<V>                    independent working copy with its merge function
    ├── TransactionCursor<V>               private draft confined to one transaction callback
    └── AUpdateCursor<V,S>                 prepare-on-write funnel
        ├── StampedCursor<V>               same type: stamp timestamp on write
        └── SignedCursor<V>                SignedData<V> to V: sign on write
AView<T>                                   read-only views (TimeCache, Transformer)
Transactions                               optimistic and locked subtree transactions
```

The interfaces a cursor accepts live in `convex.lattice` beside `LatticeContext`:
`Shape<V>` (`zero()`, `isStructural()`), `Merge<V>` (`merge(context, own, other)`),
`Signing<V>` (sign for an owner), `Ownership<V>` (the signing rule for one owner's slot)
and `Stamping<V>` (inject a timestamp). The cursor package imports nothing else from the
lattice packages.

## Operations

| Operation | Level | Description |
|---|---|---|
| `get()`, `set(v)`, `compareAndSet`, `updateAndGet(fn)` | `ACursor` | Atomic value access |
| `assoc(key, v)`, `assocIn(v, keys...)` | `ACursor` | Nested writes, creating the base from its shape |
| `path(keys...)` | `ACursor` | Navigate with canonical keys, no shape |
| `child(key, shape)` | `ACursor` | Navigate one level, declaring the child's empty value |
| `shape()`, `getContext()`, `setContext(ctx)` | `ACursor` | Position shape and write policy |
| `fork(merge)`, `fork()`, `sync()` | `AForkableCursor` | Working copy; converge into the parent |
| `merge(merge, v)` | `AForkableCursor` | Merge an external value into this position |
| `onSync(publish, merge)`, `sync(callback)`, `view()` | `Root` | Host publication seam and live views |
| `Transactions.transact(cursor, fn)`, `transactLocked` | `Transactions` | Atomic subtree transactions over a draft cursor |

### Writes

`LatticeOps.assocIn` is the shared engine for nested writes and decides how to create a
missing container at each depth:

- **At the base**: `shape.zero()` supplies the correctly typed empty container, for
  example an `Index` rather than a hash map; a shape with no zero throws.
- **Below the base, structural shape** (`isStructural()`, such as `JSONLattice`): the
  container is chosen from the key shape (integer to vector, string to map, keyword or
  blob to index), which lets deep writes work below a whole-value leaf without a
  declared type hierarchy.
- **Below the base, otherwise**: throw. Only the component owning that level knows its
  type, so it navigates with `child` and the level creates itself.
- **Signed envelope** anywhere on the path: throw. A signature covers its value, so
  nothing is written through it except by the owning component's `SignedCursor`.

Writes are copy-on-change: a level whose child did not change is returned by reference,
so a no-op write allocates nothing.

### Navigation

A `PathCursor` reads through `getIn` and writes through `assocIn` on its base. Multiple
keys collapse into one cursor, so a navigation such as `[:fs owner]` costs one `getIn`
per read rather than a chain of cursors. Context and the transactional write lock are
inherited from the base live.

| Operation | With a shape (`child`) | Without one (`path`) |
|---|---|---|
| Update function argument | `shape.zero()` in place of null | null |
| Creating this level when absent | From the base's shape | From the base's shape |
| Creating levels below | Structural shapes only | Structural shapes only |

External keys (JSON strings, hex, decimal) are canonicalised by the lattice, not the
cursor: `cursor.path(lattice.resolvePath(keys))`.

## Forks and sync

A `ForkedCursor` holds a local `Root` plus the parent, the fork point and the merge
function it was created with. `sync()` is `parent.updateAndGet`: if the parent still
holds the fork point the fork's value is installed directly; otherwise the two values
are merged with the fork's local edits in the `own` position, so a local edit wins a
timestamp tie against a concurrent parent advance. The fork then adopts the synced
value by CAS and, if a concurrent write landed on the fork meanwhile, merges it with the
fork's current state as `own`. Forks stay usable after sync for incremental work.

`sync()` on a live cursor propagates to the root, where the host's publication callback
runs under the root's sync lock. A fork's sync never reaches that callback: the host
calls `root.sync()` explicitly when the merged state should be published.

## Write interception

Some levels must transform a value on the way in: sign it, or stamp it with the
context timestamp. `AUpdateCursor<V, S>` wraps a base cursor holding the stored type
`S`, presents the view type `V`, and implements every atomic operation and `sync()`
through two hooks:

- `prepareWrite(V) -> S` authors the stored cell; it may consult the `LatticeContext`
  and may throw to enforce a precondition.
- `view(S) -> V` reads a stored cell back; identity unless the boundary changes type.

Two rules hold for every subclass. A write that leaves the view unchanged keeps the
current cell, so an unchanged value keeps its signature or timestamp. And preparation
happens once per logical write: the retry loop re-invokes the update function under
contention, but a fixed value is prepared exactly once and a computed value is
re-prepared only if a retry genuinely produces a different one.

| Cursor | Stored type | `prepareWrite` | `view` | `merge(merge, v)` |
|---|---|---|---|---|
| `StampedCursor<V>` | `V` | inject timestamp | identity | install the merge result unstamped |
| `SignedCursor<V>` | `SignedData<V>` | sign as the bound owner | `getValue` | merge the unsigned values, re-sign |

`SignedCursor` is the enforcement point for authorship. A write asks its `Signing` rule
for a signer authorised for the bound owner and throws `IllegalStateException` if there
is none. The rule comes from the owner lattice's `Ownership.signingFor(owner)`, which
is the same rule the lattice applies to data arriving on merge, so local state is never
written in a form a peer would reject.

Boundaries are placed by components, not discovered by navigation. The component that
owns an owner-keyed level wraps each owner's slot with
`SignedCursor.forOwner(slot, owners, owner)`; the component that owns a stamped level
wraps it with `StampedCursor.create(base, stamping)`. Lattices declare these rules and
construct no cursors. Forking below a boundary stores the view type locally and the
boundary re-applies `prepareWrite` on `sync()`.

## Examples

```java
// Fork, modify, sync
SetLattice<ACell> lattice = SetLattice.create();
Root<ASet<ACell>> root = Cursors.root(lattice);           // at the lattice's zero value
AForkableCursor<ASet<ACell>> fork = root.fork(lattice);
fork.updateAndGet(s -> s.include(item1));
fork.updateAndGet(s -> s.include(item2));
fork.sync();                                              // root now contains both items

// Concurrent forks converge via the merge function
AForkableCursor<ASet<ACell>> f1 = root.fork(lattice), f2 = root.fork(lattice);
f1.updateAndGet(s -> s.include(a));
f2.updateAndGet(s -> s.include(b));
f1.sync(); f2.sync();                                     // root has a and b (set union)

// A component crosses an owner boundary, then batches with deferred signing
AForkableCursor<AHashMap<AString,AVector<ACell>>> drives = SignedCursor.forOwner(
    app.resolve(Keywords.FS).path(ownerKey),              // region by the root lattice, owner slot
    DLFSRegion.LATTICE, ownerKey);                        // the owner rule the host verifies with
AForkableCursor<AVector<ACell>> drive = drives.child(driveName, DLFSLattice.INSTANCE);
AForkableCursor<AVector<ACell>> work = drive.fork(DLFSLattice.INSTANCE);
work.updateAndGet(state -> addFile(state, "a.txt"));     // local, unsigned
work.updateAndGet(state -> addFile(state, "b.txt"));
work.sync();                                              // signs once, merges into parent
```

## Design decisions

| Question | Decision |
|---|---|
| Does a cursor carry its lattice? | No. A cursor knows its position's shape, its context and, for a fork, its merge function, all supplied by the owning component. Lattices define domain structure; components encode it; cursors move and update values. |
| `path()` or `child(key, shape)`? | Both. `path` is plain navigation; `child` declares the one thing a write through an absent child needs, its empty value. Applications walk the root lattice with `resolve(path)` to declare a whole region path. |
| `assoc`/`assocIn` or `set(value, path...)`? | `assoc` forms, mirroring `RT.assoc`, avoiding overload ambiguity with `set(V)` and never promoting null silently. |
| `sync()` or CAS merge? | Both exist; `sync()` on a fork created with a merge function always succeeds, like a filesystem sync. Root publication absorbs a write that races it only through the merge the host installs with `onSync`; with none installed that race fails rather than overwriting. |
| Where do signing and stamping live? | In `AUpdateCursor` subclasses that the owning component wraps around its cursor. Lattices declare the rule and construct no cursors; navigation inserts nothing. |
| Collapse multi-key paths? | Yes, to avoid intermediate cursors and merges; break only where a component declares a level. |
| What does the base class know? | Get, atomic update, navigation and write policy at one location, nothing else. Persistence, durability and publication are host concerns attached at the root through `onSync`; no cursor carries a store. |
| Root-owned services such as the transactional write lock? | One protected `exclusiveLock()` hook on `ACursor`, resolved through existing parent links and supplied only by `TransactionalRootCursor`. Views and plain cursors never see it, and there is no capability bundle on `ACursor`. |
| Where do transactions live? | `Transactions` runs a callback against a private draft cursor and commits by CAS, with locked fallback on a transactional root. Typed domain transactions build on it in `TransactionalComponent`. |

## Where the code lives

- `convex.lattice.cursor` — every class in the hierarchy above, plus `Cursors`
  factories and `TimeCache`/`Transformer` views.
- `convex.lattice.Shape`, `Merge`, `Signing`, `Ownership`, `Stamping` — the interfaces
  cursors accept, implemented by `ALattice` and the generic lattices.
- `convex.lattice.LatticeOps` — the nested-write engine.
- `convex.lattice.LatticeContext` — signing, timestamps and owner verification.
- `convex.lattice.ALatticeComponent`, `ALatticeApplication`, `RootComponent` — the
  components that declare structure to cursors and host the root.

## Related

- [CAD035 Lattice Cursors](https://docs.convex.world/docs/cad/cursors) — cursor model and guarantees.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice) — merge properties and merge context.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth) — signer authorisation at the merge boundary.
- [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) — building components over cursors.
