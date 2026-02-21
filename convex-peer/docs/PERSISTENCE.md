# NodeServer Persistence & Sync Design

NodeServer is responsible for providing a cursor to lattice apps and subsystems.
NodeServer manages a list of propagators that handle persistence and broadcast to peers.

## Core Principles

1. **NodeServer owns the cursor** — it provides an `ACursor<V>` that applications use freely
   for instant in-memory reads and writes. The cursor is the single source of truth.
2. **No automatic sync on cursor write** — apps write to the cursor without triggering
   any I/O. Sync is a separate, explicit action.
3. **Apps trigger sync when ready** — `nodeServer.sync()` signals that the current cursor
   state should be propagated (persisted and/or broadcast).
4. **Automatic sync is configurable policy** — periodic sync, on-incoming-merge, or
   manual-only. Controlled by NodeServer configuration.
5. **Propagators handle ALL output** — persistence IS propagation (to disk instead of to
   peers). Each propagator owns its own store, filter, and peer connections.
   NodeServer has no store of its own — `sync()` just triggers propagators.
6. **Everything is async and lattice-native** — `sync()` is non-blocking. All handoffs
   between app, NodeServer, and propagators are either non-blocking queue offers or
   atomic lattice merges. Lattice properties (commutative, associative, idempotent)
   make all interleavings safe — no locks needed.
7. **Store-backed refs via merge callback** — the primary propagator calls a merge
   callback after announce, which merges the store-backed value into the cursor.
   This replaces in-memory refs with soft references, allowing GC to reclaim cell data
   that can be reloaded from the store on demand. Without this, OOM.
8. **A NodeServer with no propagator is purely in-memory** — `sync()` is a no-op. The
   cursor works fine but nothing is persisted or broadcast.
9. **Shutdown guarantees persistence, not broadcast** — `close()` ensures each propagator
   persists its final state. Broadcast to peers is best-effort during operation.

## Architecture

```
                     ┌──────────────────────────────────────────┐
                     │              NodeServer                   │
                     │                                           │
 App writes ────────►│  cursor: ACursor<V>     (in-memory)      │
 (instant, no I/O)   │   (AtomicReference — all writes atomic)  │
                     │                                           │
                     │  propagators: [LatticePropagator...]      │
                     │                                           │
 App calls ─────────►│  sync()   (non-blocking, returns fast)   │
 (when ready)        │    └──► trigger ALL propagators           │
                     │         (non-blocking queue offer)        │
                     │                                           │
                     │  Each propagator's background thread:     │
                     │    filter → announce → setRootData        │
                     │    → mergeCallback(persisted)  [primary]  │
 Store-backed  ◄─────│    → broadcast(delta)          [network]  │
 refs merged         │                                           │
 into cursor         │                                           │
                     │  Incoming merge:                          │
 From peers ────────►│    cursor.path(path).merge(value)         │
                     │    └──► propagator handles broadcast      │
                     │                                           │
                     │  close()                                  │
                     │    └──► triggerAndClose each propagator    │
                     └──────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **Cursor** (`ACursor<V>`) | In-memory state. Apps read/write freely. Thread-safe via AtomicReference. |
| **NodeServer** | Orchestration. Owns cursor + propagator list. `sync()` triggers all propagators. |
| **Propagator** (`LatticePropagator`) | Owns store, filter, peers, background thread. Optional merge callback. |

### Propagator Roles

Propagators are held in a list. **Index 0 is always the primary propagator** (if present).
All propagators are triggered the same way (non-blocking queue offer). What makes the
primary special is that NodeServer sets a **merge callback** on it:

```java
// NodeServer wires up the merge callback on the primary propagator
propagators.get(0).setMergeCallback(persisted ->
    cursor.updateAndGet(current -> lattice.merge(persisted, current))
);
```

The propagator calls this after announce. It has no knowledge of cursors or lattices —
it just calls `Consumer<V>` with the store-backed value. NodeServer owns the merge logic.

| Index | Role | Filter | Peers | Merge Callback | Store | Purpose |
|-------|------|--------|-------|----------------|-------|---------|
| 0 | **Primary** | None | None (or local) | Yes (set by NodeServer) | EtchStore | Persistence + restore. Store-backs cursor. |
| 1+ | **Public** | Yes (strip private) | Untrusted | No | Own store | Public data broadcast. Security boundary. |
| 1+ | **Backup** | None | Trusted | No | Own store | Full replication to trusted peers. |

The **primary propagator** is the app-level restore source. It gets the full unfiltered
value, announces all cells to its store, and sets root data. On startup, NodeServer
restores the cursor from `propagators[0].getStore().getRootData()`.

A node that only wants disk persistence (no network) has a single primary propagator
with no peers. A node with no propagators is purely in-memory.

### Why Propagators Need Their Own Stores

`Cells.announce(value, noveltyHandler, store)` does two things simultaneously:
1. **Writes cells to the store** — so it can detect novelty on subsequent announces
2. **Collects novel cells** — cells not previously in the store, for delta encoding

The store accumulates all announced cells. This is the **security boundary**: when peers
send `DATA_REQUEST` messages, they can only resolve cells that exist in the propagator's
store. A public propagator that only announces filtered values will never have private
cells in its store — so peers cannot request them.

```
Propagator[public]:
  announce(filter(snapshot)) → store contains only public cells
  peers can DATA_REQUEST → only public cells resolvable

Propagator[backup]:
  announce(snapshot) → store contains all cells
  peers can DATA_REQUEST → all cells resolvable
```

Two propagators always need independent stores. A single propagator that serves as
both primary (persistence) and backup (full broadcast) can use one store for both.

## Value Flow

### App Write (No I/O)

```java
// App writes directly to cursor — instant, in-memory
node.getCursor().set(newValue, :myKey);
// Nothing else happens. No broadcast, no persistence, no I/O.
```

### Explicit Sync

```java
// App decides it's time to sync
node.sync();  // returns immediately — non-blocking
```

`sync()` is trivial — it just triggers all propagators:

```java
public void sync() {
    V snapshot = cursor.get();
    for (LatticePropagator p : propagators) {
        p.triggerBroadcast(snapshot);  // non-blocking queue offer
    }
}
```

No I/O, no blocking. Each propagator's background thread picks up the value and does
its work independently: filter, announce, setRootData, merge callback, broadcast.

The **merge callback** on the primary propagator handles feeding store-backed refs
back into the cursor. After `Cells.announce()` writes cells to the store, the value's
refs become soft references. The callback merges this into the cursor atomically:

```java
// Set by NodeServer on propagators[0] during setup
propagator.setMergeCallback(persisted ->
    cursor.updateAndGet(current -> lattice.merge(persisted, current))
);
```

This merge safely combines:
- **Persisted value** — store-backed soft refs for all cells at persist time
- **Current cursor** — any new app writes that happened concurrently

For identical cells, merge returns the persisted version (store-backed). For new writes,
merge incorporates them (in-memory refs, store-backed on next sync). Lattice merge is
commutative and idempotent, so all interleavings are safe — no data loss, no locks.

**Why merge, not set?** Apps may write to the cursor concurrently during persist. A
naive `cursor.set(persisted)` would lose those writes. The lattice merge preserves both
the store-backed refs AND any concurrent app writes.

**Why store-back is critical:** Without replacing in-memory refs with soft references,
the cursor holds strong refs to all cells in the heap. As the lattice grows, this causes
OOM. Soft refs allow the GC to reclaim cell data — it can be reloaded from the store on
demand.

### Incoming Merge

When a peer sends a `LATTICE_VALUE` message:
1. NodeServer navigates to the target path via `cursor.path(path)`
2. Merges the received value via `target.merge(value)` — the cursor chain handles
   sub-lattice resolution, signing boundaries, and null-lattice bubble-up automatically
3. Broadcasting is the propagator's responsibility, not NodeServer's

NodeServer also supports explicit pull via `pull()` (query all connected peers)
or `pull(Convex)` (query a specific peer). Pull sends a `LATTICE_QUERY`, receives
the peer's current value, and merges it into the cursor.

### Shutdown

Shutdown is the one place where blocking is acceptable — we must guarantee persistence.

```java
public void close() {
    running = false;

    // Final sync + wait for all propagators to drain and stop
    V snapshot = cursor.get();
    for (LatticePropagator p : propagators) {
        p.triggerAndClose(snapshot);  // trigger, drain queue, stop thread
    }
    // Primary propagator's merge callback fires during drain,
    // so cursor has store-backed refs after close.

    if (networkServer != null) {
        networkServer.close();
    }
}
```

Each propagator's `triggerAndClose()` ensures the queued value is processed (including
the merge callback for primary) before the thread stops. This is the only blocking
handoff in the system.

## Persistence Lifecycle

### Startup: Restore

```java
public void launch() {
    // Restore from propagators[0] (primary) store
    if (!propagators.isEmpty()) {
        ACell restored = propagators.get(0).getStore().getRootData();
        if (restored != null) {
            cursor.set((V) restored);
        }
    }

    // Start propagators, network server, etc.
    ...
}
```

The primary propagator's store holds the full unfiltered value as root data.
On startup, NodeServer reads this to populate the cursor. The restored value
already has store-backed soft refs — no separate persist step needed.

### Running: Sync Triggers

Sync can be triggered by:
- **App explicitly** — `node.sync()` after a batch of writes
- **Incoming merge** — if autoSync policy is enabled
- **Periodic timer** — configurable interval (default 30s), as safety net
- **Shutdown** — final persist in each propagator's `close()`

The periodic timer ensures eventual persistence even without explicit sync calls.
It is a safety net, not the primary mechanism.

### Node Configurations

| Primary propagator | Network propagators | Behaviour |
|--------------------|--------------------|---------  |
| EtchStore, no peers | Public + backup with peers | Full node: persist + broadcast |
| EtchStore, no peers | None | Local only: persist, no broadcast |
| None | Public + backup with peers | Relay: broadcast, no local persist |
| None | None | In-memory only: sync() is a no-op |

## Propagator Architecture

### Store Separation

```
Propagator[primary] store    Propagator[public] store     Propagator[backup] store
(no filter, no peers)        (filtered announce)           (full announce)
┌──────────────┐             ┌──────────────┐              ┌──────────────┐
│ ALL cells    │             │ Public cells │              │ ALL cells    │
│ setRootData()│             │ (from filter)│              │ (no filter)  │
│ restore src  │             │ DATA_REQUEST │              │ DATA_REQUEST │
│              │             │ boundary     │              │ boundary     │
└──────────────┘             └──────────────┘              └──────────────┘
```

Each propagator's store receives `Cells.announce()` for delta tracking and
`store.setRootData()` for restore. Stores also serve as security boundaries
for `DATA_REQUEST` from peers.

### Propagator Internals

A `LatticePropagator` owns:
- A `LatticeFilter` (optional) — applied before announce
- An `AStore` for delta tracking, persistence, and peer data resolution
- A `LatticeConnectionManager` with its own set of peer connections
- A `Consumer<V> mergeCallback` (optional, default null) — called after announce
- A background thread processing broadcast triggers

When `triggerBroadcast(value)` is called:
1. Value queued (LatestUpdateQueue coalesces rapid triggers)
2. Background thread picks up latest value
3. Apply filter: `filtered = (filter != null) ? filter.apply(value) : value`
4. `Cells.announce(filtered, noveltyHandler, store)` — writes to store, collects novelty
5. `store.setRootData(filtered)` — anchor for restore
6. If `mergeCallback != null`: `mergeCallback.accept(filtered)` — feed store-backed value back
7. `Format.encodeDelta(novelty)` — encode only novel cells
8. Send `LATTICE_VALUE` message to connected peers

Steps 7–8 are skipped if the propagator has no peers (pure persistence propagator).
Step 6 is only active on the primary propagator (NodeServer sets the callback).

The propagator has no knowledge of cursors or lattices — it just calls a `Consumer<V>`
with the store-backed value. NodeServer owns the merge logic via the callback.

### Delta Tracking via Announce

`Cells.announce()` is the key mechanism for efficient delta encoding:

```
First announce of value V1:
  store: empty → {cell_A, cell_B, cell_C}
  novelty: [cell_A, cell_B, cell_C]  ← all cells are new
  broadcast: full delta

Second announce of value V2 (shares cells with V1):
  store: {cell_A, cell_B, cell_C} → {cell_A, cell_B, cell_C, cell_D}
  novelty: [cell_D]  ← only the new cell
  broadcast: minimal delta
```

This is why the propagator needs a persistent store — the announce tracking state
must survive across broadcast cycles. A MemoryStore works fine (in-memory tracking
without disk I/O). An EtchStore adds disk durability for the tracking state.

## Sync Protocol

Delta-based propagation is efficient but **not sufficient** for a robust decentralised
network. Nodes can lose synchronisation due to:

- **Offline/Restart** — node misses broadcasts while down
- **Network partition** — temporary split causes divergence
- **Packet loss** — deltas never arrive
- **New node joining** — no history, needs full state bootstrap

### Key Insight: Avoid Full Value Pushes

Push only the root cell hash periodically. Let the receiver pull what's missing.

- Lattice forks are cheap (immutable data structures)
- `Convex.acquire()` already handles efficient missing data retrieval
- Speculative merge in a forked cursor detects exactly what's needed
- Only pull data that's actually missing (not redundant data)

### Three-Tier Strategy

#### Tier 1: Delta Push (Fast Path)

- **When**: On every `sync()` call
- **How**: `Cells.announce()` + `Format.encodeDelta()` → `LATTICE_VALUE` message
- **Frequency**: On-demand (app calls `sync()`)
- **Bandwidth**: Very low (only novel cells)
- **Reliability**: Best-effort — can lose messages
- **Recovery**: Tier 2 catches missed deltas

Already implemented in `LatticePropagator.broadcast()`.

#### Tier 2: Root-Only Push (Self-Healing)

- **When**: Every 10–30 seconds (configurable)
- **How**: Send ONLY the root cell (no children) to all peers
- **Bandwidth**: Minimal (~50–200 bytes)
- **Protocol**:
  1. Propagator broadcasts root cell hash to its peers
  2. Receiver attempts speculative merge in forked cursor
  3. If `MissingDataException` → pull only missing cells via `DATA_REQUEST`
  4. Complete merge after acquisition

The receiver knows the peer's latest state but only pulls what's actually missing.

#### Tier 3: Speculative Fork + Acquire (Automatic)

- **When**: Whenever incoming message references unknown cells
- **How**: Fork cursor, attempt merge, catch `MissingDataException`, acquire, retry
- **Protocol**:
  1. Fork current cursor (cheap, copy-on-write semantics)
  2. Attempt merge in fork
  3. Catch `MissingDataException`
  4. Use `Acquiror` to pull missing cells from sender
  5. Retry merge after acquisition
  6. Commit successful merge to main cursor
- **Bandwidth**: Only missing data (tree difference)
- **Reliability**: Very high (guaranteed complete after pull)

```java
// Navigate to the target path and merge — the cursor handles everything
ALatticeCursor<ACell> target = cursor.path(path);
target.merge(value);  // cursor handles lattice merge, path write-back, null-lattice bubble-up
```

### Tier Summary

| Tier | Mechanism | Frequency | Bandwidth |
|------|-----------|-----------|-----------|
| 1 | Delta push (`Cells.announce` + `Format.encodeDelta`) | On sync | Low (novel cells only) |
| 2 | Root-only push (root cell hash) | Periodic (30s) | Minimal (~100 bytes) |
| 3 | Speculative fork + acquire | On incoming merge | On-demand (missing cells only) |

### Bandwidth Comparison

**Old approach — full value push:**
```
Delta (frequent):     [Novel Cells]               ← 1-10 KB
Full sync (30s):      [Entire Lattice Tree]       ← 100 KB - 10 MB   WASTEFUL
```

**New approach — root push + pull-on-demand:**
```
Delta (frequent):     [Novel Cells]               ← 1-10 KB
Root sync (30s):      [Root Cell Only]            ← 50-200 bytes     MINIMAL
Pull (on-demand):     [Missing Cells Only]        ← 0-100 KB (only if needed)
```

95–99% bandwidth reduction when no data is missing.

### Recovery Scenarios

**Normal operation (no deltas lost):**
```
Node A: [Delta Broadcast] ──► Node B: [Merge Success]
        [Root Sync]        ──►        [Same Hash, Skip]
```

**Some deltas lost:**
```
Node A: [Delta 1] ──X (lost)     Node B: [Has old value]
        [Delta 2] ──X (lost)
        [Root Sync] ──────────►          [Different Hash!]
                                          └──► Pull Missing: Delta 1 + 2
                                          └──► Merge Success
```
Recovery time: ~1–5 seconds after root sync.

**Node offline/restart:**
```
Node B: [Offline ... Restart]
Node A: [Root Sync] ──────────►  [Different Hash!]
                                  └──► Pull ALL Missing
                                  └──► Fully Synced
```
Recovery time: ~10–30 seconds after restart.

## Concurrency Model

All handoffs between app, NodeServer, and propagators are non-blocking:

| From | To | Mechanism | Blocking? |
|------|----|-----------|-----------|
| App | Cursor | `AtomicReference.updateAndGet()` | No |
| NodeServer | Propagators | `LatestUpdateQueue.offer()` via `sync()` | No |
| Propagator | Store | `Cells.announce()` + `setRootData()` on own thread | Own thread only |
| Propagator | Cursor | `mergeCallback` → `cursor.updateAndGet(merge)` | No |
| Propagator | Peers | `broadcast(delta)` on own thread | Own thread only |
| Peers | Cursor | `cursor.updateAndGet(merge)` | No |
| Shutdown | Propagators | `triggerAndClose()` — wait for drain | Yes (intentional) |

**Why this is safe.** Every concurrent operation on the cursor is a lattice merge via
`AtomicReference.updateAndGet()`:
- App write: `cursor.updateAndGet(current -> RT.assocIn(current, key, value))`
- Store-back: `cursor.updateAndGet(current -> lattice.merge(persisted, current))`
- Peer merge: `cursor.updateAndGet(current -> lattice.merge(current, received))`

Because lattice merge is **commutative, associative, and idempotent**, the order these
execute doesn't matter. Any interleaving produces the same result. No locks needed.

The `LatestUpdateQueue` coalescing is also safe: if V2 is triggered before V1 is
processed, V2 replaces V1. Since V2 >= V1 (lattice monotonicity), V1 is subsumed.

## Snapshot Semantics

Lattice data structures are immutable trees of `ACell` values. A "snapshot" is simply
reading the `AtomicReference` — O(1), zero-copy. Safe to hand to any thread.

```java
V snapshot = cursor.get();  // O(1), immutable, safe to share
```

This is the foundation for non-blocking sync: app writes and the sync pipeline never
contend on mutable state. If multiple writes happen between syncs, intermediate values
are naturally skipped — only the latest snapshot matters (lattice idempotence).

## Filtering

### Motivation

A node's lattice state may contain data that should not leave the node:
- Private DLFS drives
- Draft/staging data
- Node-local metadata

### Filter Ownership

Each propagator owns its own filter. NodeServer passes the full snapshot to every
propagator — the propagator applies its filter internally before announcing.

```
NodeServer.sync():                     Propagator background threads:
  snapshot = cursor.get()
  │                                    propagators[0] (primary):
  ├──► trigger(snapshot) ──queue──►      announce(snapshot)
  │                                      setRootData(snapshot)
  │                                      mergeCallback(persisted) ──► cursor
  │
  │                                    propagators[1] (public):
  ├──► trigger(snapshot) ──queue──►      filter(snapshot)
  │                                      announce(filtered)
  │                                      setRootData(filtered)
  │                                      broadcast(delta)
  │
  └──► trigger(snapshot) ──queue──►    propagators[2] (backup):
                                         announce(snapshot)
  returns immediately                    setRootData(snapshot)
                                         broadcast(delta)
```

Private cells are never announced to the public propagator's store, so they never
enter its security boundary and can never be resolved by peers. The primary and backup
propagators (no filter) have all cells — but only trusted peers connect to backup,
and primary has no peers at all.

### Filter Interface

```java
@FunctionalInterface
public interface LatticeFilter<V extends ACell> {
    V filter(V value);
    // Must be idempotent: filter(filter(v)) == filter(v)
}
```

## Configuration

```java
NodeServer<V> node = new NodeServer<>(lattice, cursor, config);

// propagators[0] = primary (persistence), [1+] = broadcast
node.addPropagator(primaryPropagator);   // index 0
node.addPropagator(publicPropagator);    // index 1

// NodeServer sets merge callback on primary during setup:
// propagators.get(0).setMergeCallback(persisted ->
//     cursor.updateAndGet(current -> lattice.merge(persisted, current)));
```

NodeConfig options:
- **`port`** — network port (null = auto, negative = local-only / no network)
- **`syncInterval`** — ms between periodic auto-syncs (default: 30000, 0 = manual only)

Sync tuning (LatticePropagator):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `broadcastInterval` | 100ms | Minimum delay between delta broadcasts |
| `rebroadcastInterval` | 1000ms | Periodic delta re-push |
| `rootSyncInterval` | 30000ms | Periodic root-only sync (Tier 2) |

### Trade-offs by Use Case

| Use Case | Delta Interval | Root Sync Interval | Notes |
|----------|----------------|-------------------|-------|
| High-speed trading | 10ms | 5s | Low latency, higher overhead |
| Standard operations | 100ms | 30s | Balanced (recommended) |
| Low-bandwidth IoT | 1000ms | 300s | Conserve bandwidth |
| Eventually consistent | 500ms | 120s | Relaxed consistency |

## Interaction with Lattice Apps

Apps only interact with the cursor. NodeServer handles everything else.

### DLFS Example

```java
NodeServer<V> node = ...;

// App writes directly to cursor (instant, in-memory)
DLFSLocal drive = new DLFSLocal(provider, uri, node.getCursor().path(ownerKey, driveName));
Files.write(drive.getPath("/readme.txt"), "hello".getBytes());

// App triggers sync when ready
node.sync();
```

### Custom App Pattern

```java
// Batch writes
node.getCursor().set(value1, key1);
node.getCursor().set(value2, key2);

// Single sync propagates the latest state
node.sync();
```

## Hierarchical Cursor Sync

A single NodeServer may host a lattice tree with independently-syncable sub-regions.

```
              Root<V>  (full lattice tree)
                 │
     ┌───────────┼───────────┐
     │           │           │
cursor.path  cursor.path  cursor.path
(:fs)         (:kv)        (:local)
     │
 DLFSLocal
```

**Only the root-level NodeServer propagates.** Sub-path cursors write atomically to the
root `AtomicReference`. The root NodeServer's `sync()` propagates the full tree.

Sub-path NodeServers can replicate sub-trees independently to different peer sets,
each with their own propagator and store.

## Implementation Phases

### Phase 1: Core Persistence ✓

- Propagator-based persistence: `Cells.announce()` + `store.setRootData()`
- Restore in `launch()` from primary propagator's store
- Final persist in propagator `close()`

### Phase 2: Explicit Sync API ✓

- `sync()` triggers propagators — NodeServer has no store of its own
- Propagators own filter, store, peers — all output goes through them
- Incoming merges optionally call `sync()` based on autoSync config
- Periodic auto-sync as configurable safety net

### Phase 3: Speculative Fork + Acquire ✓

- Fork cursor before merge to detect missing data safely
- Use `Acquiror` to pull missing cells from sender
- Retry merge after acquisition

### Phase 4: Root-Only Periodic Sync ✓

- Propagator broadcasts root cell hash to peers on timer
- Peers detect divergence from hash mismatch
- Trigger Tier 3 acquire for missing data

### Phase 5: Filtering + Security Tiers

- `LatticeFilter<V>` interface ✓ (interface exists, not yet integrated into propagator)
- Each propagator owns its own filter, applied internally before announce
- Multiple propagators with separate stores
- Per-propagator filter and peer set
- Public / trusted / backup tiers

### Phase 6: Propagator Convergence

- Extract `APropagator<T>` base from `BeliefPropagator` and `LatticePropagator`
- Shared: background loop, trigger queue, delta encoding, broadcast
- Separate: message format, merge semantics

## Testing Strategy

### Unit Tests

1. **Delta loss simulation** — drop random broadcast messages, verify full sync recovers
2. **Network partition** — split network, update both sides, verify convergence after reunion
3. **New node join** — add fresh node, verify it syncs to current state
4. **Missing data** — send delta referencing non-existent cells, verify acquisition

### Integration Tests

1. **3-node network** — verify all nodes converge after updates
2. **Rolling restart** — restart nodes one-by-one, verify no data loss
3. **High-frequency updates** — stress test with rapid changes
4. **Primary/backup restore** — persist, restart, verify data survives

## Verification

```bash
pushd C:/Users/mike_/git/convex && mvn test -pl convex-peer -Dtest=NodeServerPersistenceTest
pushd C:/Users/mike_/git/convex && mvn test -pl convex-peer -Dtest=NodeServerTest
pushd C:/Users/mike_/git/convex && mvn test -pl convex-dlfs
```
