# Lattice networking responsibilities

This document maps the implementation in `convex.node` to CAD036. CAD015
defines the underlying messages and transport, while each application lattice
defines its own merge and authorisation rules.

The central boundary is simple:

- `NodeServer` replicates one assembled `LatticeHost`; the host's applications
  own the root's merge semantics.
- The calling application constructs propagation policy groups.
- The calling application composes inbound transports independently.
- Each `LatticePropagator` owns its routes, serving view and protocol work, and
  merges nothing.
- Attaching a propagator connects it to the node for publication and inbound
  delivery; it does not configure the propagator.

A node with no propagators is a valid local, store-backed node.

For TCP networking with dynamically managed groups,
`LatticeNode` supplies an optional application assembly:

```java
try (var network = new LatticeNode<>(host, config).launch()) {
    var group = network.createPropagator();
    group.setTransportKeyPair(transportKey);
    network.serveAllInbound(group);
    // Applications publish to the existing host; host.sync() publishes to the network.
}
```

`createPropagator()` creates and registers a group using the host store and starts
it when the network is running. No separate add or activation call is needed.
New groups have no peers or assigned inbound sockets. `removePropagator(group)`
revokes inbound assignments and closes that group without stopping other groups.

`serveAllInbound(group)` is an explicit public serving choice. Otherwise the listener
denies inbound access unless the caller installs a selector. The selector can change
while running; existing sockets keep their assigned group until closed or revoked. The
wrapper starts the server before the listener, closes the listener before draining
the server, and cleans up a failed launch. The host store remains caller-owned.
`NodeServer` detaches its publisher on close or failed launch, restoring local host
publication and permitting a replacement server. Only one publisher may attach at
a time. The wrapper creates no implicit propagation group and changes no group policy.

Group identity, message handlers, observers and publication filters can change
while running. A filter change reprojects the latest processed source before
returning; failed projection retains the previous filter and view. Already sent
data and cached cells cannot be revoked by changing the filter. Identity changes
apply to subsequent handshakes; established routes are not reauthenticated.

## Ownership

| Component | Owns | Does not own |
|---|---|---|
| `LatticeHost` and its applications | The published root, which applications receive incoming values, and the lattices that merge them | Replication, transport, persistence scheduling |
| `NodeServer` | Node store root, persistence barriers, attached-group lifecycle and update fan-out for one assembled host | Lattice, merge policy, sockets, peer sets, transport identity, trust, protocol decoding, acquisition, filters, discovery or application messages |
| `LatticeListener` | Application-owned TCP acceptance, physical limits and one immutable connection-to-group assignment | Propagator lifecycle, per-connection protocol state, serving stores, trust or lattice merge |
| `LatticePropagator` | One propagation policy group: publication projection, serving store, connection manager, protocol endpoint, novelty tracking and broadcast worker | Authoritative node root or application composition |
| `LatticeProtocolEndpoint` | The group's bounded ingress queue, complete-message decoding, acquisition, challenge state, statistics and extension handler | Node persistence, peer discovery, application schemas or which values are wanted |
| `LatticeConnectionManager` | Bounded desired-peer intent, dialing, retry, possession verification and admitted outbound routes | Discovery records, listener assignment, lattice merge or application-owner validation |
| `ALattice` / `LatticeContext` | Value validation, merge semantics and application signing authority | Transport identity and routing |
| Application wrapper | Construction, lifecycle order, transport composition, connection assignment, discovery translation and application handlers | Generic protocol internals |

`LatticeListener` is the public standard TCP transport.
`LatticeProtocolEndpoint` remains package-private behind the transport-facing
methods on `LatticePropagator`, so alternate transports need not access protocol
internals.

## Application composition

For custom serving stores, limits or transports, the application can assemble groups directly:

```java
NodeConfig nodeConfig = NodeConfig.port(0);
// The host and its applications own merge policy; the node replicates what the host publishes.
NodeServer<MyValue> node = new NodeServer<>(
    RelayHost.open(lattice, nodeStore, nodeConfig, nodeContext), nodeConfig);

LatticePropagatorConfig groupConfig = LatticePropagatorConfig.create();
LatticePropagator publicGroup = new LatticePropagator(servingStore, groupConfig);
publicGroup.setPublicationFilter(publicProjection);
publicGroup.setTransportKeyPair(transportKey);
publicGroup.setApplicationMessageHandler(extensionHandler);

node.addPropagator(publicGroup);
LatticeListener transport = new LatticeListener(nodeConfig);
transport.registerPropagator(publicGroup);
transport.setSelector(connection -> publicGroup);
node.launch();
transport.launch();

// Shutdown order: stop ingress before draining groups and persistence.
transport.close();
node.close();
```

`NodeConfig` contains authoritative persistence, standard-listener and application
advertisement settings. Passing it to `NodeServer` does not create a listener;
the application supplies it independently to `LatticeListener`.
`LatticePropagatorConfig` contains one group's route,
queue, acquisition and publication limits. `NodeServer.addPropagator` copies
neither object and does not copy keys, filters or handlers into the group. An
application with several groups retains their references and selects the
intended group explicitly for pulls and connection assignment.

`LatticePropagatorConfig.from(NodeConfig)` preserves combined-map callers during
migration. New composition should construct the two configurations separately;
matching key names do not imply inheritance.

## Connection capabilities

Three capabilities remain separate:

1. A physical socket exists.
2. The application assigns an inbound socket to one publication group.
3. A remote node proves possession of an expected key, permitting an
   authenticated outbound route over that socket.

Inbound assignment does not authenticate the remote. It only selects which
bounded endpoint may handle public protocol messages. An unverified connection
may submit a complete lattice value; it cannot trigger missing-cell acquisition.

An assigned connection can later be upgraded:

```text
physical inbound socket
        |
        +-- application selector --> one propagation group
        |
        +-- expected key proves possession for this challenge audience
                    |
                    v
          authenticated outbound route
```

Manager-owned outbound connections follow their own admission path:

```text
bounded desired-peer intent
        -> dialed socket in limbo
        -> expected key proves possession
        -> admitted manager-owned route
```

Zero-trust connectivity is still useful. Public complete values can be checked
by the application lattice regardless of route authentication. Trust controls
route capability and partial-data acquisition; it never replaces signed lattice
authorisation.

## Inbound pipeline

Once the listener assigns a socket, all work belongs to that group:

1. The Netty event loop offers the message to the group's bounded queue.
2. The endpoint decodes the message against the group's serving store only when
   the connection is trusted; unverified values must already be complete.
3. Protocol handlers deal with queries, values, DATA, challenges and application
   extensions.
4. The complete value is persisted in the group's store.
5. The endpoint gives only `(path, completeValue)` to `NodeServer`.
6. `NodeServer` hands the value to its host, whose applications take what they
   want from it under their own lattices, then publishes the host root to the
   node store. Which values are wanted is the applications' decision alone; the
   propagation group filters only what it serves.
7. The node schedules a non-blocking update for every attached group.

`DATA` only materialises independently addressable cells in the selected serving
store. It never merges a lattice root. `DATA_REQUEST` is scoped to the same
store, so one group cannot expose another group's private or filtered cache.

The correlated response to `LATTICE_VALUE` confirms delivery to the host and
logical root publication. It does not wait for every propagation group to
materialise or broadcast its view.

## Publication pipeline

Each group receives the host's published snapshots independently and merges
nothing. Its worker:

1. applies the publication projection;
2. announces the result into the group's serving store;
3. updates the group's queryable announced cursor; and
4. sends a bounded delta, or later a root sync, to its routes.

The update queue may coalesce consecutive monotonic snapshots. The latest value
must converge; there is no requirement for one network frame per application
action. `nextAnnounce()` is the deterministic signal for callers that need to
wait until a group has materialised its next served view.

A propagator never writes a store root. Its store is a serving and novelty
boundary; launch seeds each one from the host's current root.

## Lifecycle and failure isolation

`NodeServer.launch()` performs these phases:

1. install and freeze the node's publication policy on the host;
2. publish the host's current root (restore, if any, happened when the host was
   assembled);
3. offer that value to each attached group independently;
4. start every group independently;
5. start node persistence maintenance.

The application opens its transport only after `NodeServer.launch()` has made
the attached endpoints ready. A transport failure does not change host state;
the application decides whether to retry, replace the transport or close the
complete composition.

There is no implicit/default group. A group that fails to materialise, start or
receive a notification is logged and isolated; it cannot fail node publication
or prevent another group from progressing.

Applications can inspect `propagator.getStatus()` for lifecycle, failure count
and the most recent contained failure. `propagator.nextFailure()` returns a
one-shot future for the next failure, allowing supervision without polling or
coupling recovery policy to `NodeServer`. A running group may report prior or
intermittent failures; `Status.isOperational()` describes lifecycle, while
`Status.hasFailures()` describes observed degradation.

Shutdown is composed in the opposite order: the application closes every
transport, then `NodeServer.close()` asks each endpoint to drain, publishes the
final host root, drains each group and completes the node-store durability
barrier. Each group cleanup is independent. Endpoint cleanup also
continues after a timed-out acquisition so one stuck resource does not suppress
the remaining cleanup steps.

Failures of the node store are surfaced by `NodeServer`. Listener failures are
surfaced independently to the application that owns the transport.

### Removing a group while the node runs

Remove a group from every application-owned listener, then remove it from the
node:

```java
transport.unregisterPropagator(group); // repeat for each listener serving it
node.removePropagator(group);
```

`unregisterPropagator` revokes future assignment to that group and closes its
already assigned sockets on this listener. The listener, its other connections
and the group itself remain live. A selector that still returns the unregistered
group is rejected; update the application's routing policy to select remaining
groups or deny new connections. Existing sockets are never reassigned.

`removePropagator` removes the group from node notifications and explicit pull
APIs, drains accepted protocol and publication work, and closes inbound sockets
and outbound routes. Accepted work may still reach the host during the drain. The node and other groups keep running, and a later node launch does not
restart the removed group. Outstanding pulls that finish after removal are
rejected before merge. Both removal methods return `false` when the group
is already absent. Node removal also works before first launch or after shutdown.

Removal is a blocking lifecycle operation: call it from application management
code, outside the group's message handlers and publication callbacks. Shutdown
failures remain isolated and observable through the group's `getStatus()` and
`nextFailure()` APIs. Caller-owned stores remain open and retain their contents;
removal does not erase local data or revoke copies already held by remote peers.

Calling `group.close()` directly stops its resources but leaves it attached to
the node; use `node.removePropagator(group)` for permanent removal from that
node's lifecycle. Adding groups, registering them with listeners and configuring
filters still precede first launch/attachment as described above; these removal
operations do not introduce runtime group creation or subscription negotiation.

## Naming

- **desired**: retained bounded candidate or explicit intent for a route to an identity.
- **pending**: a manager-owned socket exists but admission is incomplete.
- **assigned**: application policy selected one group for an inbound socket.
- **trusted**: a live challenge proved possession of the expected transport key.
- **upgraded**: an assigned inbound socket is also installed as an authenticated
  outbound route.
- **owner-authorised**: signed application data passed lattice validation; this
  is independent of every transport term above.

## Live peer selection

`maxDesiredPeers` bounds candidates (default 256). The independent soft targets
`ambientPeers` and `activePeers` default to 16 each. Maintenance retains healthy
ambient incumbents, samples replacements, and prioritises the most recently used
active peers for two minutes. Explicit `connectPeer`/`addPeer` intent survives
discovery metadata updates and may exceed these targets until `removePeer`.
Pending socket opens and identity challenges reserve slots, with at most four
automatic attempts outstanding. Each attempt runs independently of maintenance.

`markActive` records useful directed traffic. `withPeer` protects an asynchronous
operation from trimming and can optionally record activity. Application pulls and
authenticated point-message routes use these hooks; background bootstrap, broadcast
gossip and PING traffic do not earn priority. Inbound and outbound routes to one key
count once. Inbound physical sockets remain owned by their listener/endpoint, so
soft targets are not an aggregate socket limit.

Maintenance runs every second, but normal propagation supplies the heartbeat and
healthy ambient peers are not periodically rotated. Only two minutes without
decoded incoming traffic triggers a probe, with a further 30-second grace period.
Any incoming traffic cancels the liveness check; a late or failed probe request
cannot evict a peer that has resumed propagation or shorten that grace period.
New identity challenges use a separate five-second admission deadline.
Known-closed sockets immediately free a slot for another candidate, independently
of the failed peer's jittered retry backoff. A probe result is tied to its exact
route and cannot retire a replacement. Failed inbound routes are retired through
the owning endpoint, allowing NAT leaves to reconnect. Trimming healthy excess
routes never closes listener-owned sockets.

## Extension rule

Application protocols belong in application wrappers. Use ingress/publication
filters, the post-merge listener, the extension-message handler and authenticated
route APIs. Do not add application paths, record parsers, discovery side effects
or connection policy to `NodeServer`.
