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

| Operation | Level | Description |
|-----------|-------|-------------|
| `get()` | `ACursor` | Returns current value |
| `set(V)` | `ACursor` | Sets value atomically |
| `assoc(key, value)` | `ACursor` | Write at a single key (lattice-aware on `ALatticeCursor`) |
| `assocIn(value, keys...)` | `ACursor` | Write at a nested path (lattice-aware on `ALatticeCursor`) |
| `compareAndSet(expected, new)` | `ACursor` | CAS operation |
| `updateAndGet(fn)` | `ACursor` | Apply function, return new value |
| `path(keys...)` | `ACursor` | Navigate to sub-path (lattice-aware on `ALatticeCursor`) |
| `fork()` | `ALatticeCursor` | Create independent working copy |
| `sync()` | `ALatticeCursor` | Push local changes to parent |
| `merge(V)` | `ALatticeCursor` | Merge external value into cursor |
| `getLattice()` | `ALatticeCursor` | Get lattice for this cursor (may be null) |
| `getContext()` | `ALatticeCursor` | Get merge context |
| `withContext(ctx)` | `ALatticeCursor` | Return cursor with new context |

### `assoc` / `assocIn` — Lattice-Aware Writes

`assoc(key, value)` and `assocIn(value, keys...)` are the cursor write primitives for nested data structures. They parallel `RT.assoc` / `RT.assocIn` but operate in a cursor/lattice context:

- **On `ACursor`** (no lattice): Throws if any intermediate is null — callers must pre-initialise the structure. This prevents the silent `null → AHashMap` promotion that `RT.assocIn` performs.
- **On `ALatticeCursor`** (with lattice): Uses `LatticeOps.assocIn` with the cursor's lattice, which calls `lattice.zero()` for null intermediates to create correctly-typed containers (e.g. `Index` instead of `AHashMap`).

Both delegate to `LatticeOps.assocIn` internally — a lattice-aware two-pass algorithm that replaces `RT.assocIn` for all cursor writes.

## Unified Navigation: `path()`

`path()` is the single navigation primitive, parallel to `ALattice.path()`:

- `ALattice.path(key)` resolves the sub-lattice at a key
- `ALatticeCursor.path(key)` navigates to a cursor at that key, using the sub-lattice if one exists

When `path(key)` is called on a lattice cursor:

1. If at a `SignedLattice` boundary, insert a `SignedCursor` (`assocIn` cannot write through `SignedData`)
2. Otherwise resolve `lattice.path(key)` for a sub-lattice and create a `DescendedCursor` (which may have null lattice)

### With sub-lattice (lattice-aware navigation)

The descended cursor has full lattice semantics: `merge()` uses the sub-lattice, `fork()`/`sync()` use lattice merge for conflict resolution.

### Without sub-lattice (null lattice)

The descended cursor supports all operations, with simpler semantics:

| Operation | With lattice | Without lattice (null) |
|-----------|-------------|------------------------|
| `get()` | `RT.getIn(parent, key)` | Same |
| `set(v)` | `LatticeOps.assocIn(parent, key, v)` | Same |
| `fork()` | Local copy, sync uses lattice merge | Local copy, sync overwrites |
| `sync()` | Lattice merge with parent (`merge(parentVal, localVal)`) | Write-back (overwrite at path) |
| `merge(v)` | `sublattice.merge(current, v)`, write to parent | `parent.merge(LatticeOps.assocIn(parent.get(), key, v))` |

**merge() with null lattice** bubbles up: the cursor constructs a parent-level value via `LatticeOps.assocIn` and calls `merge()` on the parent cursor. This propagates up the chain until it reaches a cursor with a lattice, which performs the actual lattice merge. This means a merge at a deep path with no local lattice still benefits from ancestor lattice semantics.

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

The collapsed cursor uses a single `RT.getIn`/`LatticeOps.assocIn` call for the full multi-key path instead of nested cursors at each level. The leaf's sub-lattice is used for `merge()`/`fork()`/`sync()`.

The chain breaks only at `SignedLattice` boundaries, where `assocIn` cannot write through `SignedData` and a `SignedCursor` must be inserted.

### Lattice continuity

Lattice hierarchies are continuous trees. Each lattice level explicitly declares its children via `lattice.path(key)` — if this returns null, no child lattice semantics exist at or below that key. Once the sub-lattice becomes null during traversal, it stays null for all remaining keys. There is no mechanism for lattice semantics to "resume" after a gap, because there is no lattice object to call `path()` on.

This means navigating beyond the lattice hierarchy (e.g. into leaf data structures) produces a `DescendedCursor` with null lattice, giving write-back sync and bubble-up merge — the correct semantics for plain data that has no merge function of its own. If lattice semantics are needed at a deeper level, the lattice hierarchy must be extended to be continuous through that path.

### Auto-initialisation via `valueLattice.zero()`

When a `PathCursor` (or `DescendedCursor` which delegates to `PathCursor`) is created with a lattice, it computes a `valueLattice` by walking `baseLattice.path(keys...)` to the endpoint. This enables automatic initialisation of leaf values:

- **`assocIn` (path intermediates)**: `LatticeOps.assocIn` uses `lattice.zero()` for null intermediates at each depth, creating correctly-typed empty containers.
- **Update lambdas (leaf values)**: `updateAndGet(fn)` and similar methods substitute `valueLattice.zero()` for null, so the lambda receives an empty container instead of null. This eliminates null guards in application code (e.g. `Feed.post()` receives an empty `Index` instead of checking for null).

Note: `get()` still returns null for non-existent paths — the zero-substitution only applies within update lambdas.

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

### Lattice-aware writes via assoc

```java
// On a lattice cursor, assoc auto-initialises from lattice.zero()
ALatticeCursor<Index<Keyword, ACell>> cursor = root.path(ownerKey, Keywords.FEED);
cursor.updateAndGet(feed -> feed.assoc(postKey, postData));
// feed is auto-initialised to Index.EMPTY if it was null
```

## Design Decisions

### Unified `path()` instead of `path()` + `descend()`

A `DescendedCursor` with null lattice is functionally identical to a `PathCursor`: both read via `RT.getIn`, write via `LatticeOps.assocIn`, propagate writes to parent. The only difference is whether a lattice is attached for merge/fork/sync. Having two separate navigation methods (`path()` for data, `descend()` for lattice) is an artificial distinction — the lattice hierarchy determines what capabilities exist at each level, not the choice of method.

`cursor.path(key)` parallels `lattice.path(key)`: one resolves the sub-lattice, the other navigates to a cursor at that key using whatever sub-lattice exists.

### `assoc`/`assocIn` instead of `set` with path

Cursor writes use `assoc(key, value)` and `assocIn(value, keys...)` rather than `set(value, path...)`. This mirrors `RT.assoc`/`RT.assocIn` naming and avoids overload ambiguity with `set(V)`. The critical difference from `RT.assocIn`: cursors **never** silently promote `null` to `AHashMap`. Without a lattice, null intermediates throw. With a lattice, `lattice.zero()` provides the correctly-typed container.

### `sync()` vs CAS-based `merge()`

`AForkableCursor.merge(detached)` uses CAS and can fail if the parent changed. `ALatticeCursor.sync()` uses lattice merge and always succeeds — like filesystem sync, it pushes local changes to the parent. With null lattice, sync falls back to simple write-back (overwrite). After `sync()`, the fork's value equals the merged result, allowing continued use and incremental syncs.

### `SignedCursor` as a separate cursor type

`assocIn` cannot write through `SignedData` (immutable, requires re-signing), so a specialised cursor is needed at this boundary. `SignedCursor` handles sign/verify transparently — code above and below the signing boundary is unaware of signing. Forking from a `SignedCursor` naturally gives unsigned local storage; the existing `ForkedLatticeCursor` works unchanged because `sync()` calls `parent.updateAndGet()`, and the parent (`SignedCursor`) handles signing.

### Multi-key collapsing

Consecutive `path()` steps are collapsed into a single `DescendedCursor` to avoid unnecessary allocations and intermediate merges. The chain breaks only at `SignedLattice` boundaries, where a `SignedCursor` must be inserted because `assocIn` cannot write through `SignedData`. The collapsed cursor holds the full multi-key path and the leaf's sub-lattice.

Lattices define merge semantics only — they have no knowledge of cursors. The cursor's `path()` walks `lattice.path(key)` at each step and handles the `SignedLattice` boundary as a special case (`instanceof` check).

### `LatticeOps` as internal engine

`LatticeOps.assocIn` is the shared implementation for lattice-aware path writes. It is used by:
- `ACursor.assoc`/`assocIn` (with null lattice — throws on null intermediates)
- `ALatticeCursor.assoc`/`assocIn` (with lattice — auto-initialises via `zero()`)
- `PathCursor` internal writes (with the parent cursor's lattice)
- `DescendedCursor.merge()` bubble-up (with the parent cursor's lattice)

The public API is `assoc`/`assocIn` on the cursor; `LatticeOps` is an implementation detail.

## Thread Safety

All operations are lock-free via `AtomicReference`. Immutable values ensure safe concurrent reads. Concurrent forks can sync independently — lattice merge associativity guarantees consistent results regardless of sync order.
