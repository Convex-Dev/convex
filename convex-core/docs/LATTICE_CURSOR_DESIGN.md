# Lattice Cursor Design

## Overview

**Lattice Cursors** are cursors aware of lattice merge semantics, supporting fork/sync patterns for transactional updates to immutable lattice data structures.

Applications working with the Data Lattice need to:

1. **Fork** a working copy of lattice state for local modifications
2. Make multiple updates to the working copy
3. **Sync** changes back to the parent, using proper lattice merge semantics

This supports transactional updates, nested transactions, concurrent modifications with automatic conflict resolution, and deferred signing through the cursor hierarchy.

## Class Hierarchy

```
ACursor<V>
│
└── AForkableCursor<V>                       # Supports fork/detach operations
    │
    ├── Root<V>                              # Atomic value holder (CAS-based)
    ├── PathCursor<V>                        # Navigation into value (internal)
    │
    └── ALatticeCursor<V>                    # Lattice-aware cursor
        │
        ├── RootLatticeCursor<V>             # Root of lattice tree
        ├── ForkedLatticeCursor<V>           # Independent working copy
        ├── DescendedCursor<V>              # Navigated into sub-path
        └── SignedCursor<V>                  # Signing enforcement point
```

`PathCursor` remains as the `ACursor.path()` implementation for non-lattice cursors. `ALatticeCursor.path()` overrides to return `ALatticeCursor` — a `DescendedCursor` with the sub-lattice from `ALattice.path(key)`, or `null` if no sub-lattice exists at that key.

## Operations

| Operation | Description |
|-----------|-------------|
| `get()` | Returns current value |
| `set(V)` | Sets value atomically |
| `compareAndSet(expected, new)` | CAS operation |
| `updateAndGet(fn)` | Apply function, return new value |
| `path(keys...)` | Navigate to sub-path (lattice-aware on `ALatticeCursor`) |
| `fork()` | Create independent working copy |
| `sync()` | Push local changes to parent |
| `merge(V)` | Merge external value into cursor |
| `getLattice()` | Get lattice for this cursor (may be null) |
| `getContext()` | Get merge context |
| `withContext(ctx)` | Return cursor with new context |

## Unified Navigation: `path()`

`path()` is the single navigation primitive, parallel to `ALattice.path()`:

- `ALattice.path(key)` resolves the sub-lattice at a key
- `ALatticeCursor.path(key)` navigates to a cursor at that key, using the sub-lattice if one exists

When `path(key)` is called on a lattice cursor:

1. If at a `SignedLattice` boundary, insert a `SignedCursor` (RT.assocIn cannot write through `SignedData`)
2. Otherwise resolve `lattice.path(key)` for a sub-lattice and create a `DescendedCursor` (which may have null lattice)

### With sub-lattice (lattice-aware navigation)

The descended cursor has full lattice semantics: `merge()` uses the sub-lattice, `fork()`/`sync()` use lattice merge for conflict resolution.

### Without sub-lattice (null lattice)

The descended cursor supports all operations, with simpler semantics:

| Operation | With lattice | Without lattice (null) |
|-----------|-------------|------------------------|
| `get()` | `RT.getIn(parent, key)` | Same |
| `set(v)` | `parent.set(assocIn(parent, key, v))` | Same |
| `fork()` | Local copy, sync uses lattice merge | Local copy, sync overwrites |
| `sync()` | Lattice merge with parent (`merge(parentVal, localVal)`) | Write-back (overwrite at path) |
| `merge(v)` | `sublattice.merge(current, v)`, write to parent | `parent.merge(assocIn(parent.get(), key, v))` |

**merge() with null lattice** bubbles up: the cursor constructs a parent-level value via `assocIn` and calls `merge()` on the parent cursor. This propagates up the chain until it reaches a cursor with a lattice, which performs the actual lattice merge. This means a merge at a deep path with no local lattice still benefits from ancestor lattice semantics.

**sync() with null lattice** does a simple write-back — the forked value overwrites the parent at this path. This is correct for leaf-level navigation where there are no meaningful merge semantics. Lattice merge happens at higher levels in the cursor chain where lattices exist.

### Multi-key path collapsing

`path(:fs, ownerKey, :value, "myDrive")` walks keys one at a time, checking `lattice.path(key)` at each step. Consecutive steps that don't require a specialised cursor (e.g. `SignedCursor`) collapse into a single `DescendedCursor` with a multi-key path:

```
path(:fs, ownerKey, :value, "myDrive")

RootLatticeCursor                                 [KeyedLattice]
  → DescendedCursor([:fs, ownerKey], SignedLattice) ← collapsed
    → SignedCursor                                  ← must break here
      → DescendedCursor(["myDrive"], DLFSLattice)
```

The collapsed cursor uses a single `RT.getIn`/`RT.assocIn` call for the full multi-key path instead of nested cursors at each level. The leaf's sub-lattice is used for `merge()`/`fork()`/`sync()`.

The chain breaks only at `SignedLattice` boundaries, where `RT.assocIn` cannot write through `SignedData` and a `SignedCursor` must be inserted.

### Lattice continuity

Lattice hierarchies are continuous trees. Each lattice level explicitly declares its children via `lattice.path(key)` — if this returns null, no child lattice semantics exist at or below that key. Once the sub-lattice becomes null during traversal, it stays null for all remaining keys. There is no mechanism for lattice semantics to "resume" after a gap, because there is no lattice object to call `path()` on.

This means navigating beyond the lattice hierarchy (e.g. into leaf data structures) produces a `DescendedCursor` with null lattice, giving write-back sync and bubble-up merge — the correct semantics for plain data that has no merge function of its own. If lattice semantics are needed at a deeper level, the lattice hierarchy must be extended to be continuous through that path.

## Signing Enforcement

`SignedCursor<V>` sits at the `SignedData<V>` boundary. It is the **enforcement point** for signing: all writes must be signed, and it throws `IllegalStateException` if the `LatticeContext` cannot provide a signing key.

| Operation | Behaviour |
|-----------|-----------|
| `get()` | Extract unsigned `V` from `SignedData<V>`. Always works. |
| `set(v)` | Sign `v`, write `SignedData<V>` to parent. **Throws** if no key. |
| `path(keys)` | Navigate the inner (unsigned) lattice — writes propagate back through `SignedCursor`. |
| `fork()` | Local unsigned storage. Signing deferred until `sync()`. |

`SignedCursor` is created automatically when `path()` crosses a `SignedLattice` boundary.

## Examples

### Fork, modify, sync

```java
RootLatticeCursor<ASet<ACell>> root = Cursors.createLattice(SetLattice.create(), Sets.empty());

ALatticeCursor<ASet<ACell>> fork = root.fork();
fork.updateAndGet(s -> s.include(item1));
fork.updateAndGet(s -> s.include(item2));
fork.sync();
// root now contains both items
```

### Concurrent forks merge via lattice

```java
ALatticeCursor<ASet<ACell>> fork1 = root.fork();
ALatticeCursor<ASet<ACell>> fork2 = root.fork();

fork1.updateAndGet(s -> s.include(itemA));
fork2.updateAndGet(s -> s.include(itemB));

fork1.sync();  // root has itemA
fork2.sync();  // root has itemA AND itemB (set union)
```

### Navigate through signing boundary

```java
// Navigate from root to a DLFS drive, crossing the SignedData boundary
ALatticeCursor<AVector<ACell>> drive = root.path(
    Keywords.FS,       // KeyedLattice → OwnerLattice
    ownerKey,          // OwnerLattice → SignedLattice
    Keywords.VALUE,    // SignedLattice → SignedCursor (enforcement point)
    driveName          // MapLattice → DLFSLattice
);

// Fork for batch operations — deferred signing
ALatticeCursor<AVector<ACell>> fork = drive.fork();
fork.updateAndGet(state -> addFile(state, "a.txt"));   // local, unsigned
fork.updateAndGet(state -> addFile(state, "b.txt"));   // local, unsigned
fork.sync();  // signs once via SignedCursor, merges into parent
```

### Sub-lattice merge via path()

```java
RootLatticeCursor<AHashMap<Keyword, ASet<CVMLong>>> root =
    Cursors.createLattice(mapLattice, Maps.of(Keywords.FOO, Sets.of(CVMLong.ONE)));

// path() resolves SetLattice at :foo — merge uses set union
ALatticeCursor<ASet<CVMLong>> fooCursor = root.path(Keywords.FOO);
fooCursor.merge(Sets.of(CVMLong.create(2)));
// fooCursor.get() == #{1, 2}
```

## Design Decisions

### Unified `path()` instead of `path()` + `descend()`

A `DescendedCursor` with null lattice is functionally identical to a `PathCursor`: both read via `RT.getIn`, write via `RT.assocIn`, propagate writes to parent. The only difference is whether a lattice is attached for merge/fork/sync. Having two separate navigation methods (`path()` for data, `descend()` for lattice) is an artificial distinction — the lattice hierarchy determines what capabilities exist at each level, not the choice of method.

`cursor.path(key)` parallels `lattice.path(key)`: one resolves the sub-lattice, the other navigates to a cursor at that key using whatever sub-lattice exists.

### `sync()` vs CAS-based `merge()`

`AForkableCursor.merge(detached)` uses CAS and can fail if the parent changed. `ALatticeCursor.sync()` uses lattice merge and always succeeds — like filesystem sync, it pushes local changes to the parent. With null lattice, sync falls back to simple write-back (overwrite). After `sync()`, the fork's value equals the merged result, allowing continued use and incremental syncs.

### `SignedCursor` as a separate cursor type

`RT.assocIn` cannot write through `SignedData` (immutable, requires re-signing), so a specialised cursor is needed at this boundary. `SignedCursor` handles sign/verify transparently — code above and below the signing boundary is unaware of signing. Forking from a `SignedCursor` naturally gives unsigned local storage; the existing `ForkedLatticeCursor` works unchanged because `sync()` calls `parent.updateAndGet()`, and the parent (`SignedCursor`) handles signing.

### Multi-key collapsing

Consecutive `path()` steps are collapsed into a single `DescendedCursor` to avoid unnecessary allocations and intermediate merges. The chain breaks only at `SignedLattice` boundaries, where a `SignedCursor` must be inserted because `RT.assocIn` cannot write through `SignedData`. The collapsed cursor holds the full multi-key path and the leaf's sub-lattice.

Lattices define merge semantics only — they have no knowledge of cursors. The cursor's `path()` walks `lattice.path(key)` at each step and handles the `SignedLattice` boundary as a special case (`instanceof` check).

## Thread Safety

All operations are lock-free via `AtomicReference`. Immutable values ensure safe concurrent reads. Concurrent forks can sync independently — lattice merge associativity guarantees consistent results regardless of sync order.
