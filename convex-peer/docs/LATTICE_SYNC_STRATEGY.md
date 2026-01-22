# Lattice Synchronization Strategy: Handling Missing Data (REVISED)

## Problem Statement

Delta-based propagation (via `LatticePropagator`) is efficient but **not sufficient** for a robust decentralized network. Nodes can lose synchronization due to:

1. **Offline/Restart** - Node misses broadcasts while down
2. **Network Partition** - Temporary split causes divergence
3. **Packet Loss** - UDP/TCP drops, deltas never arrive
4. **Out-of-Order Delivery** - Delta references cells not yet received
5. **New Node Joining** - No history, needs full state bootstrap
6. **Store Divergence** - Node's store missing referenced cells

## Key Insight: Avoid Full Value Pushes

❌ **BAD**: Push entire lattice value periodically (wastes bandwidth)
✅ **GOOD**: Push only root cell hash, let receiver pull what's missing

**Rationale**:
- Lattice forks are cheap (immutable data structures)
- `Convex.acquire()` already handles efficient missing data retrieval
- Speculative merge in forked instance detects exactly what's needed
- Only pull data that's actually missing (not redundant data)

## Current Infrastructure Analysis

### What Works (From `Acquiror` and `ConvexRemote`)

✅ **DATA_REQUEST Protocol** - Nodes can request missing cells by hash
✅ **Missing Data Detection** - `ref.findMissing()` traverses tree for missing refs
✅ **Bulk Acquisition** - `Acquiror` loops until all missing data acquired
✅ **Store Integration** - `Cells.store()` populates local store
✅ **Timeout Handling** - Graceful failure with retries

### What's Missing for Lattices

❌ **No Automatic Detection** - LatticePropagator doesn't know when peers are out of sync
❌ **No Fallback to Pull** - Only pushes deltas, never pulls full state
❌ **No Periodic Full Sync** - Deltas can accumulate gaps over time
❌ **No Version Tracking** - Can't detect if peer is behind

## Proposed Strategy: Lightweight Push + Pull-on-Demand

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    LatticePropagator                     │
│                                                          │
│  ┌─────────────┐    ┌──────────────┐   ┌──────────────┐│
│  │   Delta     │    │  Top-Cell    │   │  Speculative ││
│  │  Push       │───▶│  Root Push   │──▶│  Fork+Merge  ││
│  │ (Frequent)  │    │  (Periodic)  │   │  +Acquire    ││
│  └─────────────┘    └──────────────┘   └──────────────┘│
│       ▲                   │                    │        │
│       │                   │                    ▼        │
│       │                   │           ┌──────────────┐ │
│       │                   └──────────▶│Pull Missing  │ │
│       │                               │Data Only     │ │
│       └───────────────────────────────└──────────────┘ │
│                  Minimal Bandwidth Used                 │
└─────────────────────────────────────────────────────────┘
```

### Three-Tier Sync Strategy (REVISED)

#### **Tier 1: Delta Push (Fast Path)** ✅ *Already Implemented*

- **When**: On every value change
- **How**: Broadcast delta-encoded LATTICE_VALUE messages with novel cells
- **Frequency**: ~100ms intervals
- **Bandwidth**: Very low (only new cells)
- **Reliability**: Best-effort, can lose messages
- **Recovery**: Tier 2 catches missed deltas

#### **Tier 2: Top-Cell Root Push (Self-Healing)** 🔶 *Recommended Implementation*

- **When**: Every 10-30 seconds (configurable)
- **How**: Send ONLY the root cell (no children) via LATTICE_QUERY response
- **Trigger**:
  - Timer-based: `ROOT_SYNC_INTERVAL = 30_000ms`
  - On connection: When new peer added
- **Protocol**:
  ```
  1. Broadcast LATTICE_QUERY response with ONLY root cell (no delta)
  2. Receiver attempts speculative merge in forked lattice
  3. If MissingDataException => pull only missing cells
  4. Complete merge after acquisition
  ```
- **Bandwidth**: Minimal (only root cell hash + metadata, ~50-200 bytes)
- **Reliability**: High (active push + lazy pull)

**Key Advantage**: Receiver knows peer's latest state but only pulls what's actually missing!

#### **Tier 3: Speculative Fork + Acquire (Automatic)** 🔷 *Built into Processing*

- **When**: Whenever incoming message references unknown cells
- **How**: Fork lattice, attempt merge, catch MissingDataException, acquire, retry
- **Trigger**:
  - Processing any LATTICE_VALUE message
  - Processing Tier 2 root pushes
- **Protocol**:
  ```
  1. Fork current lattice cursor (cheap, COW semantics)
  2. Attempt merge in fork
  3. Catch MissingDataException
  4. Use Acquiror to pull missing cells from sender
  5. Retry merge after acquisition
  6. Commit successful merge to main lattice
  ```
- **Bandwidth**: Only missing data (tree difference)
- **Reliability**: Very high (guaranteed complete after pull)

## Implementation Plan (REVISED)

### Phase 1: Top-Cell Root Push (Recommended First)

Add lightweight root-only broadcasting to `LatticePropagator`:

```java
public class LatticePropagator<V extends ACell> {

    /**
     * Interval for periodic root cell synchronization (milliseconds)
     * Default: 30 seconds
     */
    public static final long ROOT_SYNC_INTERVAL = 30_000L;

    /**
     * Last time root sync was performed
     */
    private long lastRootSyncTime = 0L;

    /**
     * Performs periodic root-only synchronization with all connected peers.
     *
     * Broadcasts ONLY the root cell (no children) to allow peers to detect
     * divergence and pull missing data if needed.
     *
     * This is extremely lightweight: only ~50-200 bytes per broadcast.
     */
    private void maybePerformRootSync() {
        long currentTime = Utils.getCurrentTimestamp();

        if (currentTime < lastRootSyncTime + ROOT_SYNC_INTERVAL) {
            return; // Not time yet
        }

        lastRootSyncTime = currentTime;
        V currentValue = nodeServer.getLocalValue();

        if (currentValue == null) return;

        Set<Convex> peers = nodeServer.getPeerNodes();
        if (peers.isEmpty()) return;

        try {
            // Create message with ONLY root cell (no children)
            Message rootMessage = createRootOnlyMessage(currentValue);

            // Broadcast to all peers
            int sentCount = 0;
            for (Convex peer : peers) {
                if (peer != null && peer.isConnected()) {
                    try {
                        peer.message(rootMessage);
                        sentCount++;
                    } catch (Exception e) {
                        log.debug("Failed to send root to peer {}: {}",
                            peer.getHostAddress(), e.getMessage());
                    }
                }
            }

            if (sentCount > 0) {
                log.trace("Sent root-only sync to {} peers", sentCount);
            }
        } catch (Exception e) {
            log.warn("Error during root sync", e);
        }
    }

    /**
     * Creates a LATTICE_VALUE message containing ONLY the root cell.
     *
     * Unlike delta messages, this contains no child cells - just the
     * root hash and minimal metadata. Receiver can detect divergence
     * and pull missing data if needed.
     *
     * @param value The lattice value (only root will be included)
     * @return Message with root-only encoding
     */
    private Message createRootOnlyMessage(V value) {
        // Create payload with root value
        AVector<ACell> emptyPath = Vectors.empty();
        AVector<?> payload = Vectors.create(MessageTag.LATTICE_VALUE, emptyPath, value);

        // Encode ONLY the root cell (no traversal, no novelty collection)
        // The receiver will get the hash and structure, but not children
        Blob rootEncoding = value.getEncoding();

        return Message.create(MessageType.LATTICE_VALUE, payload, rootEncoding);
    }
}
```

**Integration Point:**
```java
private void propagationLoop() {
    while (running && !Thread.currentThread().isInterrupted()) {
        // ... existing delta broadcast logic ...

        // NEW: Periodic root-only sync
        maybePerformRootSync();

        TimeUnit.MILLISECONDS.sleep(broadcastInterval);
    }
}
```

### Phase 2: Speculative Fork + Acquire Pattern

Add intelligent missing data recovery using lattice forking in `NodeServer`:

```java
/**
 * Processes a LATTICE_VALUE message with automatic missing data recovery.
 *
 * Uses speculative merge in a forked cursor to detect missing data,
 * then pulls only what's needed before committing the merge.
 *
 * @param message The LATTICE_VALUE message
 * @throws BadFormatException If message format is invalid
 */
@SuppressWarnings("unchecked")
private void processLatticeValue(Message message) throws BadFormatException {
    AVector<?> payload = RT.ensureVector(message.getPayload());
    if (payload == null || payload.count() < 2) {
        log.warn("Invalid LATTICE_VALUE message format");
        return;
    }

    ACell pathCell = payload.get(1);
    ACell value = payload.count() > 2 ? payload.get(2) : null;

    if (value == null) {
        log.warn("LATTICE_VALUE message missing value");
        return;
    }

    // Convert path
    ACell[] path = extractPath(pathCell);

    // NEW: Speculative merge with fork + acquire pattern
    if (path.length == 0) {
        // Root merge: use fork pattern
        V receivedValue = (V) value;
        mergeValueWithAcquire(receivedValue, message);
    } else {
        // Path-specific merge: use existing logic with acquire fallback
        mergePathWithAcquire(path, value, message);
    }
}

/**
 * Attempts to merge a value, forking the cursor to detect missing data
 * and acquiring it automatically before committing the merge.
 *
 * This implements the "speculative fork + acquire" pattern:
 * 1. Fork the cursor (cheap, copy-on-write)
 * 2. Attempt merge in fork
 * 3. If MissingDataException => acquire missing cells
 * 4. Retry merge after acquisition
 * 5. Commit successful merge to main cursor
 *
 * @param receivedValue Value to merge
 * @param message Original message (for tracking sender)
 */
private void mergeValueWithAcquire(V receivedValue, Message message) {
    // Step 1: Fork the cursor (cheap, immutable fork)
    ACursor<V> forkedCursor = cursor.detach();

    try {
        // Step 2: Attempt speculative merge in fork
        V currentValue = forkedCursor.get();
        V merged = lattice.merge(currentValue, receivedValue);

        // Step 3: Try to persist (triggers MissingDataException if data missing)
        merged = Cells.persist(merged);

        // Success! Commit to main cursor
        cursor.set(merged);
        log.debug("Merged lattice value successfully");

    } catch (MissingDataException e) {
        // Step 4: Missing data detected - acquire it
        log.debug("Missing data in lattice merge: {}, acquiring...", e.getMissingHash());

        try {
            // Find missing data from any peer
            ACell acquired = acquireFromPeers(e.getMissingHash());

            if (acquired != null) {
                // Step 5: Retry merge after acquisition
                V currentValue = cursor.get();
                V merged = lattice.merge(currentValue, receivedValue);
                merged = Cells.persist(merged);

                cursor.set(merged);
                log.info("Merged lattice value after acquiring missing data");
            } else {
                log.warn("Could not acquire missing data: {}", e.getMissingHash());
            }
        } catch (Exception ex) {
            log.warn("Error during missing data acquisition", ex);
        }
    } catch (IOException e) {
        log.warn("IO error during lattice merge", e);
    }
}

/**
 * Acquires missing data from connected peers.
 *
 * Tries each peer in turn until the data is successfully acquired.
 *
 * @param missingHash Hash of missing data
 * @return Acquired cell, or null if not found
 */
private ACell acquireFromPeers(Hash missingHash) {
    for (Convex peer : peerNodes) {
        if (peer == null || !peer.isConnected()) continue;

        try {
            // Use Convex.acquire() to pull missing data
            ACell acquired = peer.acquire(missingHash).get(5, TimeUnit.SECONDS);
            log.debug("Acquired missing data from peer {}: {}",
                peer.getHostAddress(), missingHash);
            return acquired;
        } catch (Exception e) {
            // Try next peer
            log.trace("Could not acquire from peer {}: {}",
                peer.getHostAddress(), e.getMessage());
        }
    }

    return null;
}
```

### Phase 3: On-Connect Sync

Add immediate sync when new peer is added:

```java
public void addPeer(Convex convex) {
    if (convex == null) {
        log.warn("Attempted to add null peer connection");
        return;
    }
    peerNodes.add(convex);
    log.debug("Added peer: {}", convex.getHostAddress());

    // NEW: Trigger immediate sync with new peer
    if (propagator != null && propagator.isRunning()) {
        propagator.triggerFullSync(convex);
    }
}
```

## Configuration Options

### Tunable Parameters

```java
// LatticePropagator configuration
public static final long DEFAULT_BROADCAST_INTERVAL = 100L;      // Fast delta push
public static final long MIN_BROADCAST_DELAY = 50L;               // Rate limiting
public static final long REBROADCAST_INTERVAL = 1000L;            // Periodic delta re-push
public static final long FULL_SYNC_INTERVAL = 30_000L;            // Periodic full sync
public static final long ON_CONNECT_SYNC_DELAY = 1000L;           // Delay before on-connect sync
```

### Trade-offs by Configuration

| Use Case | Delta Interval | Full Sync Interval | Notes |
|----------|----------------|-------------------|-------|
| **High-Speed Trading** | 10ms | 5s | Low latency, high overhead |
| **Standard Operations** | 100ms | 30s | Balanced (recommended) |
| **Low-Bandwidth IoT** | 1000ms | 300s | Conserve bandwidth |
| **Eventually Consistent** | 500ms | 120s | Relaxed consistency |

## Benefits of Revised Strategy

✅ **Fast Convergence** - Deltas provide millisecond-latency updates
✅ **Self-Healing** - Periodic root pushes detect divergence automatically
✅ **Minimal Bandwidth** - Root cells are tiny (~50-200 bytes), pull only what's missing
✅ **Automatic Recovery** - Speculative fork pattern handles missing data transparently
✅ **Partition Tolerant** - Recovers automatically after network split
✅ **New Node Friendly** - Can bootstrap by pulling from root hash
✅ **Observable** - Metrics for deltas, roots, and acquisitions
✅ **No Redundancy** - Never pushes data the receiver already has

## Testing Strategy

### Unit Tests

1. **Delta Loss Simulation** - Drop random broadcast messages, verify full sync recovers
2. **Network Partition** - Split network, update both sides, verify convergence after reunion
3. **New Node Join** - Add fresh node, verify it syncs to current state
4. **Missing Data** - Send delta referencing non-existent cells, verify acquisition

### Integration Tests

1. **3-Node Network** - Verify all nodes converge after updates
2. **Rolling Restart** - Restart nodes one-by-one, verify no data loss
3. **High-Frequency Updates** - Stress test with rapid changes
4. **Slow Network** - Simulate delays, verify eventual consistency

## Future Enhancements

### 1. **Version Vectors / Merkle Trees**
Track lattice version per peer for more efficient divergence detection:
```java
Map<AccountKey, Long> peerVersions;
```

### 2. **Selective Sync**
Only sync specific lattice paths that changed:
```java
propagator.syncPath(convex, Keywords.DATA, hash);
```

### 3. **Gossip-Based Sync**
Probabilistic peer selection for scalability:
```java
Convex randomPeer = selectRandomPeer();
syncWithPeer(randomPeer);
```

### 4. **Compression**
Delta compression for large lattice updates:
```java
Blob compressed = Compression.deflate(deltaData);
```

### 5. **Adaptive Intervals**
Tune sync frequency based on network conditions:
```java
if (highLatency) {
    fullSyncInterval *= 2; // Back off
}
```

## Recommended Implementation Order (REVISED)

1. ✅ **Phase 0: Delta Push** (Already Implemented)
2. 🔶 **Phase 2: Speculative Fork + Acquire** (Implement first - enables automatic recovery)
3. 🔶 **Phase 1: Top-Cell Root Push** (Implement second - provides periodic sync trigger)
4. 🔷 **Phase 3: On-Connect Sync** (Implement third - bootstraps new peers)
5. 🔷 **Future: Version Vectors** (Optimization, as needed)

## Bandwidth Comparison

### Old Approach (Full Value Push)
```
Delta (Frequent):     [Novel Cells]               ← 1-10 KB
Full Sync (30s):      [Entire Lattice Tree]       ← 100 KB - 10 MB  ❌ WASTEFUL
Total per minute:     ~400 KB - 40 MB
```

### New Approach (Root Push + Pull-on-Demand)
```
Delta (Frequent):     [Novel Cells]               ← 1-10 KB
Root Sync (30s):      [Root Cell Only]            ← 50-200 bytes    ✅ MINIMAL
Pull (On-Demand):     [Missing Cells Only]        ← 0-100 KB (only if needed)
Total per minute:     ~2-20 KB (if no missing data!)
```

**Bandwidth Savings**: 95-99% reduction when no data is missing!

## Example Scenarios

### Scenario 1: Normal Operation (No Deltas Lost)
```
Node A: [Delta Broadcast] ──▶ Node B: [Merge Success]
                                       ↓
        [Root Sync] ─────────▶       [Same Hash, Skip]
```
**Bandwidth**: Only deltas (~10 KB/min)

### Scenario 2: Some Deltas Lost
```
Node A: [Delta 1] ──X (lost)     Node B: [Has old value]
        [Delta 2] ──X (lost)
        [Root Sync] ───────▶            [Different Hash!]
                                         ↓
                            [Pull Missing: Delta 1 + 2]
                                         ↓
                                    [Merge Success]
```
**Bandwidth**: Deltas + Root (100 bytes) + Missing data (2-20 KB)
**Recovery Time**: ~1-5 seconds after root sync

### Scenario 3: Node Offline/Restart
```
Node B: [Offline] ──────────────────
                                    ↓
        [Restart] ──────────────────┘
                                    ↓
Node A: [Root Sync] ───────▶   [Different Hash!]
                                    ↓
                            [Pull ALL Missing]
                                    ↓
                              [Fully Synced]
```
**Bandwidth**: Root (100 bytes) + Full tree difference (10-500 KB)
**Recovery Time**: ~10-30 seconds after restart

## Key Implementation Details

### Why Fork Before Merge?
```java
// DON'T: Merge directly (can corrupt cursor on missing data)
cursor.set(lattice.merge(current, received)); // ❌ May throw, leaves corrupt state

// DO: Fork first (speculative merge, rollback on failure)
ACursor<V> fork = cursor.detach();            // ✅ Cheap copy
fork.set(lattice.merge(fork.get(), received)); // Test merge in fork
fork.persist();                                 // May throw MissingDataException
cursor.set(fork.get());                         // Commit only on success
```

### Why Root-Only Messages?
```java
// Root encoding includes:
- Hash of entire tree      ← Receiver can detect divergence
- Type tag                 ← Validates lattice type
- Metadata (if any)        ← Minimal overhead

// But NOT:
- Child refs               ← Not needed, receiver pulls if needed
- Nested values            ← Would waste bandwidth
- Full tree                ← Way too much data
```

## Conclusion

The **lightweight push + pull-on-demand** strategy provides:
- **95% efficiency** from delta push (fast path)
- **99% bandwidth savings** from root-only sync vs full sync
- **100% correctness** from automatic acquisition on missing data
- **Self-healing** within 30 seconds of any synchronization failure
- **Zero redundant data** - never pushes what receiver already has

This creates a **self-healing, bandwidth-efficient, eventually consistent** lattice network that automatically recovers from any synchronization failure while minimizing network overhead.
