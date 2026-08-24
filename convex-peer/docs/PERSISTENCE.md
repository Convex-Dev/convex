# NodeServer Persistence & Sync Design

NodeServer is responsible for providing a cursor to lattice apps and subsystems.
NodeServer manages a list of propagators that handle persistence and broadcast to peers.

## Core Principles

1. **NodeServer owns the cursor** — it provides an `ALatticeCursor<V>` that applications use
   freely for instant in-memory reads and writes. The cursor is the single source of truth.
2. **No automatic sync on cursor write** — apps write to the cursor without triggering
   any I/O. Sync is a separate, explicit action.
3. **Apps trigger sync when ready** — `cursor.sync()` guarantees the cursor is synced
   with the level below (parent cursor or, at root, the sync callback). At the root it
   publishes the current value and reachable cells to the primary store, but does not
   force a physical durability barrier.
4. **Automatic sync is configurable policy** — periodic sync, on-incoming-merge, or
   manual-only. Controlled by NodeServer configuration.
5. **Propagators handle ALL output** — persistence IS propagation (to disk instead of to
   peers). Each propagator owns its own store, peer connections and outbound filter.
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
9. **Durability is explicit policy** — `AStore.flush()` is the physical durability
   barrier. NodeServer checkpoints after initial publication, periodically while dirty,
   and during orderly shutdown; broadcast remains best-effort.

### Opening encrypted Etch stores

Store identity and secret resolution belong to peer or lattice-node startup,
not to `NodeServer` or the Etch file format. High-level launchers should pass
their store path, optional `EtchConfig` creation policy and identity `AKeyPair`
through `convex.peer.Config`. Its default Etch resolver derives the standard
master key from that identity, verifies any existing header `publicKeyHint`,
and supplies the full identity key as the hint for a new encrypted file.

Applications backed by a keystore, hardware device or other secret provider
may set the runtime-only `Config.ETCH_KEY_RESOLVER` function. That explicit
resolver wins over identity-key derivation and is deliberately not a JSON5
field. Existing Etch headers remain authoritative; the serialised `peer.etch`
section is only a creation policy.

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
                     │    announce → setRootData                 │
 Store-backed  ◄─────│    → return announced value    [primary]  │
                     │    → broadcast(delta)          [network]  │
 refs merged         │                                           │
 into cursor         │                                           │
                     │  Incoming acquisition + merge:            │
 From peers ────────►│    acquire fully in owning prop store     │
                     │    → cursor.path(path).merge(value)        │
                     │    └──► cursor.sync()                      │
                     │                                           │
                     │  close()                                  │
                     │    ├──► cancel + await inbound Acquirors   │
                     │    └──► triggerAndClose each propagator    │
                     └──────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| **Cursor** (`RootLatticeCursor<V>`) | In-memory state. Apps read/write freely. `sync()` triggers propagators via callback. Thread-safe via AtomicReference. |
| **NodeServer** | Orchestration. Owns cursor + propagator list. Hooks sync callback on cursor. |
| **Propagator** (`LatticePropagator`) | Owns its store, outbound filter, working view, peers and background propagation; pull operations acquire store-backed values without merging them. |
| **Acquiror** | Owns one remote acquisition worker and request lifecycle. Stores decoded response cells, which may be partial, and follows their missing references using the existing CAD acquisition loop. |

### Propagator Roles

Propagators are held in a list. **Index 0 is always the primary propagator** (if present).
NodeServer processes the primary synchronously from the root sync callback and queues
secondary propagators asynchronously. Explicit pulls follow the same root-owned path:

```java
ACell acquired = propagators.get(0).pull(peer).join(); // acquisition only
cursor.merge(acquired);                               // authoritative root merge
cursor.sync();                                        // publish and re-propagate merged root
```

The propagator knows the lattice semantics needed to reconcile its own working view.
It returns the filtered, store-backed value to NodeServer, which owns the authoritative
root merge and subsequent sync boundary.

The following table shows the intended multi-tier topology.

| Index | Role | Filter | Peers | Store | Purpose |
|-------|------|--------|-------|-------|---------|
| 0 | **Primary** | None | None (or local) | EtchStore | Synchronous publication, restore and pull acquisition. Store-backs cursor. |
| 1+ | **Public** | Yes (strip private) | Untrusted | Own store | Public data broadcast. Security boundary. |
| 1+ | **Backup** | None | Trusted | Own store | Full replication to trusted peers. |

The **primary propagator** is the app-level restore source. It normally uses the identity
filter, announces all cells in its outbound view, and sets root data. On startup, NodeServer
restores the cursor from `propagators[0].getStore().getRootData()`.

A node that only wants disk persistence (no network) has a single primary propagator
with no peers. A node with no propagators is purely in-memory.

### Why Propagators Need Their Own Stores

`Cells.announce(value, noveltyHandler, store)` does two things simultaneously:
1. **Writes cells to the store** — so it can detect novelty on subsequent announces
2. **Collects novel cells** — cells not previously in the store, for delta encoding

The store accumulates all announced cells. When peers send `DATA_REQUEST` messages, they
can only resolve cells that exist in the propagator's store. Outbound filtering prevents
excluded cells being introduced by announcement, but it is not an inbound storage filter:
cells acquired from a peer may already exist in that same store. Store assignment and peer
access therefore remain operator policy.

Adding a peer to a propagator's connection manager is the operator's grant of read
access to that store. The connection manager does not prescribe whether those peers
must be verified or whether a public propagator may accept public membership; operators
choose the membership and rights appropriate to each store. On the NodeServer listener,
an assigned connection serves `DATA_REQUEST` only from its selected propagator store;
an unassigned connection is rejected because no store can be chosen safely.

Inbound lattice access has the matching rule: **one physical connection, one owning
propagator, one store**. Operator policy supplies a connection selector before launch.
Once a non-null selection is made it is immutable for that connection. The selected
propagator provides both the `LATTICE_QUERY` view and the store used to acquire incoming
`LATTICE_VALUE` data. An unassigned connection is decoded without a store and may use
basic protocol operations, but lattice queries and values are rejected. There is no
fallback to the primary store and no search across propagators.

```java
// Explicitly public single-view node. This is a policy decision, not a default.
node.setInboundPropagatorSelector(connection -> node.getPropagator());
```

A deployment with public and private views should select by authenticated connection
identity or another operator-controlled capability. If one Peer has rights to multiple
views, it uses distinct physical connections. Authentication policy is deliberately
outside NodeServer; the selector expresses the operator's decision.

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
cursor.sync();  // returns after the primary publication pipeline completes
```

`sync()` is a synchronous publication boundary. A successful return confirms that
the primary store contains the root and its reachable cells and that the returned
value is store-backed. It does not call `AStore.flush()` and therefore does not promise
crash durability. An announce or root-publication failure is reported as
`StoreException`; the in-memory cursor is not rolled back.

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

The primary announce, root-publication and broadcast pipeline completes before sync returns.
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
1. NodeServer binds the physical connection to its operator-selected propagator and
   attempts to persist the complete value in that propagator's store.
2. If CAD branches are missing, NodeServer creates an `Acquiror` for that store. The
   Acquiror owns its virtual worker and correlated reverse `DATA_REQUEST`. Decoded
   response cells are stored as received and may themselves be partial; the normal
   acquisition loop continues with their missing references.
3. Only the completed value returns to the ordered dispatcher. NodeServer navigates to
   the target path via `cursor.path(path)` and merges via `target.merge(value)` — the
   cursor chain handles sub-lattice resolution, signing boundaries, and null-lattice
   bubble-up automatically.
4. Calls `cursor.sync()` — this synchronously publishes to the primary store and queues
   secondary propagation.

`LATTICE_VALUE` uses `[:LV id path value]`. A null ID is fire-and-forget. With a
non-null ID, NodeServer returns an empty successful `Result` only after the merge and,
when it changed the cursor, synchronous primary publication. Rejected merges and
publication failures return an error where the request ID is available. For
fire-and-forget publication failures, NodeServer contains and logs the exception at the
inbound-message boundary; the accepted merge remains in memory and the node stays
running for operator-directed recovery.

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
    networkServer.close();             // stop new acquisition work
    closeAndAwaitInboundAcquirors();    // no worker can still touch a store
    stopInboundDispatcher();           // drain complete ordered work

    // Drain propagators and release their network resources
    V snapshot = cursor.get();
    for (LatticePropagator p : propagators) {
        p.triggerAndClose(snapshot);  // trigger, drain queue, stop thread
        p.getConnectionManager().close();
    }
    // Complete each persistent store barrier using that propagator's final view.
    if (persist) {
        for (LatticePropagator p : propagators) {
            p.persist(p.getLastAnnouncedValue());
        }
    }
}
```

Incomplete values are safe to cancel because they have not crossed the complete-value
gateway into lattice merge code. `close()` waits for each Acquiror's actual termination,
not merely cancellation of its future, before propagator stores may be closed. Each
propagator's `triggerAndClose()` then ensures queued work is drained before the thread
stops. Final store-only persistence runs after network resources are released and any
failure is returned by `close()`.

## Persistence Lifecycle

### Startup: Restore

```java
public void launch() {
    // Restore every propagator's working view; only primary is authoritative.
    for (int i = 0; i < propagators.size(); i++) {
        ACell restored = propagators.get(i).restore();
        if ((i == 0) && (restored != null)) cursor.set((V) restored);
    }

    // Seed every store-backed announced view before opening the listener.
    ACell announced = propagators.get(0).processSnapshot(cursor.get());
    cursor.set((V) announced);
    for (int i = 1; i < propagators.size(); i++) {
        propagators.get(i).processSnapshot(announced);
    }
    if (persist) {
        for (LatticePropagator p : propagators) p.checkpoint();
    }

    // Start propagators, network server, etc.
    ...
}
```

The primary propagator's store holds its authoritative outbound view as root data
(normally the full value because the primary normally uses the identity filter).
On startup, NodeServer restores this into the cursor, then processes the current
snapshot before opening the listener. Fresh and restored nodes can therefore answer
`LATTICE_QUERY` immediately, with store-backed refs and a recovery root established
when persistence is enabled. No application-side initial `sync()` is required.

For a publicly advertised node, launch is not complete until its signed NodeInfo has
passed the same synchronous publication boundary. If publication fails after the listener
has opened, NodeServer closes the listener, dispatcher, propagators and connection
managers before rethrowing the original error. `launch()` therefore either returns a
running node or throws with the node stopped; the same instance may be retried after
the store problem is resolved.

### Running: Publication and Checkpoints

Publication can be triggered by:
- **App explicitly** — `cursor.sync()` after a batch of writes
- **Incoming merge** — if autoSync policy is enabled

Successful root publication marks the owning propagator store dirty. NodeServer's
existing maintenance loop calls `checkpoint()` on dirty persistent stores at the
configured `persistInterval` (default 30 seconds). Clean and volatile stores are
skipped. A failed periodic barrier leaves the store dirty and is retried at the next
interval. Callers needing immediate crash durability call `cursor.sync()` followed by
`node.checkpoint()`.

### Node Configurations

| Primary propagator | Network propagators | Behaviour |
|--------------------|--------------------|---------  |
| EtchStore, no peers | Public + backup with peers | Full node: persist + broadcast |
| EtchStore, no peers | None | Local only: persist, no broadcast |
| None | Public + backup with peers | Relay: broadcast, no local persist |
| None | None | In-memory only: cursor.sync() is a no-op |

## Propagator Architecture

### Store Separation

This diagram shows the public/private topology after `LatticeFilter` integration.
Store ownership, connection capabilities and filtering are all enforced by each
`LatticePropagator`.

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
`store.setRootData()` for restore. Explicit checkpoints additionally call
`store.flush()` for physical durability. Stores also serve as security boundaries
for `DATA_REQUEST` from peers.

### Propagator Internals

A `LatticePropagator` owns:
- An `AStore` for delta tracking, persistence, and peer data resolution
- A `LatticeConnectionManager` with its own set of peer connections
- A background thread processing broadcast triggers
- Its `LatticeFilter`, lattice-aware working cursor and announced cursor

When `triggerBroadcast(value)` is called:
1. Value queued (LatestUpdateQueue coalesces rapid triggers)
2. Background thread picks up latest value
3. Merge with the propagator working view (existing view is `own`)
4. Apply this propagator's outbound filter
5. `Cells.announce(value, noveltyHandler, store)` — writes to store, collects novelty
6. `store.setRootData(value)` — anchor for restore
7. `Format.encodeDelta(novelty + protocol envelope)` — encode only novel cells
8. Send `LATTICE_VALUE` message to connected peers

The send step is skipped if the propagator has no peers (pure persistence propagator).
NodeServer owns the authoritative cursor and primary merge. Each propagator knows the
lattice only to reconcile its filtered working subset, including accepted inbound state
which may precede fan-out from the primary. The primary is configured as authoritative:
its working view is replaced by the root snapshot rather than directionally merged with
an older publication.

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

- Immutable values identify every missing branch by content hash
- Acquisition is isolated in the connection's owning propagator store
- The receiver requests only absent cells from the update's originating connection
- Lattice merge code sees one complete, size-checked value

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
  2. Receiver resolves the connection's owning propagator store
  3. Receiver pulls missing cells via `DATA_REQUEST` without attempting a merge
  4. The complete, size-checked value enters the ordered merge path

The receiver knows the peer's latest state but only pulls what's actually missing.

#### Tier 3: Acquire Then Merge (Automatic)

- **When**: Whenever incoming message references unknown cells
- **How**: Fully acquire in the owning propagator store, then submit one complete value
  to the ordered merge path
- **Protocol**:
  1. Decode against the owning propagator store
  2. Request every missing hash from the same connection
  3. Validate response count and content hashes
  4. Persist the complete value tree in the owning propagator store
  5. Enforce the complete-value size limit
  6. Submit the value once to NodeServer's ordered merge path
- **Bandwidth**: Only missing data (tree difference)
- **Safety**: Partial values never reach lattice or cursor code

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
| 3 | Acquire then merge | Before inbound merge | On-demand (missing cells only) |

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

Each propagator owns its outbound filter. NodeServer may pass the same full immutable
snapshot to every propagator; after working-view reconciliation, excluded cells are
trimmed before announcement, root publication or broadcast.

```
cursor.sync():                         Propagator processing:
  sync callback(value)
  │                                    propagators[0] (primary):
  ├──► processSnapshot(value) ─────────► announce(value)
  │                                      setRootData(value)
  │◄──────────────────────────────────── return announced value
  │
  │                                    propagators[1] (public):
  ├──► trigger(value) ────queue──►       reconcile(own, value)
  │                                      filter(reconciled)
  │                                      announce(filtered)
  │                                      setRootData(filtered)
  │                                      broadcast(delta)
  │
  └──► trigger(value) ────queue──►     propagators[2] (backup):
                                         announce(value)
  returns after primary                   setRootData(value)
                                         broadcast(delta)
```

Private cells are never introduced into the public store by outbound announcement.
Inbound acquisition is deliberately unfiltered, however, and may place such cells in
the same store. Operators must therefore treat connection membership and DATA_REQUEST
access as a separate policy from outbound projection. The primary and backup propagators
normally have all cells, so only trusted peers should connect to them.

### Filter Interface

```java
@FunctionalInterface
public interface LatticeFilter<V extends ACell> {
    V filter(V value);
    // Must be idempotent: filter(filter(v)) == filter(v)
}
```

For a secondary propagator, the established working view is the directional merge's
`own` argument and a new primary snapshot is `other`. This is
deliberate: equal-priority conflicts retain the propagator's current value and refs,
avoiding needless replacement with equivalent refs backed by a different store.
The reconciled result is then filtered before announcement. The primary skips this
reconciliation because its input already is the authoritative merge result.

Accepted inbound values are staged through a fork of the propagator working cursor,
without applying the outbound filter. A rejecting lattice leaves the view unchanged.
The authoritative NodeServer merge remains the acceptance boundary; normal root fan-out
then reconciles, filters and announces the propagator subset.

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
- **`maxDeltaMessageSize`** — maximum encoded outbound lattice delta or DATA-ahead chunk (default: `maxMessageSize`)
- **`maxDeltaBroadcastSize`** — maximum combined encoded bodies materialised for one eager delta (default: 16 MiB, never below `maxDeltaMessageSize`)
- **`maxConnections`** — simultaneous inbound connection cap (default: 256)
- **`inboundQueueSize`** — bounded off-Netty processing queue capacity (default: 1024)
- **`maxInboundQueueBytes`** — encoded-byte bound for that inbound queue (default: 16 MiB, never below `maxMessageSize`)
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

Shutdown stops network admission, cancels and awaits incomplete-value Acquirors, then
waits for the ordered dispatcher before taking the final persistence snapshot. If an
Acquiror's store operation cannot stop safely or accepted ordered work does not drain
within `inboundShutdownTimeout`, `close()` throws and retains the dispatcher and
propagators; `launch()` remains forbidden so store users or ordered consumers cannot
overlap a replacement lifecycle. Once the blocking operation returns, calling `close()`
again completes the same shutdown safely.

A primary-store publication error during `cursor.sync()` throws to the calling
context. The memory-first cursor value is retained. NodeServer does not impose a
shutdown or retry policy; the operator decides how to recover.

Current propagation timing (`LatticePropagator` constants):

| Constant | Value | Description |
|-----------|---------|-------------|
| `ROOT_SYNC_INTERVAL` | 30 s | Periodic root-only sync for divergence detection |

Background snapshot triggers are coalesced before processing, but every processed
snapshot is broadcast: explicit sync must not silently discard a rapid follow-up delta.
The root-sync timing is a fixed implementation constant, not operator configuration.
There is no separate periodic delta re-push; the root-only sync provides eventual
divergence detection and missing data is acquired on demand.

For the implementation details behind DATA batching, shared fan-out,
materialisation budgets and isolation of slow receivers, see
[Delta Propagation, Backpressure and Memory Bounds](PROPAGATION.md).

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

- **Core Publication** — `Cells.announce()` + `store.setRootData()`, restore in `launch()` and explicit durable `persistSnapshot()`
- **Explicit Sync API** — `cursor.sync()` triggers propagators via callback, incoming merges call `cursor.sync()`, periodic auto-sync
- **Acquire Then Merge** — the owning propagator store acquires every missing cell
  before NodeServer attempts one merge
- **Root-Only Periodic Sync** — propagator broadcasts root cell hash, peers detect divergence, acquire missing data
- **Durability Checkpoints** — startup barrier before listener exposure, coalesced
  periodic dirty-store barriers, explicit `checkpoint()`, and observable final barriers

### Remaining

**Filtering + Security Tiers**
- Document reference public / trusted / backup deployment policies

**Propagator Convergence**
- Extract `APropagator<T>` base from `BeliefPropagator` and `LatticePropagator`
- Shared: background loop, trigger queue, delta encoding, broadcast
- Separate: message format, merge semantics

## Synchronous Publication Design

### Previous Problem

The earlier sync callback offered the snapshot to propagators and returned immediately.
The propagator's background thread later announced cells, published root data, and
called the merge callback. Thus `sync()` returned before publication completed,
store-backed refs arrived asynchronously, and tests needed `Thread.sleep`.

### Design: Synchronous Publication via Sync Callback

The existing `RootLatticeCursor.onSync` hook already supports synchronous publication:
the callback runs on the caller's thread and the returned value is CASed back into
the cursor (with lattice-merge fallback on concurrent write). No second cursor is
needed — the single-cursor design plus CAS-or-merge gives the required guarantee.

#### Scope: Primary Synchronous, Secondaries Async

Only the **primary propagator** runs synchronously on the caller's thread. Secondary
propagators (public, backup) keep their existing async broadcast loop.

Rationale:
- Primary is the publication anchor — `sync()` returning means its root and cells are available from the store.
- Secondaries are best-effort broadcast; their latency does not affect primary publication.
- Ordinary sync does not force storage, keeping update latency independent of the durability policy.

#### Sync Flow

Caller's thread (inside the `onSync` callback) runs the primary's full pipeline:
1. `announced, novelty = Cells.announce(value, primaryStore)` — primary store, primary novelty
2. `store.setRootData(announced)` — synchronous logical root publication
3. Encode delta from novelty and broadcast to primary's peers (Netty fire-and-forget)
4. For each secondary propagator: `triggerBroadcast(value)` — async fan-out
5. Return `announced` — `RootLatticeCursor.sync()` CASes it back; on a concurrent
   app write it merges the announced value into the cursor with current state as `own`,
   while still returning the exact snapshot completed by this publication

Steps 1-3 are exposed as `processSnapshot`, which is callable on any thread and is
invoked directly from the sync callback for the primary.

The primary's background thread still exists for non-sync triggers:
- Periodic root sync (Tier 2 divergence detection)
- Any other async `triggerBroadcast` invocations

Secondary propagators' current background behaviour:
- `Cells.announce(value, ownStore)` — produces independent novelty against each store
- `setRootData(value)`, `broadcast(delta)`

Filter application and secondary working-view reconciliation occur immediately before
these steps.

#### Per-Propagator Novelty Is an Outbound Boundary

With filtering, **novelty MUST be computed per-propagator,
against that propagator's store, after that propagator's filter.** Sharing novelty
across propagators would broadcast cells
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

Each propagator's announcement is its outbound replication boundary. Cross-propagator
novelty sharing is forbidden; inbound store contents remain a separate access-policy
concern.

Announcement finishes before broadcast. Consequently, when a recipient needs a cell
referenced by an update, a reverse `DATA_REQUEST` on that propagator-managed connection
can be answered from the same store without consulting another propagator.

#### Concurrency

`Cells.announce()` tags cells as persisted — once a cell is announced to a store,
re-announcing returns no novelty. The pipeline is therefore single-pass: the
thread that announces also encodes and broadcasts, with novelty held in a local
variable. No cross-thread novelty handoff.

**Sole-writer invariant.** The propagator is the only live writer of `setRootData`
on its store, and pipelines through the propagator must not interleave.
`processSnapshot` and `persist` both acquire a propagator-internal `writeLock` so
the caller's thread (sync hook), the background propagation thread (pull, drain),
and explicit `NodeServer.persistSnapshot` calls run their full store-writing sequences
sequentially. Without this, an older snapshot's
setRootData could land *after* a newer snapshot's — Etch's class-level
`synchronized` only orders the individual root-pointer write, not the surrounding
pipeline — silently demoting the published root.

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
not specify transactional rollback for every failure point, the published root is
unconfirmed. NodeServer stays running; replacement, inspection or a later explicit
sync are operator policy. Physical durability errors belong to explicit or scheduled
`flush()` checkpoints, not cursor sync.

#### Pull Path Uses the Root Sync Boundary

`LatticePropagator.pull()` only queries and acquires a store-backed peer value. It
does not persist, announce or broadcast that raw value. NodeServer merges acquired
values through its authoritative root cursor and calls `cursor.sync()` afterwards.
This prevents a dominated peer value from demoting the primary store root or announced
query view, and gives inbound messages, explicit pulls and application updates the same
re-propagation boundary.

### Benefits

- `sync()` guarantees primary store publication before returning
- Store-backed refs immediate — no OOM from lingering strong refs
- No `Thread.sleep` in tests
- Caller-visible publication errors
- Per-propagator novelty preserves store boundaries and is ready for filtered views

### Follow-ups (deferred)

- **Etch lock granularity** — narrow the class-level `synchronized` on Etch writes
  to a smaller per-region or per-write critical section. Current coarse lock will
  serialise concurrent caller-thread syncs.

## Additional Resilience Testing

Useful fault-injection coverage beyond the ordinary unit suite:

1. **Delta loss simulation** — drop random broadcast messages, verify full sync recovers
2. **Network partition** — split network, update both sides, verify convergence after reunion
3. **New node join** — add fresh node, verify it syncs to current state
4. **Missing data** — send delta referencing non-existent cells, verify acquisition

Useful longer-running integration coverage:

1. **3-node network** — verify all nodes converge after updates
2. **Rolling restart** — restart nodes one-by-one, verify no data loss
3. **High-frequency updates** — stress test with rapid changes
4. **Primary/backup restore** — persist, restart, verify data survives

## Verification

```bash
./mvnw -B test -pl convex-peer
./mvnw -B test -pl convex-dlfs -am
```
