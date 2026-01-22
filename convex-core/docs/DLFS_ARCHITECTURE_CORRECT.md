# DLFS Architecture - Correct Layering

## Conceptual Model

```
┌─────────────────────────────────────────────────────────────┐
│                    User Application                          │
│              (Java NIO FileSystem API)                       │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                  DLFileSystem / DLFSLocal                     │
│           (Mutable Java interface to DLFS)                   │
│  - Wraps Root<AVector<ACell>> cursor                         │
│  - Provides Java NIO Path operations                         │
│  - Drive timestamp: for NEW file operations                  │
│  - replicate(other): convenience method                      │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Uses cursor.updateAndGet()
                       │
┌──────────────────────▼───────────────────────────────────────┐
│              Root<AVector<ACell>> Cursor                      │
│           (Atomic mutable reference)                         │
│  - Holds current DLFS root node                              │
│  - updateAndGet(rootNode -> ...)                             │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ Pure functions on AVector<ACell>
                       │
         ┌─────────────┴─────────────┐
         │                           │
┌────────▼──────────┐      ┌─────────▼────────────┐
│    DLFSNode       │      │   DLFSLattice        │
│  (Pure functions) │      │ (Lattice semantics)  │
│                   │      │                      │
│ - merge()         │◄─────┤ - merge()            │
│ - navigate()      │      │ - zero()             │
│ - updateNode()    │      │ - checkForeign()     │
│ - createFile()    │      │ - path()             │
│ - getUTime()      │      │                      │
└───────────────────┘      └──────────────────────┘
         ▲                           ▲
         │                           │
         └───────────┬───────────────┘
                     │
                 Common merge logic
            (timestamp-based conflict resolution)
```

## Correct Dependencies

### DLFSNode (convex.lattice.fs.DLFSNode)
- **Pure utility functions** for DLFS data structures
- `merge(node1, node2, mergeTime)` - Implements rsync-like merge logic
- `navigate(root, path)` - Traverse filesystem tree
- `updateNode(root, path, newNode, timestamp)` - Update specific path
- `getUTime(node)` - Extract timestamp from node
- **No dependencies** on DLFileSystem or DLFSLattice

### DLFSLattice (convex.lattice.fs.DLFSLattice)
- **Lattice abstraction** for DLFS values
- `merge(own, other)` - Gets timestamps from nodes, delegates to DLFSNode.merge()
- `merge(context, own, other)` - Uses context timestamp if provided, else from nodes
- **Depends on**: DLFSNode for merge implementation
- **No dependency** on DLFileSystem

### DLFileSystem / DLFSLocal (convex.lattice.fs.impl.DLFSLocal)
- **Java API wrapper** around DLFS cursor
- `Root<AVector<ACell>> rootCursor` - Mutable state
- `CVMLong timestamp` - For NEW file operations (createFile, updateNode)
- `replicate(other)` - Convenience wrapper around cursor merge
- **Depends on**: DLFSNode for operations, Root cursor for state
- **Should NOT depend on**: DLFSLattice (that's a separate abstraction)

## Key Insight: Two Different Timestamps

### 1. Drive Timestamp (DLFileSystem.timestamp)
```java
driveA.setTimestamp(CVMLong.create(1000));
Files.createFile(driveA.getPath("newfile.txt"));  // Created at time 1000
```

**Purpose**:
- Used for **NEW** file operations
- Sets `utime` field in newly created nodes
- Controlled by user: `setTimestamp()`, `getTimestamp()`

**NOT used for**:
- Merge operations (except as fallback in current implementation)

### 2. Merge Timestamp (from nodes or context)
```java
// Node A has files with timestamps 1000, 2000
// Node B has files with timestamps 1500, 2500
merge(nodeA, nodeB)  // Uses timestamps FROM the nodes (max = 2500)
```

**Purpose**:
- Conflict resolution during merge
- Comes from existing node timestamps
- Can be overridden via LatticeContext

**Sources** (in priority order):
1. LatticeContext.getTimestamp() (explicit override)
2. Max of node timestamps (DLFSNode.getUTime(ownValue), DLFSNode.getUTime(otherValue))

## Current Implementation Issues

### Issue 1: DLFSLocal.merge() Confuses Timestamps ❌

**Current code** (DLFSLocal.java:145-147):
```java
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode ->
        DLFSNode.merge(rootNode, other, getTimestamp())  // ❌ WRONG timestamp
    );
}
```

**Problem**: Uses `getTimestamp()` which is the drive's "current time for new operations", not the merge timestamp from the nodes being merged.

**What happens**:
```java
// Drive A has files at times 1000, 2000, 3000
driveA.setTimestamp(CVMLong.create(500));  // Set current time to 500
driveA.replicate(driveB);  // Merge uses 500 ❌ (older than existing files!)
```

This is semantically wrong. The merge timestamp should come from the nodes, not the drive's current timestamp.

### Issue 2: DLFSLattice.merge() is Correct ✅

**Current code** (DLFSLattice.java:68-97):
```java
@Override
public AVector<ACell> merge(LatticeContext context, AVector<ACell> ownValue, AVector<ACell> otherValue) {
    // ... null checks ...

    // Get merge timestamp from context, fallback to node timestamps
    CVMLong mergeTime = context.getTimestamp();
    if (mergeTime == null) {
        // Fallback: use max of node timestamps
        CVMLong timeA = DLFSNode.getUTime(ownValue);
        CVMLong timeB = DLFSNode.getUTime(otherValue);
        mergeTime = timeA.longValue() >= timeB.longValue() ? timeA : timeB;
    }

    return DLFSNode.merge(ownValue, otherValue, mergeTime);
}
```

**This is correct**:
- Uses context timestamp if provided
- Falls back to max of node timestamps
- Properly separates merge time from drive time

## Correct Implementation

### DLFSLocal.merge() Should NOT Use Drive Timestamp

**Option 1: Don't call DLFSNode directly, call DLFSLattice**
```java
@Override
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode ->
        DLFSLattice.INSTANCE.merge(LatticeContext.EMPTY, rootNode, other)
        // Uses max of node timestamps, NOT drive timestamp
    );
}
```

**Option 2: Call DLFSNode with correct timestamp**
```java
@Override
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode -> {
        // Get merge timestamp from nodes being merged
        CVMLong timeA = DLFSNode.getUTime(rootNode);
        CVMLong timeB = DLFSNode.getUTime(other);
        CVMLong mergeTime = timeA.longValue() >= timeB.longValue() ? timeA : timeB;

        return DLFSNode.merge(rootNode, other, mergeTime);
    });
}
```

**Option 1 is better**: Uses the lattice abstraction properly.

### Optional: Add Context-Aware Merge

```java
/**
 * Merge with explicit context control (advanced API)
 */
public void mergeWithContext(AVector<ACell> other, LatticeContext context) {
    rootCursor.updateAndGet(rootNode ->
        DLFSLattice.INSTANCE.merge(context, rootNode, other)
    );
}
```

This allows explicit timestamp override when needed, but is separate from the drive timestamp.

## Correct Dependency Graph

```
DLFSNode.merge()
   ▲
   │ calls
   │
DLFSLattice.merge()
   ▲
   │ uses (optional)
   │
DLFSLocal.merge()
   │
   │ uses
   │
   ▼
Root<AVector<ACell>>.updateAndGet()
```

**Key point**: DLFSLocal can choose to use DLFSLattice or call DLFSNode directly, but either way it should NOT use `getTimestamp()` for merge operations.

## Drive Timestamp Usage (Correct)

The drive timestamp (`DLFileSystem.timestamp`) should ONLY be used for:

1. **Creating new files** (DLFSLocal.java:100):
   ```java
   AVector<ACell> newNode = DLFSNode.createEmptyFile(getTimestamp());
   ```

2. **Updating nodes** (DLFSLocal.java:126):
   ```java
   rootCursor.updateAndGet(rootNode ->
       DLFSNode.updateNode(rootNode, dir, newNode, getTimestamp())
   );
   ```

3. **Creating directories** (DLFSLocal.java:79):
   ```java
   updateNode(dir, DLFSNode.createDirectory(getTimestamp()));
   ```

4. **Creating tombstones** (DLFSLocal.java:121):
   ```java
   updateNode(path, DLFSNode.createTombstone(getTimestamp()));
   ```

**NOT for**: Merge operations (those use timestamps from the nodes being merged)

## Summary of Corrections

### Incorrect Understanding ❌
- DLFSLattice depends on DLFileSystem
- Drive timestamp is used for merges
- DLFileSystem.merge() should pass its timestamp to merge operations

### Correct Understanding ✅
- DLFSNode and DLFSLattice are **independent** utilities
- DLFileSystem uses them but they don't depend on it
- Drive timestamp is for **new operations**, not merges
- Merge timestamp comes from **existing node data**
- LatticeContext can override merge timestamp (advanced use case)

### Action Items

1. ✅ DLFSLattice implementation is correct (gets timestamp from nodes)
2. ❌ DLFSLocal.merge() is incorrect (uses drive timestamp)
3. 🔧 Fix: Make DLFSLocal.merge() call DLFSLattice or use node timestamps
4. 📝 Update DLFS_CURSOR_API_REVIEW.md with correct understanding
5. 🧪 Update tests to verify correct timestamp behavior

## Test Case Demonstrating the Issue

```java
@Test
public void testMergeDoesNotUseDriveTimestamp() throws IOException {
    DLFileSystem driveA = DLFS.createLocal();
    DLFileSystem driveB = DLFS.createLocal();

    // Create files at time 2000 in both drives
    driveA.setTimestamp(CVMLong.create(2000));
    Files.createFile(driveA.getPath("fileA.txt"));

    driveB.setTimestamp(CVMLong.create(2000));
    Files.createFile(driveB.getPath("fileB.txt"));

    // Now set driveA's current timestamp to 1000 (earlier than files)
    driveA.setTimestamp(CVMLong.create(1000));

    // Merge should use timestamps FROM the nodes (2000), not drive timestamp (1000)
    driveA.replicate(driveB);

    AVector<ACell> root = driveA.getNode(driveA.getRoot());
    CVMLong rootTime = DLFSNode.getUTime(root);

    // Root should have time 2000 (from nodes), NOT 1000 (from drive)
    assertEquals(2000L, rootTime.longValue(),
        "Merge should use node timestamps, not drive timestamp");
}
```

**Current behavior**: Fails (uses 1000)
**Correct behavior**: Should pass (uses 2000)
