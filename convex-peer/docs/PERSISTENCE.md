# Lattice node persistence

`NodeServer` is the sole owner of its authoritative lattice root. Propagators
materialise serving views; they do not decide what the node persists.

This distinction prevents network policy from becoming an accidental second
authority over application state.

## Authoritative state

A node owns:

- a `RootLatticeCursor` containing the current application value;
- a `RootComponent` connecting `sync()` to node publication;
- one authoritative `AStore`; and
- one serialised root-publication/checkpoint pipeline.

The application mutates the cursor and calls `sync()`:

```text
application cursor update
          |
          v
RootComponent.sync()
          |
          v
NodeServer publication policy
  Cells.announce(value, nodeStore)
  nodeStore.setRootData(value)       when :persist is true
          |
          +-- return store-backed authoritative value
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

- hosts and merges its lattice value;
- announces store-backed cells;
- retains the node root when persistence is enabled;
- restores the root at launch; and
- performs configured durability barriers.

This is the normal shape for a local database or application component that has
not enabled replication.

## Propagator stores

Each `LatticePropagator` owns a serving store. The store is used for:

- materialising the group's filtered publication view;
- novelty collection and bounded delta encoding;
- answering `LATTICE_QUERY` and `DATA_REQUEST`; and
- acquiring trusted partial values before they reach the authoritative merge.

At attachment, root retention in the propagator is disabled. Even when a group
uses a physically different persistent store, `NodeServer` remains the only
writer of the authoritative root pointer. Launch seeds the serving store again
from the restored node value.

A standalone `LatticePropagator` retains compatibility support for its own local
root, `restore()` and `checkpoint()`. That mode is not the ownership model for an
attached node.

## Logical publication and durability

`AStore.setRootData` establishes the logical root. Persistent stores may still
need `flush()` for a durability barrier. `NodeServer` tracks whether its store is
dirty and flushes it:

- at the end of launch after initial publication;
- periodically when `persistInterval` is positive;
- from an explicit `checkpoint()`;
- from `persistSnapshot()`; and
- during orderly shutdown.

`persistSnapshot(value)` announces and publishes an explicit authoritative
snapshot without notifying propagators, then completes the durability barrier.
It is intended for host-controlled persistence, not network ingress.

When `persist` is false, node publication still announces cells to the store so
the current cursor has valid store-backed references, but does not retain the
root pointer or schedule durability work. `restore` controls whether launch reads
an existing persistent root.

## Ordering

All node root writes and checkpoints share one `persistenceLock`. An older root
cannot land after a newer root merely because an explicit checkpoint and cursor
sync ran concurrently.

Each propagator separately serialises its working view and serving-store
announcement. Consecutive node notifications use a latest-update queue and may
coalesce because lattice snapshots are monotonic. Coalescing affects transport
work, not the ordering or durability of the authoritative node root.

The node and a propagator may deliberately share the same thread-safe `AStore`.
Only the node calls `setRootData` while attached; propagators merely announce
cells into that store. Separate serving stores provide a stronger disclosure
boundary for filtered public views.

## Inbound persistence boundary

Inbound protocol work happens in the selected propagation group:

1. bounded queue admission;
2. complete-value decode or trusted acquisition into the serving store;
3. ingress filtering;
4. `NodeServer.mergeInbound(path, value)`;
5. authoritative `cursor.sync()` if the merge changed the node; and
6. asynchronous notification of every attached group.

An unverified connection cannot acquire partial trees. A complete public value
can still be merged under zero trust, but only if the configured lattice and
ingress policy accept it.

The response to a correlated update follows step 5. This means the sender learns
whether authoritative publication succeeded without being coupled to optional
propagator workers.

## Failure semantics

Authoritative failures are visible:

- a node-store announce or root-write failure makes `sync()` fail;
- a launch-time node-store failure aborts launch and unwinds node resources;
- a shutdown checkpoint failure is returned from `close()`.

The in-memory cursor is not rolled back after a publication failure. Recovery is
an application/operator decision because the underlying store may have completed
part of the write.

Propagation failures are isolated:

- materialisation, filter, broadcast, connection or application-handler failure
  affects only that group;
- a failed group cannot fail the authoritative sync or another group;
- endpoint shutdown continues independent cleanup after acquisition or worker
  timeout; and
- `NodeServer.close()` still reaches `STOPPED` unless its own resource fails.

Fatal JVM conditions such as out-of-memory remain process failures. Recoverable
stack overflow at untrusted decode or policy boundaries is contained and logged.

## Lifecycle

Launch order protects serving consistency:

1. configure and freeze the node publication callback;
2. restore the authoritative root when enabled;
3. announce and retain the authoritative root;
4. seed every attached serving view independently;
5. start propagator workers;
6. start persistence maintenance.

The application opens any `LatticeListener` only after node launch has prepared
the attached propagation endpoints.

Shutdown order prevents new work racing store closure:

1. the application closes its transports to stop admission and detach sockets;
2. `NodeServer.close()` stops node maintenance;
3. each endpoint stops acquisitions and drains accepted messages;
4. the node publishes the final authoritative root;
5. each propagator drains and closes independently; and
6. the authoritative store is flushed.

`NodeServer` does not close application-supplied stores. Store ownership remains
with the caller that created them.

## Waiting in tests and applications

Do not sleep or assume that `cursor.sync()` completed propagation. Capture the
appropriate signal before triggering work:

```java
CompletableFuture<ACell> served = propagator.nextAnnounce();
node.getCursor().set(updatedValue);
node.getCursor().sync();        // authoritative root is now published
served.get(timeout, unit);      // this group's served view is now materialised
```

For network convergence, wait on the pull future, correlated protocol result or
an application-level future/latch that represents the condition being tested.
