# Lattice service shutdown

A service running a lattice node closes four things in order: its own request
handling, the node's networking, the host and the store. The order keeps every
change networking accepted, saves it, and sends the saved state to peers before
the store closes.

## Order

1. Stop the service's requests and close its listener, so no new work is admitted.
2. `node.stopIngress()`. Each propagation group drains the inbound work it has
   accepted, then the node stops accepting deliveries: an ingress worker that
   overran its drain, or a pull that completes later, is rejected rather than
   merged. From here networking no longer changes the host. Publication continues.
3. Sync the application drafts the service wants kept. They now include every
   merge networking accepted.
4. `host.close()`. The host stops automatic flushing and saves a final checkpoint,
   which the still-attached node publishes to its groups.
5. `node.close()`. Each group drains its publication, waits for the resulting
   broadcasts to be written to each outbound route, then closes its routes. The
   drain and the wait share the group's `inboundShutdownTimeout`.
6. Close the store.

## In code

```java
try (store; node; host) {
    requests.close();     // the service's own front end
    node.stopIngress();   // listener closed, accepted inbound work landed
    application.sync();   // drafts to keep, including what networking merged
}                         // closes the host, then the node, then the store
```

Keep `stopIngress()` inside the block, so a failure there still closes all three
resources. A service without drafts leaves out the sync.

`LatticeNode.stopIngress()` and `P2PNode.stopIngress()` close their listener
first. A node assembled from `NodeServer` and `LatticeListener` closes the
listener, then calls `NodeServer.stopIngress()`.

## What each close leaves alone

- Closing the host neither syncs applications nor closes the store. A closed host
  refuses syncs, checkpoints and flushes. If its final checkpoint fails, the host
  stays open and closing it again retries.
- Closing the node neither publishes pending host state nor flushes the store; it
  sends only what the host has already published. It never closes the store, which
  belongs to whoever created it. The host and its applications remain usable.
- `node.close()` stops ingress itself when needed, so closing the node before the
  host also shuts down safely. Peers then miss the final checkpoint.

## The final broadcast

The final broadcast is best effort:

- it reaches outbound routes only, because closing the listener closes the
  connections that peers opened to this node;
- written to a socket is not acknowledged by the peer;
- once ingress has stopped, the node no longer answers data requests, so a peer
  receives the complete final value only when it fits one delta broadcast.

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
