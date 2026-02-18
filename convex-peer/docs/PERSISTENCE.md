# NodeServer Persistence Design

## Status Quo

NodeServer holds all lattice state in a `Root<V>` cursor backed by `AtomicReference<V>`. On
shutdown, everything is lost. The `AStore` field is used only for network data resolution
(delta encoding, missing data acquisition) — never for persisting the lattice value itself.

The existing `Server` (CVM peer) has a complete persistence lifecycle:
`persistPeerData()` → `store.setRootData()` → Etch on disk → `Peer.restorePeer()` on restart.
NodeServer needs the equivalent, but adapted for lattice semantics and the needs of lattice
applications like DLFS.

## Design Goals

1. **Lattice apps update their own cursor in memory** — writes are instant, persist/sync
   happens asynchronously or on demand
2. **Hot push to trusted replicas** with novelty detection (delta encoding)
3. **Filtering before push** — exclude local/private data from outbound lattice values
4. **Works with any AStore** — including non-persistent (memory) stores
5. **Atomic operations** — persist, merge, and filter are all-or-nothing
6. **O(1) snapshots** — immutable lattice data structures make cheap snapshots the norm;
   hand copies to other threads freely
7. **Connection management** for hot replicas

## Architecture

### Lifecycle Overview

```
                        ┌──────────────────────────────┐
                        │        NodeServer             │
                        │                               │
  App writes ──────────►│  cursor: Root<V>              │
  (instant, in-memory)  │    │                          │
                        │    │ O(1) snapshot             │
                        │    ▼                          │
                        │  ┌─────────────────────────┐  │
                        │  │   Persistence Pipeline   │  │
                        │  │                          │  │
                        │  │  snapshot ──► filter ──► │──┼──► Hot replicas (delta push)
                        │  │      │                   │  │
                        │  │      ▼                   │  │
                        │  │  store.setRootData()     │  │
                        │  │  (if persistent store)   │  │
                        │  └─────────────────────────┘  │
                        └──────────────────────────────┘
```

### Value Flow

```
Local app writes
    │
    ▼
cursor.set(value)               ← instant, in-memory
    │
    │  triggerPersist()          ← signals persistence pipeline
    ▼
┌─────────────────────────────────────────────────────────┐
│ Persistence Pipeline (background thread)                 │
│                                                          │
│  1. V snapshot = cursor.get()          ← O(1) ref copy  │
│  2. V filtered = filter(snapshot)      ← strip private   │
│  3. Cells.persist(snapshot, store)     ← write to Etch   │
│  4. store.setRootData(snapshot)        ← anchor for      │
│                                          restore         │
│  5. Cells.announce(filtered, handler)  ← collect novelty │
│  6. broadcast(filtered, novelty)       ← delta push      │
└─────────────────────────────────────────────────────────┘
```

Key invariant: the cursor is **never blocked** by persistence or network I/O. Apps write
to the cursor and continue immediately. The pipeline picks up the latest value at its own
pace. If multiple writes happen between pipeline runs, intermediate values are naturally
skipped — only the latest snapshot matters (lattice idempotence).

### Snapshot Semantics

Lattice data structures are immutable trees of `ACell` values. A "snapshot" is simply
reading the current reference — O(1), zero-copy. The snapshot can be handed to any thread
(persistence, broadcast, filter) without coordination.

```java
V snapshot = cursor.get();  // O(1), immutable, safe to share across threads
```

This is the foundation for non-blocking persistence: the app thread and the persistence
pipeline never contend on the same mutable state.

## Persistence Lifecycle

### Startup: Restore

```java
public void launch() {
    // 1. Check if store has persistent data
    if (store.isPersistent()) {
        Hash rootHash = store.getRootHash();
        if (rootHash != null) {
            Ref<?> rootRef = store.refForHash(rootHash);
            if (rootRef != null) {
                AMap<ACell,ACell> rootData = (AMap<ACell,ACell>) rootRef.getValue();
                V restored = (V) rootData.get(rootKey);
                if (restored != null) {
                    cursor.set(restored);
                    log.info("Restored lattice value from store");
                }
            }
        }
    }

    // 2. Start network, propagator, etc.
    ...
}
```

### Running: Periodic + Triggered Persistence

Persistence is triggered by:
- **Value change** — `updateLocal()` and incoming merges signal the pipeline
- **Timer** — periodic snapshots at configurable interval (default 30s)
- **Shutdown** — final persist before close

The pipeline coalesces rapid updates: if 100 writes happen in 50ms, only the final
snapshot is persisted. This is correct because lattice merge is idempotent.

### Shutdown: Final Persist

```java
public void close() {
    // 1. Stop accepting new connections and messages
    // 2. Final persist of current value
    if (store.isPersistent()) {
        persistSnapshot(cursor.get());
    }
    // 3. Close propagator, network server, peer connections
}
```

### Store Compatibility

Not all stores are persistent (e.g. `MemoryStore` for testing). The pipeline must
handle this gracefully:

```java
boolean isPersistent = store instanceof EtchStore;
// or: add isPersistent() to AStore interface
```

When using a non-persistent store:
- Skip `setRootData()` and disk writes
- Novelty tracking and delta broadcast still work (announce uses in-memory tracking)
- On restart, value resets to `lattice.zero()` (no restore)

## Filtering

### Motivation

A node's lattice state may contain data that should not leave the node:
- **Private drives** in DLFS — user marks a drive as local-only
- **Draft/staging data** — incomplete edits not ready for replication
- **Node-local metadata** — configuration, caches, indices

### Filter Interface

```java
@FunctionalInterface
public interface LatticeFilter<V extends ACell> {
    /**
     * Filters a lattice value before outbound replication.
     * Returns the value with private data removed/nulled.
     * Must be idempotent: filter(filter(v)) == filter(v).
     * Must preserve lattice properties: merge(filter(a), filter(b)) == filter(merge(a, b))
     * for the public subset.
     *
     * @param value The full local value
     * @return The filtered value suitable for replication
     */
    V filter(V value);
}
```

### Filter Placement

Filtering happens **after snapshot, before broadcast**:

```
cursor.get()  →  snapshot  →  filter(snapshot)  →  announce + broadcast
                     │
                     └→  persist(snapshot)  ← full value persisted locally
```

The **full unfiltered value** is persisted to local store (so private data survives
restart). Only the **filtered value** is announced and broadcast to peers.

### Per-Connection Filters

Different replicas may have different trust levels:

```java
nodeServer.addReplica(peerAddress, filter);  // custom filter per replica
nodeServer.addReplica(trustedPeer, null);    // null = no filter (full replication)
```

This enables:
- **Public replicas** — heavy filtering, only public drives
- **Trusted replicas** — full or lightly filtered replication
- **Backup replicas** — full replication including private data

## Novelty and Delta Push

### Current Mechanism (LatticePropagator)

`LatticePropagator` already implements delta broadcasting:

1. `Cells.announce(value, noveltyHandler, store)` — marks cells as announced, collects
   novel (first-seen) cells via the handler
2. `Format.encodeDelta(novelty)` — encodes only novel cells
3. Broadcast delta message to peers

### Change for Persistence

The persistence pipeline integrates with the existing propagator:

```
Pipeline run:
  1. snapshot = cursor.get()
  2. filtered = filter(snapshot)
  3. persist(snapshot, store)              ← NEW: write full value to Etch
  4. announce(filtered, noveltyHandler)    ← existing: collect novelty for filtered value
  5. broadcast(filtered, novelty)          ← existing: delta push
```

Step 3 (persist) writes the full tree to Etch. This also populates the store's hash
index, which means step 4 (announce) can efficiently detect novelty — cells already
persisted are not novel.

### Atomicity

Each pipeline run operates on a single immutable snapshot. There is no partial state:

- **Persist** writes the entire snapshot tree atomically (Etch is append-only, root hash
  update is the commit point)
- **Filter** produces a new immutable value — no mutation
- **Announce** is idempotent — re-announcing the same cells is a no-op
- **Broadcast** is fire-and-forget — peers merge independently

If the pipeline crashes mid-run:
- Persist may have written some cells but not updated the root hash → on restart, those
  cells are orphaned (harmless, can be GC'd)
- Broadcast may have partially sent → peers will catch up via root sync

## Connection Management

### Replica Types

```java
public enum ReplicaMode {
    /** Full bidirectional sync — merge incoming, push outgoing */
    FULL_SYNC,
    /** Push only — send our updates, ignore incoming */
    PUSH_ONLY,
    /** Pull only — accept incoming, don't push */
    PULL_ONLY
}
```

### Replica Configuration

```java
public class ReplicaConfig {
    final InetSocketAddress address;
    final ReplicaMode mode;
    final LatticeFilter<V> filter;        // null = no filtering
    final boolean autoReconnect;           // reconnect on disconnect
    final long reconnectIntervalMs;        // backoff interval
}
```

### Connection Lifecycle

```
addReplica(config)
    │
    ▼
┌──────────────────┐     connect fails     ┌─────────────────┐
│   CONNECTING     │ ────────────────────► │  WAITING         │
│   (async)        │                       │  (backoff timer) │
└────────┬─────────┘                       └────────┬─────────┘
         │ connected                                │ timer fires
         ▼                                          │
┌──────────────────┐◄───────────────────────────────┘
│   ACTIVE         │
│   - delta push   │     connection lost
│   - merge pull   │ ────────────────────► [WAITING if autoReconnect]
│   - root sync    │                       [REMOVED if not]
└──────────────────┘
```

On connect, an immediate root sync is triggered to bring the replica up to date.
Subsequent updates use delta push (via LatticePropagator).

### Thread Safety

- `peerNodes` set uses `ConcurrentHashMap.newKeySet()` for lock-free iteration
- Connection state transitions are atomic (CAS on state enum)
- Broadcast iterates a snapshot of the peer set — new/removed connections don't
  affect in-flight broadcasts

## Configuration

### NodeServer Builder

```java
NodeServer<V> server = NodeServer.builder(lattice, store)
    .port(8765)
    .rootKey(Keywords.create("myapp"))     // key in store root data
    .filter(myFilter)                      // default outbound filter
    .persistInterval(30_000)               // ms between periodic persists
    .restore(true)                         // restore from store on startup
    .build();
```

### Configuration Keywords

| Keyword | Type | Default | Description |
|---------|------|---------|-------------|
| `:port` | Integer | null (random) | Network listen port |
| `:store` | AStore | MemoryStore | Persistence backend |
| `:root-key` | ACell | null | Key in store root data map |
| `:restore` | Boolean | true | Restore from store on startup |
| `:persist` | Boolean | true | Persist on shutdown |
| `:persist-interval` | Long | 30000 | Periodic persist interval (ms) |
| `:filter` | LatticeFilter | null | Default outbound filter |

## Interaction with Lattice Apps

### DLFS Example

A DLFS drive manager holds a cursor into the lattice tree:

```java
// NodeServer holds the full lattice: OwnerLattice(MapLattice(DLFSLattice))
NodeServer<V> node = ...;

// App gets a sub-cursor for its region of the tree
ACursor<V> rootCursor = node.getCursor();

// DLFS writes directly to its cursor (instant, in-memory)
DLFSLocal drive = new DLFSLocal(provider, uri, rootCursor.path(ownerKey, driveName));
Files.write(drive.getPath("/readme.txt"), "hello".getBytes());

// The write updates the cursor immediately.
// NodeServer's persistence pipeline will:
//   1. Snapshot the full tree (including the DLFS change)
//   2. Persist to Etch
//   3. Filter and broadcast to replicas
// All asynchronously — the DLFS write is already complete.
```

### Custom App Pattern

```java
// App updates its portion of the lattice
node.getCursor().set(newAppState, appKey);

// Or use a forked cursor for batch operations
ALatticeCursor<V> fork = node.getCursor().fork();
fork.set(value1, path1);
fork.set(value2, path2);
fork.set(value3, path3);
fork.sync();  // atomic merge back to parent — always succeeds (lattice)
```

## Relation to Existing Docs

### DLFS_LATTICE_INTEGRATION_STRATEGY.md

That document describes how DLFS integrates with the lattice hierarchy (OwnerLattice,
context flow, signing). It covers the **data model** and **merge semantics** — what
values are stored and how they merge. This document covers the **operational lifecycle**
— how values are persisted, filtered, replicated, and restored. The two are complementary:

- **Superseded**: Phase 3 (DLFSLocal.attachToNode / syncToNode) — the cursor-based
  approach here is simpler. DLFS gets a sub-cursor and writes directly; no explicit
  sync calls needed.
- **Still needed**: Phase 1 (context flow in DLFSLattice), Phase 2 (lattice-based merge
  in DLFSLocal), Phase 4 (OwnerLattice context), Phase 5 (NodeServer context).

### LATTICE_SYNC_STRATEGY.md

Describes the network sync protocol (delta push, root sync, speculative fork + acquire).
This document does not change the sync protocol — it adds a persistence layer underneath.
The key integration point: the persistence pipeline feeds into the existing
LatticePropagator rather than replacing it.

### STORE_REFACTOR_PLAN.md

Describes removing ThreadLocal stores in favour of explicit store passing. This
document assumes explicit stores throughout — NodeServer already holds its store as
a field. The persistence pipeline uses `this.store` directly.

## Implementation Phases

### Phase 1: Core Persistence

Add persist/restore to NodeServer using the existing `Server.persistPeerData()` pattern.

- Add `rootKey` configuration field
- Add `persistSnapshot(V value)` method — `Cells.persist()` + `store.setRootData()`
- Add restore logic in `launch()` — read from `store.getRootHash()` + `rootKey`
- Add final persist in `close()`
- Add `isPersistent()` check (skip disk ops for MemoryStore)

### Phase 2: Periodic + Triggered Persistence

Integrate persistence into the LatticePropagator loop.

- Add persist trigger alongside broadcast trigger
- Configurable persist interval (default 30s)
- Coalesce rapid updates — only persist latest snapshot
- Persist before broadcast (so store is populated for novelty detection)

### Phase 3: Filtering

Add outbound filtering to the broadcast pipeline.

- Define `LatticeFilter<V>` interface
- Add default filter to NodeServer configuration
- Filter applied after snapshot, before announce/broadcast
- Full (unfiltered) value persisted locally

### Phase 4: Per-Connection Filters + Replica Management

Add connection management with per-replica configuration.

- Define `ReplicaConfig` (address, mode, filter, reconnect policy)
- `addReplica()` / `removeReplica()` API
- Per-connection filter in broadcast loop
- Auto-reconnect with backoff
- Immediate root sync on connect

## Verification

```bash
# Unit tests
pushd C:/Users/mike_/git/convex && mvn test -pl convex-peer -Dtest=NodeServerTest

# Integration: multi-node lattice sync with persistence
pushd C:/Users/mike_/git/convex && mvn test -pl convex-peer -Dtest=NodeServerPersistenceTest

# DLFS integration
pushd C:/Users/mike_/git/convex && mvn test -pl convex-dlfs
```

Test scenarios:
- Persist + restart + restore: value survives restart
- Non-persistent store: no errors, value resets to zero on restart
- Filter: filtered value broadcast, full value persisted
- Rapid writes: only latest snapshot persisted (coalescing)
- Concurrent app writes during persist: no blocking, no corruption
- Multi-node: two NodeServers with Etch stores, sync, restart one, verify convergence
