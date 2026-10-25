# Lattice node persistence

`NodeServer` is the sole writer of the node store's root. The host and its
applications decide what that root contains; propagators materialise serving
views of it. Neither the node nor a propagator decides what the root means.

This distinction prevents network policy from becoming an accidental second
authority over application state.

## Node state

A node is constructed from an assembled `LatticeHost` and uses:

- the host's root cursor, holding the current published value;
- the host's `sync()`, which launch connects to the node's publication policy;
- the host's store, which becomes the node store; and
- one serialised root-publication/checkpoint pipeline of the node's own.

The application publishes its working state to the host, then the caller syncs the host:

```text
application working update and application.sync()
          |
          v
LatticeHost.sync()
          |
          v
NodeServer publication policy
  Cells.announce(value, nodeStore)
  nodeStore.setRootData(value)       when :persist is true
          |
          +-- return store-backed published value
          |
          +-- non-blocking notification to each propagator
```

A successful `sync()` after launch confirms logical publication to the node
store. It does not confirm that any propagation group has announced or sent its
view. Call `propagator.nextAnnounce()` before the update when that later boundary
matters.

## No propagator is required

The application decides whether networking exists. `NodeServer` never creates a
default propagator. A zero-propagator node still:

- hands incoming values to its host, which delegates them to the configured
  applications;
- announces store-backed cells;
- retains the node root when persistence is enabled; and
- performs configured durability barriers.

This is the normal shape for a local database or application component that has
not enabled replication.

## Propagator stores

Each `LatticePropagator` owns a serving store. The store is used for:

- materialising the group's filtered publication view;
- novelty collection and bounded delta encoding;
- answering `LATTICE_QUERY` and `DATA_REQUEST`; and
- acquiring trusted partial values before they reach the host.

A propagator never writes a store root. Even when a group uses a physically
different persistent store, `NodeServer` remains the only writer of the node root
pointer, and launch seeds each serving store from the host's current value.

## Logical publication and durability

`AStore.setRootData` establishes the logical root. Persistent stores may still
need `flush()` for a durability barrier. `NodeServer` tracks whether its store is
dirty and flushes it:

- at the end of launch after initial publication;
- periodically when `persistInterval` is positive;
- from an explicit `checkpoint()`;
- from `persistSnapshot()`; and
- during orderly shutdown.

`persistSnapshot(value)` hands the supplied snapshot to the host, which delegates
it to its applications exactly as it would a network value, then publishes the
already-published host state without notifying propagators and completes the
durability barrier. The applications' lattices decide what the snapshot changes,
and an application may keep its result private until its own sync. The method is
intended for host-controlled persistence, not network ingress.

Persistence is the host's: a host with a store writes its root on every sync,
whether or not a node is attached. When `persist` is false the node still
publishes through the host, so the root pointer moves, but the node never
checkpoints: no durability barrier runs on an interval, at close or from the
shutdown hook, and `persistSnapshot` is a no-op. An ephemeral node uses a memory
store. `restore` is honoured when the host is assembled: `RelayHost.openHost`
adopts a retained root from any kind of store, and with `restore` false replaces
it with the initial value at once. The node itself never reloads the store.

## Ordering

All publication entry points run inside the host's ordered sync, which captures
the current root, publishes it and installs the store-backed result by identity
compare-and-set. Launch and shutdown publication use the same path. A separate
`persistenceLock` excludes checkpoints from cell announcement and root-pointer
writes; serialising store writes alone would not prevent a previously captured
snapshot arriving late.

If a local write intervenes during publication, the store-backed result is not
installed and the racing write stays pending for the next sync. The sync result
is the exact completed publication, while the live cursor may contain additional
pending changes. A subsequent quiescent sync installs the complete store-backed
tree; no special reference-rewriting merge is used.

Propagators merge nothing. Each group serialises only the announcement of the
host's published root into its own serving store. A value replicated back from a
peer therefore reaches the host like any other incoming value, and the receiving
application's lattice decides whether it displaces a local edit; under the
standard own-wins rule an older replicated copy cannot defeat an already
published local edit on a tie, while strictly newer values win.

Consecutive node notifications use a latest-update queue and may
coalesce because lattice snapshots are monotonic. Coalescing affects transport
work, not the ordering or durability of the node root.

These are the implementation's directional `ALattice` semantics. CAD035/036 in
the current design checkout still require universal commutativity (and CAD035
describes value-equality CAS); those specification discrepancies need resolution
separately from this publication ordering. This pipeline uses identity CAS and
does not change generic lattice conflict resolution.

The node and a propagator may deliberately share the same thread-safe `AStore`.
Only the node calls `setRootData` while attached; propagators merely announce
cells into that store. Separate serving stores provide a stronger disclosure
boundary for filtered public views.

## Inbound persistence boundary

Inbound protocol work happens in the selected propagation group:

1. bounded queue admission;
2. complete-value decode or trusted acquisition into the serving store;
3. `NodeServer.mergeInbound(path, value)`, which hands the value to the host and
   its applications, each taking what it wants;
4. host `sync()` if the merge changed the published root; and
5. asynchronous notification of every attached group.

An unverified connection cannot acquire partial trees. A complete public value
can still be merged under zero trust, but only if a receiving application takes
it and its lattice accepts it.

The response to a correlated update follows step 4. This means the sender learns
whether host publication succeeded without being coupled to optional propagator
workers.

## Failure semantics

Node-store failures are visible:

- a node-store announce or root-write failure makes `sync()` fail;
- a launch-time node-store failure aborts launch and unwinds node resources;
- a shutdown checkpoint failure is returned from `close()`.

The in-memory cursor is not rolled back after a publication failure. Recovery is
an application/operator decision because the underlying store may have completed
part of the write.

Propagation failures are isolated:

- materialisation, filter, broadcast, connection or application-handler failure
  affects only that group;
- a failed group cannot fail the host sync or another group;
- endpoint shutdown continues independent cleanup after acquisition or worker
  timeout; and
- `NodeServer.close()` still reaches `STOPPED` unless its own resource fails.

Fatal JVM conditions such as out-of-memory remain process failures. Recoverable
stack overflow at untrusted decode or policy boundaries is contained and logged.

## Lifecycle

Launch order protects serving consistency:

1. install and freeze the node's publication policy on the host;
2. announce and retain the host's current root (restore, if any, happened when
   the host was assembled);
3. seed every attached serving view independently;
4. start propagator workers;
5. start persistence maintenance.

The application opens any `LatticeListener` only after node launch has prepared
the attached propagation endpoints.

Shutdown order prevents new work racing store closure:

1. the application closes its transports to stop admission and detach sockets;
2. `NodeServer.close()` stops node maintenance;
3. each endpoint stops acquisitions and drains accepted messages;
4. the node publishes the final host root;
5. each propagator drains and closes independently; and
6. the node store is flushed.

`NodeServer` does not close application-supplied stores. Store ownership remains
with the caller that created them.

## Waiting in tests and applications

Do not sleep or assume that a host sync completed propagation. Capture the
appropriate signal before triggering work:

```java
CompletableFuture<ACell> served = propagator.nextAnnounce();
app.sync();                     // the application publishes into the host root
node.getHost().sync();          // the host root is now published to the node store
served.get(timeout, unit);      // this group's served view is now materialised
```

For network convergence, wait on the pull future, correlated protocol result or
an application-level future/latch that represents the condition being tested.
