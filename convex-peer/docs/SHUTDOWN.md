# Lattice service shutdown

A service running a lattice node closes four things in order: its own request
handling, the host, the node's networking and the store. The order saves the
application's state and sends the saved state to peers before the store closes.

## Order

1. Stop the service's requests, so no new work is admitted.
2. Sync the application drafts the service wants kept.
3. `host.close()`. The host stops automatic flushing and saves a final checkpoint,
   which the still-attached node publishes to its groups.
4. `node.close()`. The node closes its listeners. Each group stops admitting
   protocol work, drains what it accepted and its publication, waits for the
   resulting broadcasts to be written to each outbound route, then closes its
   routes. The drain and the wait share the group's `inboundShutdownTimeout`.
5. Close the store.

Networking runs until step 4. A merge from a peer that lands after the drafts sync
is not saved, and one that arrives after the host closes is refused. The peer still
holds it, and the two converge at their next exchange.

## In code

```java
try (store; node; host) {
    requests.close();     // the service's own front end
    application.sync();   // drafts to keep
}                         // closes the host, then the node, then the store
```

A front-end close or drafts sync that can fail catches its own failure, so it does
not lose the drafts. A service without drafts leaves out the sync. A node assembled
from `NodeServer` and `LatticeListener` closes its listeners before the
`NodeServer`.

## What each close leaves alone

- Closing the host neither syncs applications nor closes the store. A closed host
  refuses syncs, checkpoints and flushes. If its final checkpoint fails, the host
  stays open and closing it again retries.
- Closing the node neither publishes pending host state nor flushes the store; it
  sends only what the host has already published. It never closes the store, which
  belongs to whoever created it. The host and its applications remain usable.
- Closing the node before the host also shuts down safely, but peers then miss the
  final checkpoint.

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
