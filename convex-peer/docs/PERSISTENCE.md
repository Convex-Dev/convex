# Lattice host persistence

`LatticeHost` owns its retained root and durability operations. Applications
choose when to publish working state into the host. `NodeServer` supplies optional
replication notifications and inbound delivery; it does not control saving.

## Host state

The host retains one snapshot before invoking an attached publisher:

```text
application working update and application.sync()
          |
          v
LatticeHost.sync()
  store.setRootData(snapshot)
          |
          +-- return store-backed published snapshot
          |
          +-- NodeServer notification to each propagator, if attached
```

A successful `sync()` confirms logical publication to the host store, if one is
present. It does not confirm a physical durability barrier or that a propagation
group has announced or sent its view. Subscribe to `propagator.nextAnnounce()`
before triggering publication when that later boundary matters.

## Networking is optional

A local application needs a host and its components. Add a store for retained
state; add `NodeServer` and propagators only when replication is needed. The same
`host.checkpoint()` operation saves with or without networking. An ephemeral
networked application uses `MemoryStore`; a purely local host can have no store.

## Propagator stores

Each `LatticePropagator` owns a serving store. The store is used for:

- materialising the group's filtered publication view;
- novelty collection and bounded delta encoding;
- answering `LATTICE_QUERY` and `DATA_REQUEST`; and
- acquiring trusted partial values before they reach the host.

A propagator never writes a store root. Even when a group uses a physically
different persistent store, `LatticeHost` remains the only writer of the host root
pointer, and launch seeds each serving store from the host's current value.

## Logical publication and durability

`AStore.setRootData` retains a root and its reachable tree. The store's `flush()`
provides its physical durability barrier. The host exposes three boundaries:

- `sync()` retains and offers one snapshot to attached replication.
- `flush()` completes a barrier for already-retained data, without advancing the root.
- `checkpoint()` retains, offers and flushes one snapshot under the host publication lock.

Applications must publish their own staged changes first. A checkpoint never
accepts private application drafts and never exchanges a forked host with its
origin. Checkpointing a private host with a separate store therefore saves a
private draft; explicit fork sync or delivery accepts it into another host.

A service can scope `host.autoFlush(Duration)` around its lifetime for periodic
barriers. The resource exposes the first background failure through `failure()`,
retries subsequent flushes, and drains its worker and performs a final flush on
close. It never syncs applications or the host. Close it before the borrowed
store. A request requiring durable completion calls `host.checkpoint()` before
reporting success; an interval alone is not a completion guarantee.

Host construction expresses opening intent: `create` rejects a retained root,
`open` adopts one, and `replace` explicitly writes a replacement. Optional cursor
coordination is supplied at host construction with `Cursors::transactionalRoot`.
`RelayHost.open(lattice, store, context)` adds one live relay application using
these ordinary host rules. Storage flags are not `NodeConfig` settings.

## Ordering

All publication entry points run inside the host's ordered sync, which captures
the current root, publishes it and installs the store-backed result by identity
compare-and-set. Launch seeds replication through this path. The same host lock
serialises checkpoints and flushes with root publication; an older snapshot
cannot arrive late and replace a newer retained root. Network close does not
publish another snapshot.

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

- a host-store root-write failure makes `sync()` fail;
- a launch-time node-store failure aborts launch and unwinds node resources;
- an explicit barrier failure is returned from host `checkpoint()` or `flush()`;
- a final automatic-flush failure is returned from the durability resource's `close()`.

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

1. attach the node's publication callback to the host;
2. announce and retain the host's current root (restore, if any, happened when
   the host was assembled);
3. seed every attached serving view independently;
4. start propagator workers.

The owning service chooses any initial checkpoint and starts automatic flushing
explicitly. Networking does not install a durability worker or shutdown hook.

The application opens any `LatticeListener` only after node launch has prepared
the attached propagation endpoints.

Shutdown order prevents new work racing store closure:

1. the service stops requests and closes transports to stop admission;
2. `NodeServer.close()` drains accepted messages and closes each group;
3. the owning service explicitly saves any accepted host state it wants retained;
4. the service closes its automatic-flush resource, if any, and then its store.

A service that shuts down from a JVM shutdown hook registers that work with
`Shutdown.addHook(Shutdown.SERVICE, ...)`, not `Runtime.addShutdownHook`. The JVM
runs separate hooks concurrently, so a separate hook races Convex's own hook,
which closes transports and every Etch file. The `SERVICE` level completes first.

Network close does not publish pending host state or flush the store. The host
and its applications remain usable after detachment.

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
