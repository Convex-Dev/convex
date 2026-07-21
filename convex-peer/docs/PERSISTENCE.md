# NodeServer Persistence & Sync Design

NodeServer is responsible for providing a cursor to lattice apps and subsystems.
NodeServer manages a list of propagators that handle persistence and broadcast to peers.

## Core Principles

1. **NodeServer owns the cursor** — it provides an `ALatticeCursor<V>` that applications use
   freely for instant in-memory reads and writes. The cursor is the single source of truth.
2. **No automatic sync on cursor write** — apps write to the cursor without triggering
   any I/O. Sync is a separate, explicit action.
3. **Apps trigger sync when ready** — `cursor.sync()` guarantees the cursor is synced
   with the level below (parent cursor or, at root, the sync callback). Sync propagates
   up the cursor hierarchy to the root. May block if the sync callback does blocking work
   (e.g. synchronous persistence).
4. **Automatic sync is configurable policy** — periodic sync, on-incoming-merge, or
   manual-only. Controlled by NodeServer configuration.
5. **Propagators handle ALL output** — persistence IS propagation (to disk instead of to
   peers). Each propagator owns its own store, filter, and peer connections.
   NodeServer hooks a sync callback on the cursor that triggers propagators.
6. **Lattice-native concurrency** — cursor state transitions are atomic and root sync
   callbacks are serialised. Many lattice merges are commutative, associative and
   idempotent, but directional tie-breaks exist. Every handoff must preserve the
   documented `own`/`other` roles; correctness does not rely on arbitrary merge
   reordering.
7. **Store-backed refs via synchronous sync result** — the primary propagator returns
   its announced, store-backed value from the root sync callback. The root cursor
   installs it with CAS, or merges it behind a concurrent local write. This allows GC
   to reclaim cell data that can be reloaded from the store on demand.
8. **A NodeServer with no propagator is purely in-memory** — `cursor.sync()` is a no-op.
   The cursor works fine but nothing is persisted or broadcast.
9. **Shutdown guarantees persistence, not broadcast** — `close()` ensures each propagator
   persists its final state. Broadcast to peers is best-effort during operation.

## Architecture

```
                     ┌──────────────────────────────────────────┐
                     │              NodeServer                   │
                     │                                           │
 App writes ────────►│  cursor: RootLatticeCursor<V> (in-memory) │
 (instant, no I/O)   │   (AtomicReference — all writes atomic)  │
                     │                                           │
                     │  propagators: [LatticePropagator...]      │
                     │                                           │
 App calls ─────────►│  cursor.sync()                            │
 (when ready)        │    ├──► primary processes synchronously  │
                     │    └──► secondary queue offers           │
                     │                                           │
                     │  Propagator pipeline:                     │
                     │    filter → announce → setRootData        │
 Store-backed  ◄─────│    → return announced value    [primary]  │
                     │    → broadcast(delta)          [network]  │
 refs merged         │                                           │
 into cursor         │                                           │
                     │  Incoming merge:                          │
 From peers ────────►│    cursor.path(path).merge(value)         │
                     │    └──► cursor.sync()                      │
                     │                                           │
                     │  close()                                  │
                     │    └──► triggerAndClose each propagator    │
                     └──────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **Cursor** (`RootLatticeCursor<V>`) | In-memory state. Apps read/write freely. `sync()` triggers propagators via callback. Thread-safe via AtomicReference. |
| **NodeServer** | Orchestration. Owns cursor + propagator list. Hooks sync callback on cursor. |
| **Propagator** (`LatticePropagator`) | Owns store, peers and background propagation; pull operations acquire store-backed values without merging them. |

### Propagator Roles

Propagators are held in a list. **Index 0 is always the primary propagator** (if present).
NodeServer processes the primary synchronously from the root sync callback and queues
secondary propagators asynchronously. Explicit pulls follow the same root-owned path:

```java
ACell acquired = propagators.get(0).pull(peer).join(); // acquisition only
cursor.merge(acquired);                               // authoritative root merge
cursor.sync();                                        // persist and re-propagate merged root
```

The propagator has no knowledge of cursors or lattices. It returns the store-backed
value to NodeServer, which owns both the merge and the subsequent sync boundary.

| Index | Role | Filter | Peers | Store | Purpose |
|-------|------|--------|-------|-------|---------|
| 0 | **Primary** | None | None (or local) | EtchStore | Synchronous persistence, restore and pull acquisition. Store-backs cursor. |
| 1+ | **Public** | Yes (strip private) | Untrusted | Own store | Public data broadcast. Security boundary. |
| 1+ | **Backup** | None | Trusted | Own store | Full replication to trusted peers. |

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
cursor.sync();  // returns after the primary persistence pipeline completes
```

`sync()` is the synchronous logical-checkpoint boundary. A successful return confirms
that the primary store accepted the root update; it does not imply a physical Etch
`flush()`, which is a separate operator policy. A primary announce or root-write
failure is reported to this caller as `StoreException`. The in-memory cursor is not
rolled back, and NodeServer does not choose a recovery policy. A failed call provides
no root-publication confirmation, so the operator must treat the persisted root as
unconfirmed.

NodeServer hooks a sync callback on the `RootLatticeCursor` at construction time.
When `cursor.sync()` is called, the callback processes the primary propagator on the
calling thread and queues asynchronous fan-out to the secondary propagators:

```java
// In NodeServer constructor:
cursor.onSync(value -> {
    ACell announced = propagators.get(0).processSnapshot(value);
    for (int i = 1; i < propagators.size(); i++) {
        propagators.get(i).triggerBroadcast(value);
    }
    return announced;
});
```

The primary announce, persistence and broadcast pipeline completes before sync returns.
Each secondary propagator processes its queued value independently.

The callback returns the primary propagator's announced value to
`RootLatticeCursor.sync()`. After `Cells.announce()` writes cells to the store, the
value's refs become soft references. Root sync installs the result with CAS:

```java
V current = get();
V persisted = syncCallback.apply(current);
if (!compareAndSet(current, persisted)) merge(persisted);
```

This merge safely combines:
- **Persisted value** — store-backed soft refs for all cells at persist time
- **Current cursor** — any new app writes that happened concurrently

For identical cells, sync converges on the persisted version (store-backed). If the
cursor changed while persistence was running, root sync merges the current value as
`own` with the persisted snapshot as `other`. This ordering is deliberate: an
equal-priority concurrent local edit must not be reverted by the older snapshot.

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
3. Calls `cursor.sync()` — this synchronously commits to the primary store and queues
   secondary propagation

`LATTICE_VALUE` is fire-and-forget, so there is no application sync caller for a
durability exception. NodeServer contains and logs the exception at the inbound-message
boundary. The accepted merge remains in memory, durability is unconfirmed, and the
node stays running for operator-directed recovery.

NodeServer also supports explicit pull via `pull()` (query all connected peers)
or `pull(Convex)` (query a specific peer). Pull sends a `LATTICE_QUERY`, receives
the peer's current value, acquires its cells into the primary store, and returns it
to NodeServer. NodeServer merges through the root cursor and calls `cursor.sync()`;
the raw peer value is never independently persisted or re-propagated.

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

	// Seed the store-backed announced view before opening the listener
	ACell announced = propagators.get(0).processSnapshot(cursor.get());
	cursor.set((V) announced);

    // Start propagators, network server, etc.
    ...
}
```

The primary propagator's store holds the full unfiltered value as root data.
On startup, NodeServer restores this into the cursor, then processes the current
snapshot before opening the listener. Fresh and restored nodes can therefore answer
`LATTICE_QUERY` immediately, with store-backed refs and a recovery root established
when persistence is enabled. No application-side initial `sync()` is required.

For a publicly advertised node, launch is not complete until its signed NodeInfo has
passed the same synchronous checkpoint. If that checkpoint fails after the listener
has opened, NodeServer closes the listener, dispatcher, propagators and connection
managers before rethrowing the original error. `launch()` therefore either returns a
running node or throws with the node stopped; the same instance may be retried after
the store problem is resolved.

### Running: Sync Triggers

Sync can be triggered by:
- **App explicitly** — `cursor.sync()` after a batch of writes
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
| None | None | In-memory only: cursor.sync() is a no-op |

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
- A background thread processing broadcast triggers

When `triggerBroadcast(value)` is called:
1. Value queued (LatestUpdateQueue coalesces rapid triggers)
2. Background thread picks up latest value
3. Apply filter: `filtered = (filter != null) ? filter.apply(value) : value`
4. `Cells.announce(filtered, noveltyHandler, store)` — writes to store, collects novelty
5. `store.setRootData(filtered)` — anchor for restore
6. `Format.encodeDelta(novelty)` — encode only novel cells
7. Send `LATTICE_VALUE` message to connected peers

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

Ordinary cursor reads and writes remain lock-free. Primary persistence during sync is
intentionally synchronous; secondary propagation remains asynchronous:

| From | To | Mechanism | Blocking? |
|------|----|-----------|-----------|
| App | Cursor | `AtomicReference.updateAndGet()` | No |
| Cursor sync callback | Primary propagator | `processSnapshot()` on caller thread | Yes (intentional) |
| Cursor sync callback | Secondary propagators | `LatestUpdateQueue.offer()` | No |
| Propagator | Store | `Cells.announce()` + `setRootData()`, serialised by `writeLock` | Pipeline owner |
| Primary result | Cursor | CAS, then merge on a concurrent write | No |
| Pull callback | Cursor | `cursor.updateAndGet(merge)` | No |
| Propagator | Peers | `broadcast(delta)` on own thread | Own thread only |
| Peers | Cursor | `cursor.updateAndGet(merge)` | No |
| Shutdown | Propagators | `triggerAndClose()` — wait for drain | Yes (intentional) |

**Why this is safe.** Cursor changes are atomic, and each path preserves its required
argument roles:
- App write: `cursor.updateAndGet(current -> RT.assocIn(current, key, value))`
- Concurrent store-back reconciliation: `lattice.merge(current, persisted)`
- Peer merge: `cursor.updateAndGet(current -> lattice.merge(current, received))`

The first argument is the established local value and wins a directional unresolved
tie. Root sync callbacks are serialised, while `AtomicReference` CAS/update operations
make reconciliation indivisible with respect to concurrent cursor writes. Do not swap
merge arguments merely because a particular child lattice happens to be commutative.

The `LatestUpdateQueue` may coalesce snapshots only when they belong to the same
monotonic local update sequence, so the later snapshot subsumes the earlier one.
Commutativity alone would not justify reordering or folding directional candidates.

## Snapshot Semantics

Lattice data structures are immutable trees of `ACell` values. A "snapshot" is simply
reading the `AtomicReference` — O(1), zero-copy. Safe to hand to any thread.

```java
V snapshot = cursor.get();  // O(1), immutable, safe to share
```

This makes snapshot capture cheap and lets ordinary app reads and writes continue while
the primary pipeline processes an immutable value. Concurrent writes are reconciled
atomically when the sync result returns.

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
cursor.sync():                         Propagator processing:
  sync callback(value)
  │                                    propagators[0] (primary):
  ├──► processSnapshot(value) ─────────► announce(value)
  │                                      setRootData(value)
  │◄──────────────────────────────────── return announced value
  │
  │                                    propagators[1] (public):
  ├──► trigger(value) ────queue──►       filter(value)
  │                                      announce(filtered)
  │                                      setRootData(filtered)
  │                                      broadcast(delta)
  │
  └──► trigger(value) ────queue──►     propagators[2] (backup):
                                         announce(value)
  returns after primary                   setRootData(value)
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
NodeServer<V> node = new NodeServer<>(lattice, store, config);
// or: new NodeServer<>(lattice, store)  — uses default config

// propagators[0] = primary (persistence), [1+] = broadcast
node.addPropagator(primaryPropagator);   // index 0
node.addPropagator(publicPropagator);    // index 1
```

Merge context and propagator topology are configuration-time only: set them before
the first `launch()`. They remain frozen across a later close/relaunch cycle, and
`getPropagators()` returns an immutable snapshot so callers cannot reorder the primary
under a live sync callback. Internally the lifecycle is explicit:
`NEW → STARTING → RUNNING → STOPPING → STOPPED`; relaunch moves `STOPPED` back through
`STARTING`, while a drain timeout remains `STOPPING` until `close()` is retried.

NodeConfig options:
- **`port`** — network port (null = auto, negative = local-only / no network)
- **`persist`** — write root data for restore (default: true)
- **`restore`** — restore root data at launch (default: true)
- **`url`** / **`allowPrivateURL`** — advertised transport and dev-network override
- **`maxMessageSize`** — maximum encoded inbound frame, enforced before full allocation (default: 4 MiB)
- **`maxTrustedMessageSize`** — encoded frame limit after an outbound Peer's AccountKey is verified (default: protocol maximum, 50 MB)
- **`maxInboundValueSize`** — maximum decoded lattice value accepted for merge (default: `maxMessageSize`)
- **`maxConnections`** — simultaneous inbound connection cap (default: 256)
- **`inboundQueueSize`** — bounded off-Netty processing queue capacity (default: 1024)
- **`inboundShutdownTimeout`** — time allowed for accepted inbound work to drain during shutdown (default: 10 seconds)
- **`maxConsecutiveRejects`** — bad-message circuit-breaker threshold (default: 100)

Netty event-loop threads only parse the bounded frame and offer it to the inbound
queue. Payload decoding, lattice merge, synchronous persistence and response encoding
run on the ordered NodeServer dispatcher. When the queue is full, reads pause only on
the affected channel until capacity becomes available or the delivery timeout expires.

The 4 MiB public limit also applies to connections initiated by NodeServer. TCP is
bidirectional, so an outbound socket remains untrusted until its remote endpoint proves
the AccountKey advertised for that Peer through challenge/response. Only successful
verification promotes that individual connection to `maxTrustedMessageSize`; discovery,
a signed NodeInfo, or merely opening the socket is not sufficient.

Shutdown stops network admission and waits for the ordered dispatcher before taking the
final persistence snapshot. If accepted work does not drain within
`inboundShutdownTimeout`, `close()` throws and retains the dispatcher and propagators;
`launch()` remains forbidden so two consumers can never overlap. Once the blocking
operation returns, calling `close()` again completes the same shutdown safely.

A primary-store error during `cursor.sync()` throws to the calling context. The
memory-first cursor value is retained and the persisted root must be treated as
unconfirmed. NodeServer does not impose a shutdown or retry policy; the operator
decides how to recover.

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
ALatticeCursor<V> cursor = node.getCursor();

// App writes directly to cursor (instant, in-memory)
DLFSLocal drive = new DLFSLocal(provider, uri, cursor.path(ownerKey, driveName));
Files.write(drive.getPath("/readme.txt"), "hello".getBytes());

// App triggers sync when ready
cursor.sync();
```

### Custom App Pattern

```java
ALatticeCursor<V> cursor = node.getCursor();

// Batch writes
cursor.set(value1, key1);
cursor.set(value2, key2);

// Single sync propagates the latest state
cursor.sync();
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

**Only the root-level cursor propagates.** Sub-path cursors write atomically to the
root `AtomicReference`. Calling `sync()` on any cursor in the hierarchy propagates up
to the root, where the sync callback triggers all propagators.

Sub-path NodeServers can replicate sub-trees independently to different peer sets,
each with their own propagator and store.

## Implementation Status

Phases 1–4 are complete and tested. Remaining work is listed below.

### Completed ✓

- **Core Persistence** — `Cells.announce()` + `store.setRootData()`, restore in `launch()`, final persist in `close()`
- **Explicit Sync API** — `cursor.sync()` triggers propagators via callback, incoming merges call `cursor.sync()`, periodic auto-sync
- **Speculative Fork + Acquire** — fork cursor, `Acquiror` pulls missing cells, retry merge
- **Root-Only Periodic Sync** — propagator broadcasts root cell hash, peers detect divergence, acquire missing data

### Remaining

**Filtering + Security Tiers**
- `LatticeFilter<V>` interface exists but is not yet integrated into propagator
- Each propagator owns its own filter, applied internally before announce
- Multiple propagators with separate stores and peer sets
- Public / trusted / backup tiers

**Propagator Convergence**
- Extract `APropagator<T>` base from `BeliefPropagator` and `LatticePropagator`
- Shared: background loop, trigger queue, delta encoding, broadcast
- Separate: message format, merge semantics

**Synchronous Commit** — see the implemented sync design below

## Synchronous Commit Design

### Previous Problem

The earlier sync callback offered the snapshot to propagators and returned immediately.
The propagator's background thread later announced cells, persisted root data, and
called the merge callback. Thus `sync()` returned before persistence completed,
store-backed refs arrived asynchronously, and tests needed `Thread.sleep`.

### Design: Synchronous Commit via Sync Callback

The existing `RootLatticeCursor.onSync` hook already supports synchronous commit:
the callback runs on the caller's thread and the returned value is CASed back into
the cursor (with lattice-merge fallback on concurrent write). No second cursor is
needed — the single-cursor design plus CAS-or-merge gives the required guarantee.

#### Scope: Primary Synchronous, Secondaries Async

Only the **primary propagator** runs synchronously on the caller's thread. Secondary
propagators (public, backup) keep their existing async broadcast loop.

Rationale:
- Primary is the checkpoint anchor — `sync()` returning means it accepted the logical root update.
- Physical Etch flush is a separate operator policy (see issue #650).
- Secondaries are best-effort broadcast; their latency does not affect the primary checkpoint.
- Caller's thread does **one** store write (primary), not N. Bounded cost.

#### Sync Flow

Caller's thread (inside the `onSync` callback) runs the primary's full pipeline:
1. `value = filter.apply(cursor value)` — primary has no filter; identity
2. `announced, novelty = Cells.announce(value, primaryStore)` — primary store, primary novelty
3. `store.setRootData(announced)` — synchronous logical root publication
4. Encode delta from novelty and broadcast to primary's peers (Netty fire-and-forget)
5. For each secondary propagator: `triggerBroadcast(value)` — async fan-out
6. Return `announced` — `RootLatticeCursor.sync()` CASes it back, merge fallback handles concurrent app writes

Steps 1-4 are exposed as `processSnapshot`, which is callable on any thread and is
invoked directly from the sync callback for the primary.

The primary's background thread still exists for non-sync triggers:
- Periodic root sync (Tier 2 divergence detection)
- Any other async `triggerBroadcast` invocations

Secondary propagators' background threads (unchanged behaviour):
- Apply their own filter
- `Cells.announce(filtered, ownStore)` — produces filtered novelty against own store
- `setRootData(filtered)`, `broadcast(delta)`

#### Per-Propagator Novelty is a Security Boundary

**Novelty MUST be computed per-propagator, against that propagator's store, after
that propagator's filter.** Sharing novelty across propagators would broadcast cells
that were never tracked in the public propagator's store, bypassing its filter and
leaking private data.

```
Primary (no filter, primary store):
  announce(value, primaryStore) → primaryNovelty
  → broadcast primaryNovelty (only if primary has peers)

Public propagator (public filter, public store):
  announce(filter(value), publicStore) → publicNovelty   ← independent
  → broadcast publicNovelty
```

Each propagator's announce IS its security boundary. Cross-propagator novelty
sharing is forbidden.

#### Concurrency

`Cells.announce()` tags cells as persisted — once a cell is announced to a store,
re-announcing returns no novelty. The pipeline is therefore single-pass: the
thread that announces also encodes and broadcasts, with novelty held in a local
variable. No cross-thread novelty handoff.

**Sole-writer invariant.** The propagator is the only live writer of `setRootData`
on its store, and pipelines through the propagator must not interleave.
`processSnapshot` and `persist` both acquire a propagator-internal `writeLock` so
the caller's thread (sync hook), the background propagation thread (pull, drain),
and explicit `NodeServer.persistSnapshot` calls run their full announce +
setRootData + broadcast sequences sequentially. Without this, an older snapshot's
setRootData could land *after* a newer snapshot's — Etch's class-level
`synchronized` only orders the individual root-pointer write, not the surrounding
pipeline — silently demoting the root pointer and breaking the durability promise
of `sync()`.

EtchStore today serialises all writes on a class-level `synchronized` (`Etch.java:321`).
Multiple caller threads syncing concurrently will contend on the propagator's
`writeLock` first, then on the Etch monitor. Acceptable for v1; see "Follow-ups"
below for narrowing the Etch lock to per-region writes.

Concurrent app writes during sync are handled by `RootLatticeCursor.sync()`: it
CASes the announced value back into the cursor, falling back to lattice merge if
the CAS fails. The merge combines the store-backed announced value with any
in-memory writes that landed during the announce — no data loss, store-backed refs
preserved for cells that overlap.

#### Error Propagation

If announce or `setRootData` throws, the exception propagates to the `sync()` caller.
The memory-first cursor value remains available. Because `AStore.setRootData()` does
not specify transactional rollback for every failure point, the persisted root is
unconfirmed: it may still be the previous root, but callers must not infer that solely
from the exception. NodeServer stays running; shutdown, replacement, inspection or a
later explicit sync are operator policy. A successful `sync()` confirms that the
primary store accepted the root update, not that Etch physically flushed it; a thrown
`sync()` confirms only that the checkpoint did not complete successfully.

#### Pull Path Uses the Root Sync Boundary

`LatticePropagator.pull()` only queries and acquires a store-backed peer value. It
does not persist, announce or broadcast that raw value. NodeServer merges acquired
values through its authoritative root cursor and calls `cursor.sync()` afterwards.
This prevents a dominated peer value from demoting the primary store root or announced
query view, and gives inbound messages, explicit pulls and application updates the same
re-propagation boundary.

### Benefits

- `sync()` guarantees primary persistence before returning
- Store-backed refs immediate — no OOM from lingering strong refs
- No `Thread.sleep` in tests
- Caller-visible durability errors
- Per-propagator novelty preserves filter security boundaries

### Follow-ups (deferred)

- **Etch lock granularity** — narrow the class-level `synchronized` on Etch writes
  to a smaller per-region or per-write critical section. Current coarse lock will
  serialise concurrent caller-thread syncs.

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
