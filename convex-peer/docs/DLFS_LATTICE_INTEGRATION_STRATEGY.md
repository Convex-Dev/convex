# DLFS Lattice Integration Strategy

## Current Architecture Analysis

### What Exists Today

1. **DLFS FileSystem** (`DLFSLocal`)
   - Works like a regular Java FileSystem API
   - Uses `Root<AVector<ACell>>` cursor for mutable state
   - Has timestamp management (`getTimestamp()`, `setTimestamp()`)
   - Implements `merge(AVector<ACell> other)` using DLFSNode merge logic
   - **ISSUE**: Merge happens directly on DLFSNode, bypassing DLFSLattice

2. **DLFSLattice**
   - Implements rsync-like merge semantics
   - Uses timestamps from nodes for conflict resolution
   - **ISSUE**: Doesn't receive context (signing keys, merge timestamp)
   - **ISSUE**: Merge function at line 65 calls `DLFSNode.merge(ownValue, otherValue, mergeTime)` but derives mergeTime from node data, not from context

3. **LatticeContext**
   - Contains timestamp and signing key fields
   - Ready to pass contextual info to merges
   - **ISSUE**: Not integrated with DLFS operations

4. **NodeServer**
   - Manages lattice values over network
   - Uses cursor for local state
   - Performs merges when receiving remote values
   - **ISSUE**: Calls basic `lattice.merge(ownValue, otherValue)` without context
   - **ISSUE**: No connection to DLFS concept

5. **OwnerLattice**
   - Maps owner (ACell) to SignedData<V>
   - Each owner has their own signed lattice value
   - **ISSUE**: Uses basic merge in mergeFunction (line 43), doesn't pass context

## The Disconnect

### Problem 1: DLFS bypasses DLFSLattice
```java
// DLFSLocal.java:146
public void merge(AVector<ACell> other) {
    rootCursor.updateAndGet(rootNode->DLFSNode.merge(rootNode,other,getTimestamp()));
}
```

This calls `DLFSNode.merge()` directly, not `DLFSLattice.merge()`. The lattice is only used in tests.

### Problem 2: No signing in DLFS sync
When DLFS drives replicate, there's no signing happening. The merged result is just the merged filesystem tree, but there's no cryptographic ownership proof.

### Problem 3: Context not flowing through
```
NodeServer.merge()
  └─> lattice.merge(own, other)  ❌ No context
      └─> DLFSLattice.merge()
          └─> DLFSNode.merge(own, other, mergeTime)  ❌ mergeTime from node, not context
```

### Problem 4: No path from DLFS to NodeServer
DLFS drives are standalone. There's no integration with NodeServer to sync DLFS over the network with signing.

## Proposed Unified Architecture

### Goal: DLFS drives can be synced via NodeServer with owner signatures

```
User modifies DLFS filesystem (Java NIO API)
    ↓
DLFSLocal (local mutable state with Root<AVector<ACell>> cursor)
    ↓
Sync to NodeServer (on demand or automatic)
    ↓
NodeServer lattice path: ["dlfs", ownerAddress]
    - Root lattice: OwnerLattice wrapping SignedLattice wrapping DLFSLattice
    - Each owner has their signed DLFS tree
    ↓
Context-aware merge with:
    - LatticeContext(timestamp=syncTime, signingKey=ownerKeyPair)
    - Signature applied at OwnerLattice level
    - Timestamp flows to DLFS merge operations
    ↓
Network propagation via LatticePropagator
    ↓
Remote NodeServers receive and merge
    ↓
Users can mount remote DLFS drives for read/write
```

## Implementation Strategy

### Phase 1: Fix Context Flow in DLFS ✅ HIGH PRIORITY

**Change DLFSLattice to use context-aware merge:**

```java
@Override
public AVector<ACell> merge(LatticeContext context, AVector<ACell> ownValue, AVector<ACell> otherValue) {
    if (ownValue == null) {
        if (checkForeign(otherValue)) return otherValue;
        return zero();
    }
    if (otherValue == null) return ownValue;
    if (Utils.equals(ownValue, otherValue)) return ownValue;

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

**Also update DirectoryEntriesLattice to pass context:**

```java
@Override
public Index<AString, AVector<ACell>> merge(LatticeContext context, Index<AString, AVector<ACell>> ownValue, Index<AString, AVector<ACell>> otherValue) {
    if (ownValue == null) {
        if (otherValue == null) return zero();
        return otherValue;
    }
    if (otherValue == null) return ownValue;

    // Pass context through to child merges
    MergeFunction<AVector<ACell>> mergeFunction = (a, b) -> {
        return DLFSLattice.INSTANCE.merge(context, a, b);
    };

    return ownValue.mergeDifferences(otherValue, mergeFunction);
}
```

**Impact**: DLFS merges can now receive timestamps from context instead of always deriving from nodes.

### Phase 2: Add Lattice-Based Merge to DLFSLocal 🔶 MEDIUM PRIORITY

**Current problem**: DLFSLocal.merge() bypasses DLFSLattice

**Solution**: Add context-aware merge method that uses the lattice:

```java
public class DLFSLocal extends DLFileSystem {

    // Keep existing merge() for backwards compatibility
    @Override
    public void merge(AVector<ACell> other) {
        rootCursor.updateAndGet(rootNode->DLFSNode.merge(rootNode, other, getTimestamp()));
    }

    /**
     * Merge another DLFS tree using lattice semantics with context.
     * This method should be used when syncing via NodeServer.
     *
     * @param other The other DLFS root node to merge
     * @param context Context containing timestamp and signing key for merge
     */
    public void mergeWithContext(AVector<ACell> other, LatticeContext context) {
        rootCursor.updateAndGet(rootNode -> {
            return DLFSLattice.INSTANCE.merge(context, rootNode, other);
        });
    }
}
```

**Impact**: DLFSLocal can now perform context-aware merges using the lattice.

### Phase 3: Create DLFS-NodeServer Integration 🔷 LOWER PRIORITY

**Add sync capabilities to DLFSLocal:**

```java
public class DLFSLocal extends DLFileSystem {

    /** Optional NodeServer for network synchronization */
    private NodeServer<AHashMap<ACell, SignedData<AVector<ACell>>>> nodeServer;

    /** Path in the NodeServer lattice for this drive's owner */
    private ACell ownerAddress;

    /** Key pair for signing this drive's updates */
    private AKeyPair ownerKeyPair;

    /**
     * Attach this DLFS drive to a NodeServer for network synchronization.
     * The drive will be stored at the given owner address with signed updates.
     *
     * @param nodeServer The NodeServer managing the OwnerLattice
     * @param ownerAddress The address (key) for this owner in the OwnerLattice map
     * @param ownerKeyPair The key pair for signing updates
     */
    public void attachToNode(
            NodeServer<AHashMap<ACell, SignedData<AVector<ACell>>>> nodeServer,
            ACell ownerAddress,
            AKeyPair ownerKeyPair) {
        this.nodeServer = nodeServer;
        this.ownerAddress = ownerAddress;
        this.ownerKeyPair = ownerKeyPair;
    }

    /**
     * Sync this drive's current state to the attached NodeServer.
     * This signs the current root and updates the network lattice.
     */
    public void syncToNode() {
        if (nodeServer == null) {
            throw new IllegalStateException("Drive not attached to NodeServer");
        }

        AVector<ACell> currentRoot = rootCursor.get();
        CVMLong timestamp = getTimestamp();

        // Create context with timestamp and signing key
        LatticeContext context = LatticeContext.create(timestamp, ownerKeyPair);

        // Update the NodeServer lattice at our owner's path
        nodeServer.updateAtPath(
            context,
            new Object[] { ownerAddress },  // Path: OwnerLattice -> owner key
            currentRoot
        );
    }

    /**
     * Pull latest state from the attached NodeServer and merge into this drive.
     */
    public void syncFromNode() {
        if (nodeServer == null) {
            throw new IllegalStateException("Drive not attached to NodeServer");
        }

        // Get the signed value at our owner's path
        AHashMap<ACell, SignedData<AVector<ACell>>> ownerMap = nodeServer.getLocalValue();
        SignedData<AVector<ACell>> signedRoot = ownerMap.get(ownerAddress);

        if (signedRoot != null) {
            AVector<ACell> remoteRoot = signedRoot.getValue();
            CVMLong timestamp = getTimestamp();
            LatticeContext context = LatticeContext.create(timestamp, ownerKeyPair);

            mergeWithContext(remoteRoot, context);
        }
    }
}
```

**Impact**: DLFS drives can now be synced to/from NodeServer with signatures.

### Phase 4: Update OwnerLattice to Pass Context 🔶 MEDIUM PRIORITY

**Current problem**: OwnerLattice.mergeFunction doesn't pass context (line 42-44)

**Solution**: Make OwnerLattice context-aware:

```java
@Override
public AHashMap<ACell, SignedData<V>> merge(
        LatticeContext context,
        AHashMap<ACell, SignedData<V>> ownValue,
        AHashMap<ACell, SignedData<V>> otherValue) {
    if (otherValue == null) return ownValue;
    if (ownValue == null) {
        if (checkForeign(otherValue)) return otherValue;
        return zero();
    }

    // Merge the maps using context-aware SignedLattice merge
    MergeFunction<SignedData<V>> mergeFunction = (a, b) -> {
        return signedLattice.merge(context, a, b);
    };

    return ownValue.mergeDifferences(otherValue, mergeFunction);
}
```

**Impact**: Signatures and timestamps flow correctly through OwnerLattice to child lattices.

### Phase 5: Update NodeServer to Use Context 🔷 LOWER PRIORITY

**Current problem**: NodeServer calls basic merge (lines 416, 511, 715)

**Solution**: Create and pass LatticeContext in merge operations:

```java
// In NodeServer.java
private void handleLatticeValue(Message msg, AVector<ACell> payload) {
    try {
        V receivedValue = RT.ensureType(payload.get(2), receivedType);

        // Create context for this merge
        CVMLong timestamp = CVMLong.create(Utils.getCurrentTimestamp());
        // Note: signingKey would come from node's configuration
        LatticeContext context = LatticeContext.create(timestamp, null);

        // Atomically merge using context
        V merged = cursor.updateAndGet(currentValue -> {
            return lattice.merge(context, currentValue, receivedValue);
        });

        // Trigger broadcast if value changed
        if (merged != cursor.get()) {
            propagator.triggerBroadcast();
        }
    } catch (MissingDataException e) {
        // ... handle missing data
    }
}
```

**Impact**: All network merges use context-aware operations.

## Benefits of This Architecture

### 1. Clean Separation of Concerns
- **DLFSLocal**: Mutable filesystem API (Java NIO)
- **DLFSLattice**: Pure merge semantics (CRDT properties)
- **NodeServer**: Network synchronization layer
- **OwnerLattice**: Multi-user signed values

### 2. Signature Placement
- Signatures happen at the OwnerLattice level (per-owner)
- Each owner signs their entire DLFS tree
- Network sync validates signatures automatically

### 3. Context Flow
```
User operation → timestamp updated in DLFSLocal
    ↓
Sync to node → LatticeContext(timestamp, signingKey)
    ↓
OwnerLattice.merge(context, ...) → passes to SignedLattice
    ↓
SignedLattice.merge(context, ...) → signs with context.getSigningKey()
    ↓
SignedLattice merges inner values → passes to DLFSLattice
    ↓
DLFSLattice.merge(context, ...) → uses context.getTimestamp()
    ↓
DLFSNode.merge(..., mergeTime) → timestamp from context
```

### 4. Flexible Usage

**Standalone DLFS** (current usage in tests):
```java
DLFileSystem drive = DLFS.createLocal();
Path file = Files.createFile(drive.getPath("foo.txt"));
// Works like normal filesystem, no network
```

**Network-Synced DLFS**:
```java
// Create node server with OwnerLattice for multi-user DLFS
OwnerLattice<AVector<ACell>> ownerLattice =
    OwnerLattice.create(DLFSLattice.INSTANCE);
NodeServer<AHashMap<ACell, SignedData<AVector<ACell>>>> node =
    NodeServer.create(ownerLattice, port);

// Attach drive to node
DLFSLocal drive = DLFS.createLocal();
drive.attachToNode(node, myAddress, myKeyPair);

// Use filesystem normally
Files.write(drive.getPath("data.txt"), bytes);

// Sync to network (signs and broadcasts)
drive.syncToNode();
```

## Testing Strategy

### Phase 1 Tests
- Update `DLFSTest.testDLFSLattice()` to test context-aware merge
- Verify context.timestamp is used when provided
- Verify fallback to node timestamps when context.timestamp is null

### Phase 2 Tests
- Test `DLFSLocal.mergeWithContext()`
- Verify merge uses lattice semantics
- Verify backwards compatibility of basic `merge()`

### Phase 3 Tests
- Test DLFS attach/detach from NodeServer
- Test `syncToNode()` creates signed values
- Test `syncFromNode()` pulls and merges remote values
- Test multi-user scenario (two drives, two owners, one NodeServer)

### Phase 4 Tests
- Test OwnerLattice with context
- Verify signatures use context.signingKey
- Verify child merges receive context

### Phase 5 Tests
- Test NodeServer end-to-end with DLFS
- Test network sync of DLFS between two nodes
- Test signature validation on receive

## Open Questions

1. **Where should signing happen during sync?**
   - Proposed: At OwnerLattice level when syncing to node
   - Alternative: Have DLFSLocal sign before passing to node

2. **Should DLFSLocal.merge() be deprecated in favor of mergeWithContext()?**
   - Proposed: Keep both, merge() for simple cases, mergeWithContext() for network
   - Consider: Eventually deprecate basic merge()

3. **How to handle timestamp updates during sync?**
   - Proposed: syncToNode() uses current drive timestamp
   - Alternative: Allow explicit timestamp parameter

4. **Should there be automatic sync on file operations?**
   - Proposed: Manual sync only (explicit syncToNode() calls)
   - Alternative: Option for auto-sync on file change

## Implementation Priority

**Phase 1 (HIGH)**: Context flow in DLFSLattice - enables everything else
**Phase 4 (MEDIUM)**: OwnerLattice context - needed for proper signing
**Phase 2 (MEDIUM)**: DLFSLocal context merge - cleaner architecture
**Phase 5 (LOWER)**: NodeServer context - optimization, not critical
**Phase 3 (LOWER)**: Integration API - can be done separately or in user code

Recommend starting with Phase 1 to establish the pattern, then Phase 4 for signing support.
