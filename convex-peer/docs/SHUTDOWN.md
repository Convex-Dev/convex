# Lattice service shutdown

A service running a lattice node saves the state it wants kept while the node can
still send it to peers, then closes what it opened in reverse order: its own
request handling, the node's networking, the host and the store. This is the
default the examples follow; a service can change any step.

## Order

1. Stop the service's requests, so no new work is admitted.
2. Sync the application drafts the service wants kept.
3. `host.checkpoint()`. The host saves its root durably and publishes it to the
   node, which is still running.
4. `node.close()`. The node closes its listeners. Each group stops admitting
   protocol work, drains what it accepted and its publication, waits for the
   resulting broadcasts to be written to each outbound route, then closes its
   routes. The drain and the wait share the group's `inboundShutdownTimeout`.
5. `host.close()`. The host stops automatic flushing and saves what changed after
   the checkpoint.
6. Close the store.

Publishing never waits on the network: the checkpoint writes to the local store and
hands the root to each group, which sends it from its own worker. A slow peer delays
step 4 by at most each group's timeout, and the state is already durable by then.

Networking runs until step 4. A peer's merge into live state after the checkpoint is
saved by step 5 but not sent on. A staged application keeps such a merge in its own
working copy, which is not saved unless it syncs again. Either way the peer still
holds the merge, and the two converge at their next exchange.

## In code

A service that runs within one block opens everything in its header, so a failure
at any step closes what was already opened:

```java
try (var store = EtchStore.create(file);
     var host = LatticeHost.open(store);
     var node = new LatticeNode<>(host, config)) {
    var application = host.addApplication(new App(host));
    node.launch();
    // ... serve until asked to stop ...
    requests.close();     // the service's own front end
    application.sync();   // drafts to keep
    host.checkpoint();    // saved, and sent to peers as the node closes
}                         // closes the node, then the host, then the store
```

A service that shuts down from a JVM shutdown hook does the same in its hook:

```java
try (store; host; node) {
    requests.close();
    application.sync();
    host.checkpoint();
}
```

A front-end close or drafts sync that can fail catches its own failure, so it does
not lose the drafts. If the checkpoint fails, the block still closes the node and
the host, and closing the host tries the save again. A service without drafts
leaves out the sync. One that leaves out the checkpoint still saves when the host
closes, but its peers receive that state only at their next exchange. A node
assembled from `NodeServer` and `LatticeListener` closes its listeners before the
`NodeServer`.

## What each close stops

Each close must stop everything its component started: it interrupts the threads
it runs, polling loops included, shuts down its executors, cancels its timers and
releases the resources it owns. Once a close returns, nothing the component started
may keep the JVM from exiting or do work that is no longer needed.

## What each close leaves alone

- Closing the host neither syncs applications nor closes the store. A closed host
  refuses syncs, checkpoints and flushes. If its final save fails, the host stays
  open and closing it again retries.
- Closing the node neither publishes pending host state nor flushes the store; it
  sends only what the host has already published. It never closes the store, which
  belongs to whoever created it. The host and its applications remain usable.
- Closing the host before the node also shuts down safely: the host's closing save
  reaches peers through the node, which is still attached, but a peer's merge that
  arrives after it is not saved.

## The final broadcast

The final broadcast is best effort:

- it reaches outbound routes only, because closing the listener closes the
  connections that peers opened to this node;
- written to a socket is not acknowledged by the peer;
- once a closing group stops admitting protocol work, it no longer answers data
  requests, so a peer receives the complete final value only when it fits one
  delta broadcast.

Peers converge on anything missed at their next exchange.

## Components on a borrowed host

Only the service that owns the host closes it. A component running on a host it
was given, such as an embedded engine, syncs its own drafts at shutdown and leaves
closing the host to its owner.

## JVM shutdown hooks

A service that shuts down from a JVM shutdown hook registers that work with
`Shutdown.addHook(Shutdown.SERVICE, ...)`, not `Runtime.addShutdownHook`. The JVM
runs separate hooks concurrently, so a separate hook races Convex's own hook,
which closes transports and every Etch file. The `SERVICE` level completes first.
