# DLFS Cursor API Review

**⚠️ IMPORTANT UPDATE**: This review initially had the dependencies backwards. See [DLFS_ARCHITECTURE_CORRECT.md](./DLFS_ARCHITECTURE_CORRECT.md) for the corrected understanding.

## Current Architecture

### DLFSLocal Implementation

`DLFSLocal` wraps a `Root<AVector<ACell>>` cursor (line 34):

```java
public class DLFSLocal extends DLFileSystem {
    Root<AVector<ACell>> rootCursor;

    public DLFSLocal(DLFSProvider dlfsProvider, String uriPath, AVector<ACell> rootNode) {
        super(dlfsProvider, uriPath, DLFSNode.getUTime(rootNode));
        this.rootCursor = Root.create(rootNode);
    }
}
```

### Key Operations

**Read Operations** (lines 46-50, 53-56):
```java
public AVector<ACell> getNode(DLPath path) {
    AVector<ACell> rootNode = rootCursor.get();  // Read from cursor
    AVector<ACell> result = DLFSNode.navigate(rootNode, path);
    return result;
}
```

**Update Operations** (lines 125-127):
```java
public synchronized AVector<ACell> updateNode(DLPath dir, AVector<ACell> newNode) {
    rootCursor.updateAndGet(rootNode ->
        DLFSNode.updateNode(rootNode, dir, newNode, getTimestamp())
    );
    return newNode;
}
```

**Merge Operation** (lines 145-147):
```java
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode ->
        DLFSNode.merge(rootNode, other, getTimestamp())
    );
}
```

### Issues Identified

#### Issue 1: Merge Uses Wrong Timestamp ❌❌❌

**Problem**: `DLFSLocal.merge()` uses `getTimestamp()` (drive timestamp for NEW operations) instead of timestamps from the nodes being merged.

**Impact**:
- **Semantically incorrect**: Merge uses arbitrary "current time" instead of node timestamps
- Can produce incorrect results when drive timestamp < node timestamps
- Violates separation of concerns (drive timestamp is for new files, not merges)

**Location**: DLFSLocal.java:145-147

**Current Code**:
```java
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode ->
        DLFSNode.merge(rootNode, other, getTimestamp())  // ❌ WRONG timestamp
    );
}
```

**Example of the bug**:
```java
// Files created at time 2000
driveA.setTimestamp(CVMLong.create(2000));
Files.createFile(driveA.getPath("file.txt"));

// Later, set drive time to 1000
driveA.setTimestamp(CVMLong.create(1000));

// Merge will use 1000 (wrong!) instead of 2000 (from nodes)
driveA.replicate(driveB);
```

**Correct Fix**:
```java
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode ->
        DLFSLattice.INSTANCE.merge(LatticeContext.EMPTY, rootNode, other)
        // Uses max of node timestamps, NOT drive timestamp
    );
}
```

**Why this is correct**:
- DLFSLattice.merge() gets timestamps from the nodes themselves
- Drive timestamp is only for NEW file operations
- Merge timestamp should reflect the data being merged, not the drive's "current time"

#### Issue 2: No Context-Aware Merge API ❌

**Problem**: No way to pass `LatticeContext` through the DLFileSystem API

**Impact**:
- Cannot control merge timestamp from user code
- Cannot use context-aware merge features we just implemented
- Signature support would require context but there's no API for it

**Current API**:
```java
driveA.replicate(driveB);  // Uses driveA's current timestamp
```

**Desired API**:
```java
CVMLong mergeTime = CVMLong.create(System.currentTimeMillis());
LatticeContext ctx = LatticeContext.create(mergeTime, keyPair);
driveA.replicateWithContext(driveB, ctx);  // Uses context timestamp
```

#### Issue 2: Confusion Between Drive Time and Merge Time ⚠️

**Problem**: The code conflates two different concepts:

1. **Drive Timestamp** (`DLFileSystem.timestamp`): Time for NEW file operations
2. **Merge Timestamp**: Time derived from nodes being merged

**Drive Timestamp Purpose** (CORRECT usage):
```java
driveA.setTimestamp(CVMLong.create(1000));
Files.createFile(driveA.getPath("newfile.txt"));  // Created at time 1000
```

**Merge Timestamp Purpose** (should come from nodes):
```java
// Node A has files at times [1000, 2000]
// Node B has files at times [1500, 2500]
// Merge should use max(2000, 2500) = 2500, NOT drive's current time
```

**Current Bug**: `merge()` uses drive timestamp instead of node timestamps

**Why this matters**:
```java
// Create files at time 2000
driveA.setTimestamp(CVMLong.create(2000));
Files.createFile(driveA.getPath("file1.txt"));

// Much later, set drive time to 500
driveA.setTimestamp(CVMLong.create(500));

// BUG: Merge will use 500 (drive time) instead of 2000 (node time)
driveA.replicate(driveB);  // Produces incorrect timestamps!
```

## API Quality Assessment

### Positive Aspects ✅

1. **Clean Java NIO API**: Standard filesystem operations work as expected
   ```java
   Path file = Files.createFile(drive.getPath("foo.txt"));
   Files.write(file, bytes);
   ```

2. **Cursor-Based Mutability**: Atomic updates with `Root<AVector<ACell>>`
   ```java
   rootCursor.updateAndGet(rootNode -> /* pure function */)
   ```

3. **Immutable Snapshots**: Easy to clone drives
   ```java
   DLFSLocal snapshot = drive.clone();
   ```

4. **Hash-Based Identity**: `getRootHash()` for content addressing

### Areas for Improvement ⚠️

1. **Lattice Integration**: Merge should use `DLFSLattice.merge()` not `DLFSNode.merge()`

2. **Context Support**: No way to pass `LatticeContext` to merge operations

3. **Timestamp Control**: Implicit timestamp management, no explicit control

4. **API Consistency**: High-level API (DLFileSystem) doesn't expose lattice semantics

## Recommended API Enhancements

### Enhancement 1: Use DLFSLattice in merge()

**Change DLFSLocal.merge()** to use the lattice:

```java
@Override
public void merge(AVector<ACell> other) {
    // Use current timestamp from drive
    CVMLong timestamp = getTimestamp();
    LatticeContext context = LatticeContext.create(timestamp, null);

    rootCursor.updateAndGet(rootNode ->
        DLFSLattice.INSTANCE.merge(context, rootNode, other)
    );
}
```

**Benefits**:
- Uses proper lattice abstraction
- Enables context-aware merge with current drive timestamp
- Consistent with lattice architecture

### Enhancement 2: Add Context-Aware Merge API

**Add new method to DLFileSystem**:

```java
/**
 * Merge another DLFS drive into this one using provided context.
 * The context timestamp will be used for conflict resolution.
 *
 * @param other Root node of other DLFS drive
 * @param context Lattice context (timestamp, signing key)
 */
public void mergeWithContext(AVector<ACell> other, LatticeContext context) {
    rootCursor.updateAndGet(rootNode ->
        DLFSLattice.INSTANCE.merge(context, rootNode, other)
    );
}

/**
 * Replicate another drive into this one using provided context.
 *
 * @param other The other drive to replicate from
 * @param context Lattice context for merge
 */
public void replicateWithContext(DLFileSystem other, LatticeContext context) {
    mergeWithContext(other.getNode(other.getRoot()), context);
}
```

**Usage Example**:
```java
DLFileSystem driveA = DLFS.createLocal();
DLFileSystem driveB = DLFS.createLocal();

// ... make changes to both drives ...

// Merge with explicit timestamp control
CVMLong mergeTime = CVMLong.create(System.currentTimeMillis());
LatticeContext ctx = LatticeContext.create(mergeTime, null);

driveA.replicateWithContext(driveB, ctx);  // Uses ctx.timestamp for merge
```

### Enhancement 3: Add Cursor Access (Optional)

**For advanced users who want direct cursor access**:

```java
/**
 * Get the root cursor for this drive.
 * Advanced API for direct cursor manipulation.
 *
 * @return The root cursor
 */
public Root<AVector<ACell>> getRootCursor() {
    return rootCursor;
}
```

**Usage Example**:
```java
DLFSLocal drive = DLFS.createLocal();
Root<AVector<ACell>> cursor = drive.getRootCursor();

// Direct cursor manipulation with lattice
AVector<ACell> remoteNode = ... // from network
CVMLong timestamp = CVMLong.create(System.currentTimeMillis());
LatticeContext ctx = LatticeContext.create(timestamp, signingKey);

cursor.updateAndGet(current ->
    DLFSLattice.INSTANCE.merge(ctx, current, remoteNode)
);
```

## Testing Recommendations

### Test 1: Verify Lattice-Based Merge

```java
@Test
public void testMergeUsesLattice() throws IOException {
    DLFileSystem driveA = DLFS.createLocal();
    DLFileSystem driveB = DLFS.createLocal();

    driveA.setTimestamp(CVMLong.create(1000));
    Files.createFile(driveA.getPath("file.txt"));

    driveB.setTimestamp(CVMLong.create(2000));
    Files.createFile(driveB.getPath("other.txt"));

    // Merge should use lattice semantics
    driveA.replicate(driveB);

    // Both files should exist
    assertTrue(Files.exists(driveA.getPath("file.txt")));
    assertTrue(Files.exists(driveA.getPath("other.txt")));
}
```

### Test 2: Verify Context-Aware Merge

```java
@Test
public void testMergeWithContext() throws IOException {
    DLFileSystem driveA = DLFS.createLocal();
    DLFileSystem driveB = DLFS.createLocal();

    // Create conflicting files at different timestamps
    driveA.setTimestamp(CVMLong.create(1000));
    Files.write(driveA.getPath("conflict.txt"), new byte[]{1, 2, 3});

    driveB.setTimestamp(CVMLong.create(2000));
    Files.write(driveB.getPath("conflict.txt"), new byte[]{4, 5, 6});

    // Merge with explicit context timestamp = 3000
    CVMLong mergeTime = CVMLong.create(3000);
    LatticeContext ctx = LatticeContext.create(mergeTime, null);

    driveA.replicateWithContext(driveB, ctx);

    // Merged root should have context timestamp
    assertEquals(3000L,
        DLFSNode.getUTime(driveA.getNode(driveA.getRoot())).longValue());
}
```

### Test 3: Verify Cursor-Based Merge

```java
@Test
public void testCursorMergeWithLattice() {
    Root<AVector<ACell>> cursorA = Root.create(
        DLFSNode.createDirectory(CVMLong.create(1000))
    );

    Root<AVector<ACell>> cursorB = Root.create(
        DLFSNode.createDirectory(CVMLong.create(2000))
    );

    // Add data to both cursors
    // ... (via DLFSNode operations)

    AVector<ACell> nodeB = cursorB.get();

    // Merge using lattice with context
    CVMLong mergeTime = CVMLong.create(3000);
    LatticeContext ctx = LatticeContext.create(mergeTime, null);

    AVector<ACell> merged = cursorA.updateAndGet(nodeA ->
        DLFSLattice.INSTANCE.merge(ctx, nodeA, nodeB)
    );

    // Verify merge used context timestamp
    assertEquals(3000L, DLFSNode.getUTime(merged).longValue());
}
```

## Summary

### Current State

✅ **What Works**:
- Cursor-based mutable API
- Clean Java NIO filesystem interface
- Atomic updates via `Root<AVector<ACell>>`
- Replication between drives

❌ **What Needs Improvement**:
- `merge()` bypasses `DLFSLattice`
- No context-aware merge API
- Timestamp management is implicit
- No way to control merge behavior from user code

### Recommended Changes

**Priority 1: Use DLFSLattice in merge()**
- Update `DLFSLocal.merge()` to call `DLFSLattice.INSTANCE.merge()`
- Pass context with current drive timestamp

**Priority 2: Add Context-Aware API**
- Add `mergeWithContext(other, context)` method
- Add `replicateWithContext(other, context)` method

**Priority 3: Document Cursor Usage**
- Show how cursors work with lattices
- Provide examples of direct cursor manipulation

### API Design Principles

1. **Backwards Compatible**: Existing `merge()` and `replicate()` continue working
2. **Lattice-Aligned**: Use `DLFSLattice.merge()` not `DLFSNode.merge()`
3. **Context-Enabled**: Provide explicit context control when needed
4. **Progressive Enhancement**: Simple API for simple cases, advanced API for advanced cases

### Next Steps

1. Implement Priority 1 changes (use DLFSLattice in merge)
2. Add tests verifying lattice-based merge behavior
3. Implement Priority 2 (context-aware API) if needed for network sync
4. Update documentation with cursor + lattice usage patterns
