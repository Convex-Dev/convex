# NodeServer Persistence Design

## Status Quo

NodeServer by default holds all lattice state in a `Root<V>` cursor backed by `AtomicReference<V>`. On
shutdown, everything is lost. Applications are required to define their own persistence / replication strategy
which is not ideal as it is a likely cause of bugs / security risks. 

The existing `Server` (Convex peer) has a complete persistence lifecycle:
`persistPeerData()` → `store.setRootData()` → Etch on disk → `Peer.restorePeer()` on restart.
NodeServer needs the equivalent, but adapted for lattice semantics and the needs of lattice
applications like DLFS which are not public by default.

## Design Goals

1. **Lattice apps update their own cursor in memory** — writes are instant, persist/sync
   happens asynchronously or on demand
2. **Hot push to trusted replicas** with novelty detection (delta encoding)
3. **Filtering before push** — exclude local/private data from outbound lattice values
4. **Works with any AStore** — `store.isPersistent()` determines if disk writes occur
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
  App writes ──────────►│  cursor: ACursor<V>           │
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
│  3. Cells.announce(filtered, handler)  ← collect novelty │
│  4. broadcast(filtered, novelty)       ← delta push      │
│  5. if config.persist:                                    │
│       Cells.persist(snapshot, store)   ← write to Etch   │
│       store.setRootData(snapshot)      ← anchor restore  │
└─────────────────────────────────────────────────────────┘
```

**Pipeline ordering rationale:** Announce the filtered value *before* persisting the full
snapshot. This ensures cells that are private (present in the full snapshot but absent from
the filtered version) are never marked as novelty — they never enter the announce tracking
at all. Shared cells (present in both filtered and full) will be announced during step 3
and then deduplicate when persisted in step 5 with no significant performance cost.

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

The lattice value is stored directly as the Etch root data — one NodeServer per store.
Applications that need multiple lattice regions use sub-cursors within a single root value.

Both restore and persist are **configuration options**, not purely determined by store type.
An operator may want a clean start even on a persistent EtchStore (e.g. clearing corrupted
state, testing fresh behaviour, or starting a new replication topology). Similarly, a node
may want to broadcast without persisting (e.g. a relay node, or during testing). Both flags
default to `true` — they only take effect when the store is also persistent.

```java
public void launch() {
    // 1. Restore from persistent store if configured
    if (config.restore && store.isPersistent()) {
        V restored = (V) store.getRootData();
        if (restored != null) {
            cursor.set(restored);
            log.info("Restored lattice value from store");
        }
    }

    // 2. Start network, propagator, etc.
    ...
}
```

### Running: Periodic + Triggered Persistence

Persistence is triggered by:
- **Value change** — cursor updates and incoming merges signal the pipeline
- **Timer** — periodic snapshots at configurable interval (default 30s)
- **Shutdown** — final persist before close

The pipeline coalesces rapid updates: if 100 writes happen in 50ms, only the final
snapshot is persisted. This is correct because lattice merge is idempotent.

### Shutdown: Final Persist

```java
public void close() {
    // 1. Stop accepting new connections and messages
    // 2. Final persist of current value
    if (config.persist && store.isPersistent()) {
        persistSnapshot(cursor.get());
    }
    // 3. Close propagator, network server, peer connections
}
```

### Store Compatibility

`AStore.isPersistent()` determines whether the store *can* persist. `config.persist` and
`config.restore` determine whether the server *does* persist and restore. All three are
independent:

| `isPersistent()` | `config.restore` | `config.persist` | Behaviour |
|-------------------|-------------------|-------------------|-----------|
| `true` | `true` | `true` | Full persist/restore cycle (normal operation) |
| `true` | `false` | `true` | Persist new state, but start fresh (clear start) |
| `true` | `true` | `false` | Restore existing state, but don't write updates (read-only replay) |
| `true` | `false` | `false` | Relay node — broadcast only, no disk I/O |
| `false` | `*` | `*` | In-memory only — restore/persist flags are no-ops |

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

Filtering happens **after snapshot, before announce**. The filtered value is announced and
broadcast first; the full value is persisted afterwards:

```
cursor.get()  →  snapshot  →  filter(snapshot)  →  announce + broadcast
                     │                                     │
                     └─────────────────────────────────────┘
                                                    then persist(snapshot)
```

This ordering is critical: cells that exist only in the full value (private data) are
**never announced as novelty** because they were never seen by `Cells.announce()`. They
are written to Etch during persist but remain invisible to the broadcast mechanism.

The **full unfiltered value** is persisted to local store (so private data survives
restart). Only the **filtered value** is announced and broadcast to peers. Shared cells
(present in both) deduplicate naturally — `Cells.persist()` is a no-op for cells already
tracked by announce.

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

The persistence pipeline integrates with the existing propagator. Announce happens before
persist to ensure private cells are never tracked as novelty:

```
Pipeline run:
  1. snapshot = cursor.get()
  2. filtered = filter(snapshot)
  3. announce(filtered, noveltyHandler)    ← collect novelty for filtered value only
  4. broadcast(filtered, novelty)          ← delta push (filtered)
  5. if config.persist:
       persist(snapshot, store)            ← NEW: write full value to Etch
       store.setRootData(snapshot)         ← anchor for restore
```

Steps 3-4 announce and broadcast the **filtered** value. Only cells in the filtered tree
are marked as novelty and transmitted. Step 5 then persists the **full** snapshot to Etch.
Cells already announced in step 3 deduplicate — the store recognises them as already
written, so the persist cost is dominated by private-only cells (which are typically a
small fraction).

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

```java
NodeServer<V> server = NodeServer.builder(lattice, store)
    .port(8765)
    .cursor(rootCursor)                    // ACursor<V>, default creates new Root<V>
    .filter(myFilter)                      // default outbound filter
    .persist(true)                         // write state to store (default: true)
    .restore(true)                         // restore from store on startup (default: true)
    .persistInterval(30_000)               // ms between periodic persists
    .build();
```

Key options:
- **`cursor`** — an `ACursor<V>` to use. If omitted, creates a `Root<V>` with the lattice
  zero value. Pass a `PathCursor` to attach this server at a sub-path of a larger tree.
- **`persist`** — whether to write state to the store. Defaults to `true`. Set to `false`
  for relay nodes or testing scenarios where only broadcast is needed.
- **`restore`** — whether to load existing data from the store on startup. Defaults to
  `true`. Set to `false` for a clean start even on a persistent store.
- **`filter`** — default `LatticeFilter<V>` for outbound replication. Can be overridden
  per-connection via `addReplica()`.
- **`persistInterval`** — milliseconds between periodic persistence runs. Set to `0` to
  disable periodic persistence (persist only on shutdown and explicit trigger).

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

## Hierarchical Cursor Sync

### Motivation

A single NodeServer may host a lattice tree with independently-syncable sub-regions.
Examples:
- **DLFS** — each drive is a sub-tree that could sync with a different set of peers
- **Database subsystem** — a SQL database occupies one branch of the lattice; it may
  replicate to database-specific peers that don't need the full tree
- **Multi-tenant** — each owner's data is a sub-tree with independent replication policy

The cursor hierarchy (`Root` → `PathCursor` → `DescendedLatticeCursor`) supports this
natively.

### Architecture

```
                  Root<V>  (full lattice tree)
                     │
         ┌───────────┼───────────┐
         │           │           │
    PathCursor    PathCursor   PathCursor
    (:fs)         (:db)        (:meta)
         │           │
    ┌────┴────┐      │
    │         │      │
 PathCursor  ...   NodeServer (DB-level sync)
 (owner,drive)       ↕ peers
    │
 DLFSLocal
 NodeServer (drive-level sync)
    ↕ peers
```

### How It Works

`PathCursor` delegates reads and writes to the parent cursor with an automatic path prefix.
All writes are atomic — `PathCursor.updateAndGet()` runs a CAS loop on the root
`AtomicReference`, so concurrent writes to different sub-paths compose correctly.

A `NodeServer` only requires an `ACursor<V>` — it does not assume it holds the root.
This means a NodeServer can be attached at any level of the tree:

```java
// Root-level NodeServer: persists and syncs the entire tree
NodeServer<V> rootServer = NodeServer.create(lattice, rootCursor, store);

// Drive-level NodeServer: syncs only one DLFS drive
ACursor<AVector<ACell>> driveCursor = rootCursor.path(Keyword.intern("fs"), ownerKey, driveName);
NodeServer<AVector<ACell>> driveServer = NodeServer.create(dlfsLattice, driveCursor, null);
// null store — persistence handled by root server
```

### Persistence Responsibility

**Only the root-level NodeServer should persist to store.** Sub-path NodeServers set
`store = null` (or use a non-persistent store) because their writes propagate atomically
to the root cursor, which the root server persists. Double-persisting would be redundant
and could cause ordering issues.

```
Write to drive cursor
    │
    ▼
PathCursor.updateAndGet()      ← atomic CAS on Root<V>
    │
    ▼
Root<V> now holds updated tree ← root NodeServer's persistence pipeline picks this up
    │
    ▼
Root server: snapshot → filter → announce → broadcast → persist
```

### Independent Replication

Sub-path NodeServers replicate independently at their own level:

- **Drive sync**: a DLFS drive syncs with backup peers at the drive granularity — peers
  receive only that drive's tree, not the full lattice
- **DB sync**: a database subsystem syncs with database-specific peers
- **Root sync**: the root server syncs the full tree with trusted infrastructure peers

Each NodeServer at each level has its own `LatticePropagator`, its own peer connections,
and its own filter configuration. Changes propagate upward through the cursor hierarchy
and downward through each server's broadcast.

### Merge Direction

When a sub-path NodeServer receives an update from a remote peer, it merges into its
cursor. Because the cursor is a `PathCursor`, the merge atomically updates the root value.
The root server's propagator detects the change and includes it in the next root-level
broadcast.

Conversely, when the root server receives an update that touches a sub-path, the sub-path
NodeServer sees the change (its `PathCursor.get()` reflects the new root value) and can
broadcast to its own peers.

This bidirectional propagation works because lattice merge is commutative and idempotent —
the same update arriving via two paths produces the same result.

## Convergence with CVM Peer Server

NodeServer and the CVM `Server` implement the same distributed systems pattern. The
table below maps equivalent mechanisms:

| Concern | Server (CVM Peer) | NodeServer (Lattice) |
|---------|-------------------|----------------------|
| **State holder** | `Peer` (Belief + State) | `Root<V>` cursor |
| **Persist** | `persistPeerData()` → `setRootData()` | `persistSnapshot()` → `setRootData()` |
| **Restore** | `Peer.restorePeer(store, kp, rootKey)` | `store.getRootData()` → `cursor.set()` |
| **Delta broadcast** | `BeliefPropagator` | `LatticePropagator` |
| **Novelty detection** | `Cells.announce()` + `Format.encodeDelta()` | Same |
| **Network layer** | `AServer` (Netty/NIO) | `AServer` (Netty) |
| **Peer connections** | `ConnectionManager` (`HashMap<AccountKey, Convex>`) | `Set<Convex>` (ConcurrentHashMap.newKeySet) |
| **Shutdown order** | Components → persist → close network | Same |

### What should converge

**Already shared:** `AStore`, `Cells`, `Format`, `Message`, `AServer`, `Ref`, delta
encoding. The novelty detection mechanism (`Cells.announce` → `Format.encodeDelta`) is
identical.

**Candidates for extraction:**

1. **Propagator base class** — `BeliefPropagator` and `LatticePropagator` both:
   - Run a background loop waiting on a trigger queue
   - Snapshot a value, announce to store, collect novelty
   - Encode delta, broadcast to peers
   - Periodically re-sync for divergence detection

   An `APropagator<T>` base class could factor out the loop, trigger queue, broadcast
   mechanics, and timing. Subclasses implement only message format and merge semantics.

2. **Persist/restore lifecycle** — Both servers do `Cells.persist(value, store)` +
   `store.setRootData(value)` on shutdown, and `store.getRootData()` on startup. This
   is a 10-line pattern, simple enough to share via a utility method rather than a base
   class.

3. **`AServer.setReceiveAction()`** — NodeServer currently casts to `NettyServer` to set
   the receive action. Adding `setReceiveAction(Consumer<Message>)` to `AServer` would
   eliminate this cast.

### What should stay separate

- **Server** manages CVM consensus (Beliefs, Orders, Blocks), transaction processing, and
  the CVM executor. These have no lattice equivalent.
- **NodeServer** manages lattice merge semantics, path-based cursors, and foreign value
  validation. These have no CVM equivalent.
- **Connection management** differs: Server authenticates peers via challenge-response and
  keys connections by `AccountKey`. NodeServer's lattice sync is simpler (private network,
  trusted connections). Forcing a common abstraction would over-complicate both.

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

## Implementation Phases

### Phase 1: Core Persistence

Add persist/restore to NodeServer using the existing `Server.persistPeerData()` pattern.

- Add `persistSnapshot(V value)` method — `Cells.persist()` + `store.setRootData()`
- Add restore logic in `launch()` — `config.restore && store.isPersistent()` → load
- Accept `ACursor<V>` in builder (support sub-path attachment)
- Add final persist in `close()`
- Use `store.isPersistent()` to gate disk operations

### Phase 2: Periodic + Triggered Persistence

Integrate persistence into the LatticePropagator loop.

- Add persist trigger alongside broadcast trigger
- Configurable persist interval (default 30s)
- Coalesce rapid updates — only persist latest snapshot
- Announce filtered value before persist (so private cells are never marked as novelty)

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

### Phase 5: Propagator Convergence

Extract common propagator base from `BeliefPropagator` and `LatticePropagator`.

- Define `APropagator<T>` with shared loop, trigger queue, broadcast mechanics
- Migrate `LatticePropagator` first (simpler, lower risk)
- Migrate `BeliefPropagator` second (higher stakes, more testing needed)
- Add `setReceiveAction()` to `AServer` interface

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
- Restore disabled (`config.restore = false`): persistent store ignored, fresh start
- Filter: filtered value broadcast, full value persisted; private cells never announced
- Rapid writes: only latest snapshot persisted (coalescing)
- Concurrent app writes during persist: no blocking, no corruption
- Multi-node: two NodeServers with Etch stores, sync, restart one, verify convergence
- Hierarchical sync: sub-path NodeServer syncs independently, writes propagate to root
- Sub-path isolation: sub-path server's peers receive only the sub-tree, not full lattice
