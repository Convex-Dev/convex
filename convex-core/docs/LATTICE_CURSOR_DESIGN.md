# Lattice Cursor Design

Lattice cursors are cursors that understand lattice merge semantics. They give
applications a fork-and-sync model over immutable lattice data: fork a working copy,
make several updates, then sync back through lattice merge, with signing and
timestamping handled transparently by the cursor chain. The cursor model is specified
in [CAD035](https://docs.convex.world/docs/cad/cursors); this document explains the
class hierarchy and the design choices in `convex.lattice.cursor`.

## Key points

- `path(key)` is the single navigation primitive and mirrors `ALattice.path(key)`: one
  resolves the sub-lattice at a key, the other navigates to a cursor there using
  whatever sub-lattice exists.
- Lattice hierarchies are continuous. Once `lattice.path(key)` returns null, everything
  below has no lattice: sync becomes write-back and merge bubbles up to the nearest
  ancestor with a lattice.
- `sync()` always succeeds because it merges; `AForkableCursor.merge(detached)` is a
  CAS that can fail. Forks stay usable after sync for incremental work.
- Writes never promote `null` to a map silently. With a lattice, missing intermediates
  are created from `lattice.zero()`; without one, they throw.
- Update lambdas receive `zero()` instead of null for uninitialised paths; `get()`
  still returns null.
- A lattice declares its own write boundaries. The cursor inserts a `SignedCursor` or
  `StampedCursor` where the lattice says so, with no `instanceof` on lattice types.
- Every write is prepared exactly once per logical write, not once per CAS retry, so
  signing that reaches a wallet or remote signer is not repeated under contention.
- All operations are lock-free over `AtomicReference`; immutable values make concurrent
  reads safe and merge associativity makes sync order irrelevant.

## Class hierarchy

```
ACursor<V>
└── AForkableCursor<V>                    fork / detach
    ├── Root<V>                           atomic value holder (CAS)
    ├── PathCursor<V>                     navigation for non-lattice cursors
    └── ALatticeCursor<V>                 lattice-aware cursor
        ├── RootLatticeCursor<V>          root of a lattice tree
        ├── ForkedLatticeCursor<V>        independent working copy
        ├── DescendedCursor<V>            navigated into a sub-path
        └── AUpdateCursor<V,S>            prepare-on-write funnel
            ├── StampedCursor<V>          same type: stamp timestamp on write
            └── SignedCursor<V>           SignedData<V> to V: sign on write
```

`ALatticeCursor.path()` returns a `DescendedCursor` carrying the sub-lattice from
`ALattice.path(key)`, or one with a null lattice when no sub-lattice exists.

## Operations

| Operation | Level | Description |
|---|---|---|
| `get()`, `set(v)`, `compareAndSet`, `updateAndGet(fn)` | `ACursor` | Atomic value access |
| `assoc(key, v)`, `assocIn(v, keys...)` | `ACursor` | Lattice-aware nested writes |
| `path(keys...)` | `ACursor` | Navigate with canonical keys |
| `resolve(keys...)` | `ALatticeCursor` | Canonicalise external keys via `resolveKey`, then `path` |
| `fork()`, `sync()` | `ALatticeCursor` | Working copy; merge back to the parent |
| `merge(v)` | `ALatticeCursor` | Merge an external value into this position |
| `getLattice()`, `getContext()`, `withContext(ctx)` | `ALatticeCursor` | Lattice and merge context |

### Writes

`LatticeOps.assocIn` is the shared engine for nested writes and decides how to create a
missing intermediate at each depth:

- **No lattice**: throw. Callers pre-initialise the structure.
- **Typed lattice**: `lattice.zero()` supplies the correctly typed empty container, for
  example an `Index` rather than a hash map.
- **Structural lattice** (`isStructural()`, such as `JSONLattice`): the container is
  chosen from the key shape (integer to vector, string to map, keyword or blob to
  index), which lets deep writes work below a whole-value leaf without a declared type
  hierarchy.

Writes are copy-on-change: a level whose child did not change is returned by reference,
so a no-op write allocates nothing.

### Navigation

With a sub-lattice, a descended cursor has full lattice semantics: `merge` uses the
sub-lattice and `fork`/`sync` resolve conflicts by lattice merge. With a null lattice
it still supports every operation, with simpler semantics:

| Operation | With lattice | Without lattice |
|---|---|---|
| `sync()` | Lattice merge with the parent value | Write-back at the path |
| `merge(v)` | Sub-lattice merge, written to the parent | Build the parent-level value and call `merge` on the parent cursor |

Consecutive `path` steps collapse into one `DescendedCursor` holding a multi-key path,
so a navigation such as `[:fs owner :value "drive"]` costs one `getIn` per read rather
than a chain of cursors. The chain breaks only where the lattice declares a write
boundary.

`resolve` is the entry point for external keys (JSON strings, hex, decimal). It
canonicalises each key against the cursor's own lattice and composes:
`c.resolve(a).resolve(b)` reaches `c.resolve(a, b)`, an identity resolver reduces it to
`path`, and an unresolvable key throws.

### Auto-initialisation

A descended cursor computes its value lattice by walking `path` to the endpoint. Update
lambdas then receive `valueLattice.zero()` in place of null, which removes null guards
from application code (a feed post lambda receives an empty `Index`). `get()` is
unaffected and returns null for absent paths.

## Write interception

Some lattices must transform a value on the way in: sign it, or stamp it with the
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

| Cursor | Stored type | `prepareWrite` | `view` | `merge` |
|---|---|---|---|---|
| `StampedCursor<V>` | `V` | inject timestamp | identity | select an existing value, no re-stamp |
| `SignedCursor<V>` | `SignedData<V>` | sign as the bound owner | `getValue` | synthesise, re-sign |

`SignedCursor` is the enforcement point for authorship. A write asks
`LatticeContext.signAs` for a signer authorised for the owner the path selected and
throws `IllegalStateException` if there is none. That is the same rule `OwnerLattice`
applies to data arriving on merge, so local state is never written in a form a peer
would reject.

The cursor learns where boundaries are from three `ALattice` hooks: `isWriteBoundary(key)`
(a cheap gate checked at every step), `createPathCursor(base, key, ctx)` (builds the
update cursor when the gate fires) and `consumesPathKey(key)` (whether the key is a
virtual segment such as `:value`, or transparent as for stamping). `SignedLattice` and
`StampingLattice` implement them; forking below a boundary stores the view type
locally and the boundary re-applies `prepareWrite` on `sync()`.

## Examples

```java
// Fork, modify, sync
RootLatticeCursor<ASet<ACell>> root = Cursors.createLattice(SetLattice.create(), Sets.empty());
ALatticeCursor<ASet<ACell>> fork = root.fork();
fork.updateAndGet(s -> s.include(item1));
fork.updateAndGet(s -> s.include(item2));
fork.sync();                                  // root now contains both items

// Concurrent forks merge via the lattice
ALatticeCursor<ASet<ACell>> f1 = root.fork(), f2 = root.fork();
f1.updateAndGet(s -> s.include(a));
f2.updateAndGet(s -> s.include(b));
f1.sync(); f2.sync();                         // root has a and b (set union)

// Navigate through a signing boundary, then batch with deferred signing
ALatticeCursor<AVector<ACell>> drive = root.path(
    Keywords.FS,        // KeyedLattice -> OwnerLattice
    ownerKey,           // OwnerLattice -> SignedLattice
    Keywords.VALUE,     // SignedLattice -> SignedCursor
    driveName);         // MapLattice -> DLFSLattice
ALatticeCursor<AVector<ACell>> work = drive.fork();
work.updateAndGet(state -> addFile(state, "a.txt"));   // local, unsigned
work.updateAndGet(state -> addFile(state, "b.txt"));
work.sync();                                           // signs once, merges into parent
```

## Design decisions

| Question | Decision |
|---|---|
| One `path()` or `path()` plus `descend()`? | One. A descended cursor with a null lattice is a path cursor; the lattice hierarchy, not the method, determines capabilities. |
| `assoc`/`assocIn` or `set(value, path...)`? | `assoc` forms, mirroring `RT.assoc`, avoiding overload ambiguity with `set(V)` and never promoting null silently. |
| `sync()` or CAS merge? | Both exist; `sync()` is the lattice operation that always succeeds, like a filesystem sync. |
| Where do signing and stamping live? | In `AUpdateCursor` subclasses inserted at lattice-declared boundaries, so cursor code stays lattice-agnostic. |
| Collapse multi-key paths? | Yes, to avoid intermediate cursors and merges; break only at write boundaries. |

## Where the code lives

- `convex.lattice.cursor` — every class in the hierarchy above, plus `Cursors`
  factories and `TimeCache`/`Transformer` views.
- `convex.lattice.LatticeOps` — the nested-write engine.
- `convex.lattice.ALattice` — `path`, `zero`, `isStructural`, `resolveKey` and the
  write-boundary hooks.
- `convex.lattice.LatticeContext` — signing, timestamps and owner verification.
- `convex.lattice.SignedLattice`, `StampingLattice`, `JSONLattice`, `OwnerLattice` —
  the lattices that exercise boundaries and structural navigation.

## Related

- [CAD035 Lattice Cursors](https://docs.convex.world/docs/cad/cursors) — cursor model and guarantees.
- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice) — merge properties and merge context.
- [CAD038 Lattice Authorisation](https://docs.convex.world/docs/cad/lattice_auth) — signer authorisation at the merge boundary.
- [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) — building components over cursors.
