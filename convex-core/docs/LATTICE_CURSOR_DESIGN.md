# Lattice Cursor Design

## Overview

This document describes the design for **Lattice Cursors** - cursors that are aware of lattice merge semantics and support fork/merge patterns for transactional updates to immutable lattice data structures.

## Motivation

Applications working with the Data Lattice need to:

1. **Fork** a working copy of lattice state for local modifications
2. Make multiple updates to the working copy
3. **Sync** changes back to the parent, using proper lattice merge semantics

This pattern supports:
- Transactional updates (batch changes, then commit)
- Nested transactions (sub-transactions within transactions)
- Concurrent modifications (multiple forks sync independently)
- Automatic conflict resolution via lattice merge

### Current Limitations

The existing `AForkableCursor` with `detach()`/`merge()` has limitations:
- Uses CAS-based merge (fails if parent changed) rather than lattice merge
- Doesn't know the lattice merge function at its level
- No support for navigating through lattice hierarchy
- Can't fork at arbitrary sub-lattice levels

## Integration with Existing Cursors

### Renaming: `ABranchedCursor` → `AForkableCursor`

The existing class `ABranchedCursor` should be renamed to `AForkableCursor` - it represents a cursor that *can be forked* (supports detach/fork operations). "Fork" is clearer and more widely understood terminology from version control and concurrent systems.

### `AForkableCursor.merge(detached)`

The existing `sync()` method on `AForkableCursor` should be renamed to `merge()`:

```java
// Called on PARENT, takes detached child as argument
public boolean merge(AForkableCursor<V> detached) {
    V newValue = detached.get();
    V detachedValue = detached.getInitialValue();
    boolean updated = compareAndSet(detachedValue, newValue);
    return updated;
}
```

This performs a **CAS-based merge**: succeeds only if parent hasn't changed since detach.

### `ALatticeCursor.sync()`

The new method on `ALatticeCursor`:

```java
// Called on CHILD, syncs changes back to parent using lattice semantics
public V sync() {
    // ... uses lattice.merge() to combine changes
}
```

This performs a **lattice sync**: always succeeds, like filesystem sync - pushes local changes to the parent.

### `ALatticeCursor.merge(other)`

Additionally, `ALatticeCursor` can provide:

```java
// Merge another value into this cursor using lattice semantics
public V merge(V other) {
    return localValue.updateAndGet(current ->
        lattice.merge(context, current, other)
    );
}
```

This merges an external value (not from a fork) into the cursor.

### Naming Rationale

| Method | Class | Called On | Argument | Semantics | Can Fail? |
|--------|-------|-----------|----------|-----------|-----------|
| `merge(detached)` | `AForkableCursor` | Parent | Child cursor | CAS-based | Yes |
| `sync()` | `ALatticeCursor` | Child | None | Lattice merge to parent | No |
| `merge(value)` | `ALatticeCursor` | Any | External value | Lattice merge | No |

The `sync()` name for lattice cursors follows the filesystem analogy - "sync my local changes back to the master".

### Inheritance

`ALatticeCursor` extends `AForkableCursor`:
- Inherits standard cursor operations (`get`, `set`, `compareAndSet`, etc.)
- Inherits `detach()` for creating branches
- Overrides/adds lattice-aware operations (`sync()`, `merge(value)`, `descend()`)
- `sync()` always succeeds (uses lattice merge, not CAS)

## Design

### Core Concept: `ALatticeCursor<V>`

A `LatticeCursor` is a cursor that:
- Knows its **lattice** (defining merge semantics)
- Can **fork** to create independent working copies
- Can **sync** changes back to parent using lattice merge
- Can **merge** external values using lattice merge
- Can **descend** through the lattice hierarchy to sub-lattices

### Structure

```
ALatticeCursor<V>
├── parent: ALatticeCursor<V> | null     # Parent cursor (null = root)
├── lattice: ALattice<V>                 # Merge semantics for this level
├── localValue: AtomicReference<V>       # Current local value
├── forkPoint: V                         # Value when forked
├── context: LatticeContext              # Merge context (timestamp, signing key)
```

### Operations

#### Standard Cursor Operations

LatticeCursor extends the standard cursor interface:

| Operation | Description |
|-----------|-------------|
| `get()` | Returns current local value |
| `set(V)` | Sets local value atomically |
| `getAndSet(V)` | Sets value, returns previous |
| `compareAndSet(expected, new)` | CAS operation |
| `getAndUpdate(fn)` | Apply function, return old |
| `updateAndGet(fn)` | Apply function, return new |
| `path(keys...)` | Navigate into value (returns PathCursor) |

#### Lattice-Specific Operations

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `fork()` | `() → ALatticeCursor<V>` | Create independent working copy |
| `sync()` | `() → V` | Sync changes back to parent (lattice merge) |
| `merge(V)` | `(V) → V` | Merge external value into cursor (lattice merge) |
| `descend(keys...)` | `(ACell...) → ALatticeCursor<T>` | Navigate to sub-lattice |
| `getLattice()` | `() → ALattice<V>` | Get lattice for this cursor |
| `getContext()` | `() → LatticeContext` | Get merge context |
| `withContext(ctx)` | `(LatticeContext) → ALatticeCursor<V>` | Return cursor with new context |

### Operation Semantics

#### `fork() → ALatticeCursor<V>`

Creates an independent cursor forked from the current value.

```
function fork():
    currentValue = this.localValue.get()
    return new ForkedLatticeCursor(
        parent = this,
        lattice = this.lattice,
        localValue = new AtomicReference(currentValue),
        forkPoint = currentValue,
        context = this.context
    )
```

Properties:
- Forked cursor is completely independent
- Modifications to fork don't affect parent until `sync()`
- Multiple forks can exist simultaneously
- Forks can be nested (fork from a fork)

#### `sync() → V`

Syncs local changes back into parent using lattice semantics (like filesystem sync).

```
function sync():
    if parent == null:
        return localValue.get()  # Root cursor, nothing to sync

    localVal = localValue.get()

    # Atomically sync into parent using lattice merge
    synced = parent.localValue.updateAndGet(parentValue ->
        if parentValue == forkPoint:
            # Fast path: parent unchanged since fork
            return localVal
        else:
            # Parent changed: perform lattice merge
            return lattice.merge(context, parentValue, localVal)
    )

    # Update state for subsequent syncs
    forkPoint = synced
    localValue.set(synced)

    return synced
```

Properties:
- Always succeeds (lattice merge, not CAS)
- Updates both parent and local state
- Subsequent syncs will sync only new changes
- Thread-safe via atomic operations

**Fast path optimisation**: When `parentValue == forkPoint`, the parent hasn't changed since we forked, so we can simply adopt our value without computing a merge.

#### `merge(V other) → V`

Merges an external value into this cursor using lattice semantics.

```
function merge(other):
    return localValue.updateAndGet(current ->
        lattice.merge(context, current, other)
    )
```

Properties:
- Always succeeds (lattice merge)
- Used for merging values received from network, other cursors, etc.
- Does not involve parent cursor

#### `descend(keys...) → ALatticeCursor<T>`

Navigates through the lattice hierarchy to a sub-lattice.

```
function descend(keys...):
    if keys is empty:
        return this

    key = keys[0]
    remainingKeys = keys[1..]

    subLattice = lattice.path(key)
    if subLattice == null:
        error("No sub-lattice at key: " + key)

    descendedCursor = new DescendedLatticeCursor(
        parent = this,
        pathKey = key,
        lattice = subLattice,
        context = context
    )

    return descendedCursor.descend(remainingKeys...)
```

Properties:
- Empty keys returns `this` (no-op)
- Fails if no sub-lattice exists at the path
- Each descended cursor can independently fork/sync
- Changes propagate up through the hierarchy on merge

#### `path(keys...) → PathCursor<T>`

Standard data navigation (no lattice awareness).

```
function path(keys...):
    return PathCursor.create(this, keys)
```

Use `path()` for navigating within a value where no sub-lattice exists.
Use `descend()` for navigating through the lattice hierarchy.

### Comparison: `path()` vs `descend()`

| Aspect | `path(keys...)` | `descend(keys...)` |
|--------|-----------------|---------------------|
| Returns | `PathCursor<T>` | `ALatticeCursor<T>` |
| Lattice aware | No | Yes |
| Can fork/sync | No (uses parent) | Yes |
| Fails if no sub-lattice | No | Yes |
| Use case | Navigate data | Navigate lattice hierarchy |

### Class Hierarchy

```
ACursor<V>
│
└── AForkableCursor<V>                       # Supports fork/detach operations
    │
    ├── Root<V>                              # CAS-based merge
    ├── PathCursor<V>                        # CAS-based merge
    │
    └── ALatticeCursor<V>                    # Lattice-aware (extends AForkableCursor)
        │
        ├── RootLatticeCursor<V>             # Root of lattice tree
        │   ├── localValue: AtomicReference<V>
        │   ├── lattice: ALattice<V>
        │   └── context: LatticeContext
        │
        ├── ForkedLatticeCursor<V>           # Forked from parent
        │   ├── parent: ALatticeCursor<V>
        │   ├── localValue: AtomicReference<V>
        │   ├── forkPoint: V
        │   ├── lattice: ALattice<V>
        │   └── context: LatticeContext
        │
        └── DescendedLatticeCursor<V>        # Descended into sub-lattice
            ├── parent: ALatticeCursor<?>
            ├── pathKey: ACell
            ├── lattice: ALattice<V>
            └── context: LatticeContext
```

Note: `ALatticeCursor` extends `AForkableCursor`, inheriting standard cursor operations and adding lattice-aware `sync()` and `merge(V)` methods.

### Thread Safety

All operations are thread-safe:

- `AtomicReference.updateAndGet()` for atomic operations in `sync()` and `merge(V)`
- `AtomicReference.get()`/`set()` for local value access
- No locks required (lock-free design)
- Immutable values ensure safe concurrent reads

Concurrent forks can sync independently:
```
fork1.sync()  // Syncs using lattice merge
fork2.sync()  // Syncs with fork1's result using lattice merge

// Equivalent to: lattice.merge(lattice.merge(original, fork1.value), fork2.value)
// Which by associativity equals any merge order
```

### Context Propagation

`LatticeContext` flows through the cursor hierarchy:

```java
RootLatticeCursor<V> root = LatticeCursors.createRoot(lattice, initialValue);

// Set context with timestamp and signing key
ALatticeCursor<V> withCtx = root.withContext(
    LatticeContext.create(timestamp, keyPair)
);

// Context inherited by descendants and forks
ALatticeCursor<T> child = withCtx.descend(Keywords.FS, owner);  // Inherits context
ALatticeCursor<T> fork = child.fork();                          // Inherits context
```

Context is used by lattices like `SignedLattice` that need:
- Timestamp for conflict resolution
- Signing key for creating signatures on merged values

## Usage Examples

### Example 1: Simple Fork/Sync

```java
// Create root lattice cursor
RootLatticeCursor<ASet<ACell>> root = LatticeCursors.createRoot(
    SetLattice.INSTANCE,
    Sets.empty()
);

// Fork for modifications
ALatticeCursor<ASet<ACell>> tx = root.fork();

// Make changes
tx.updateAndGet(set -> set.include(item1));
tx.updateAndGet(set -> set.include(item2));

// Sync back to root
tx.sync();

// Root now contains both items
```

### Example 2: Descending Through Lattice Hierarchy

```java
// Root with standard lattice structure
RootLatticeCursor<ACell> root = LatticeCursors.createRoot(
    Lattice.ROOT,
    initialState
);

// Descend to DLFS drive (navigates through lattice hierarchy)
ALatticeCursor<AVector<ACell>> drive = root
    .descend(Keywords.FS)      // → OwnerLattice
    .descend(ownerAddress)     // → SignedLattice
    .descend("main");          // → DLFSLattice

// Or equivalently:
ALatticeCursor<AVector<ACell>> drive = root.descend(Keywords.FS, ownerAddress, "main");

// Fork at DLFS level
ALatticeCursor<AVector<ACell>> tx = drive.fork();

// Modify using path (non-lattice navigation into DLFS node)
tx.updateAndGet(driveState ->
    DLFSNode.updateNode(driveState, filePath, newFileNode, timestamp)
);

// Sync - uses DLFSLattice merge, propagates up to root
tx.sync();
```

### Example 3: Nested Transactions

```java
ALatticeCursor<ACell> root = ...;

// Outer transaction
ALatticeCursor<ACell> outer = root.fork();

// Make some changes
outer.updateAndGet(state -> modify1(state));

// Inner transaction (nested fork)
ALatticeCursor<ACell> inner = outer.fork();
inner.updateAndGet(state -> modify2(state));

// Sync inner to outer
inner.sync();

// More changes to outer
outer.updateAndGet(state -> modify3(state));

// Sync outer to root (includes inner's changes)
outer.sync();
```

### Example 4: Concurrent Modifications

```java
ALatticeCursor<ASet<ACell>> root = ...;

// Two concurrent forks
ALatticeCursor<ASet<ACell>> fork1 = root.fork();
ALatticeCursor<ASet<ACell>> fork2 = root.fork();

// Independent modifications (possibly in different threads)
fork1.updateAndGet(set -> set.include(itemA));
fork2.updateAndGet(set -> set.include(itemB));

// Both sync - lattice merge ensures both items included
fork1.sync();  // Root now has itemA
fork2.sync();  // Root now has itemA AND itemB (SetLattice union)
```

### Example 5: Comparison with Existing CAS-based Merge

```java
// OLD: CAS-based merge (can fail)
Root<ACell> root = Root.create(initialValue);
AForkableCursor<ACell> detached = root.detach();
detached.set(newValue);
boolean success = root.merge(detached);  // May return false!
if (!success) {
    // Must handle conflict manually
}

// NEW: Lattice-aware sync (always succeeds)
RootLatticeCursor<ACell> root = LatticeCursors.createRoot(lattice, initialValue);
ALatticeCursor<ACell> fork = root.fork();
fork.set(newValue);
fork.sync();  // Always succeeds, uses lattice merge for conflicts
```

### Example 6: Integration with NodeServer

```java
public class NodeServer<V extends ACell> {
    private final RootLatticeCursor<V> cursor;
    private final ALattice<V> lattice;

    // External API for local modifications
    public ALatticeCursor<V> fork() {
        return cursor.fork();
    }

    // Called when LATTICE_VALUE received from network
    void processLatticeValue(V received, LatticeContext ctx) {
        // Atomically merge received value using lattice semantics
        cursor.mergeExternal(ctx, received);

        // Trigger propagation to peers
        propagator.triggerBroadcast();
    }

    // Use cursor.merge(V) for external values
    void mergeExternal(LatticeContext ctx, V received) {
        cursor.withContext(ctx).merge(received);
    }
}
```

## Design Decisions

### Why `sync()` for lattice cursors?

1. **Filesystem analogy**: "sync" is familiar - like syncing local changes back to a master copy
2. **Direction clarity**: `sync()` is called on the child to push changes to parent
3. **Distinct from CAS**: `AForkableCursor.merge(detached)` uses CAS (can fail); `ALatticeCursor.sync()` uses lattice merge (always succeeds)
4. **Complementary `merge(V)`**: `sync()` pushes to parent; `merge(V)` pulls external values into cursor

### Why `descend()` instead of lattice-aware `path()`?

1. **Explicitness**: Merge semantics are important - users should know when they're at a lattice boundary
2. **Type safety**: `descend()` always returns `ALatticeCursor`, `path()` always returns `PathCursor`
3. **Fail-fast**: `descend()` fails if no sub-lattice exists, preventing subtle bugs
4. **Clarity**: Two methods with clear purposes vs one method with variable behavior

### Why no child tracking?

1. **Simplicity**: Parent doesn't need to know about forks
2. **Memory**: No risk of memory leaks from forgotten forks
3. **Concurrency**: No synchronization needed for child list
4. **Independence**: Forks are truly independent - caller manages lifecycle

### Why `forkPoint` tracking?

1. **Fast path**: Skip merge computation when parent unchanged
2. **Correctness**: Detect when merge is actually needed
3. **Efficiency**: Common case (no concurrent modification) is fast

### Why sync updates local state?

After `sync()`, the local cursor's value equals the synced result. This allows:
1. Continued use of the fork after sync
2. Subsequent syncs sync only new changes
3. Consistent view between parent and synced child

**Important caveat**: This means the fork's value may change after `sync()` if the parent had concurrent modifications:

```java
// Initial: parent has value P
ALatticeCursor<V> fork = parent.fork();
fork.set(A);                    // Fork has A
// ... meanwhile, another fork syncs B to parent ...
fork.sync();                    // Fork now has merge(P+B, A), NOT A!
```

If you need to preserve your original changes as a snapshot, capture the value before syncing:
```java
V myChanges = fork.get();       // Capture snapshot
fork.sync();                    // Sync (may change fork's value)
// myChanges still holds original value A
```

This behavior is intentional - it's optimal for the common case of transactional updates where you want the fork to reflect the latest merged state for continued work.

## Error Handling

| Operation | Error Condition | Behavior |
|-----------|-----------------|----------|
| `descend(key)` | No sub-lattice at key | Throw exception |
| `sync()` | On root cursor | Return current value (no-op) |
| `merge(V)` | Never | Always succeeds |
| `fork()` | Never | Always succeeds |

## Performance Considerations

1. **Fork is O(1)**: Just copies reference to current value
2. **Sync/Merge is O(merge)**: Dominated by lattice merge cost
3. **Fast path**: No-sync case detected by reference equality
4. **No locks**: Lock-free via atomic operations
5. **Structural sharing**: Immutable values share structure

## Migration Path

✓ `ABranchedCursor` renamed to `AForkableCursor`. All cursor classes implemented:
- `RootLatticeCursor`, `ForkedLatticeCursor`, `DescendedLatticeCursor`
- `fork()`, `sync()`, `merge(V)`, `descend()` all operational
- CAS-based `AForkableCursor.merge(detached)` coexists with lattice-aware `ALatticeCursor.sync()`

## Future Considerations

### Potential Extensions

1. **Abort/rollback**: Discard fork without merging
2. **Conflict detection**: Callback when merge differs from either input
3. **Merge policies**: Options for merge behavior (merge, replace, fail-on-conflict)
4. **Persistence hooks**: Trigger store persistence on merge
5. **Observable cursors**: Listeners for value changes

### Integration Points

- **NodeServer**: Use `fork()` for local transactions, `merge(V)` for incoming values
- **LatticePropagator**: Trigger broadcasts on cursor changes
- **DLFS**: Fork drives for batch file operations
- **Applications**: Transactional state management

## Summary

| Feature | Mechanism |
|---------|-----------|
| Fork working copies | `fork()` creates independent cursor |
| Sync changes to parent | `sync()` uses lattice merge (always succeeds) |
| Merge external values | `merge(V)` uses lattice merge (always succeeds) |
| Navigate lattice hierarchy | `descend(keys...)` to sub-lattices |
| Navigate data | `path(keys...)` for non-lattice paths |
| Thread safety | Lock-free atomic operations |
| Nested transactions | Fork from forks |
| Context propagation | `withContext()` and inheritance |
| Fast path | Skip sync when parent unchanged |
| Coexistence | Extends `AForkableCursor` |
