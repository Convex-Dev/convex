# Lattice networking responsibilities

This document maps the implementation in `convex.node` to CAD036. CAD015
defines the underlying messages and transport, while each application lattice
defines its own merge and authorisation rules.

The central boundary is simple:

- `NodeServer` replicates one assembled `LatticeHost`; the host's applications
  own the root's merge semantics.
- The service ([anatomy](../../convex-core/docs/LATTICE_APPLICATIONS.md#service-anatomy))
  constructs propagation policy groups.
- The service composes inbound transports independently.
- Each `LatticePropagator` owns its routes, serving view and protocol work, and
  merges nothing.
- Attaching a propagator connects it to the node for publication and inbound
  delivery; it does not configure the propagator.

A node with no propagators is a valid local, store-backed node.

For TCP networking with dynamically managed groups,
`LatticeNode` supplies an optional application assembly:

```java
try (var network = new LatticeNode<>(host, config).launch()) {
    network.setIdentity(nodeDID, nodeKey, didAuthorizer);
    var group = network.createPropagator();
    network.serveAllInbound(group);
    // Applications publish to the existing host; host.sync() publishes to the network.
}
```

`createPropagator()` creates and registers a group using the host store and starts
it when the network is running. No separate add or activation call is needed.
New groups have no peers or assigned inbound sockets. A group reads its limits from
its own configuration, never the node's: `createPropagator(groupConfig)` supplies
one, and the no-argument form uses defaults. Both publish the host's value
unfiltered; `createPropagator(groupConfig, filter)` gives the group its publication
filter from the start and refuses a null one. `removePropagator(group)` revokes the
group's inbound assignments on every listener and closes it without stopping other
groups.

`LatticeNode` is the standard TCP assembly: the DLFS server, `convex dlfs start` and
`P2PNode` use it. A host replicated without a listener, such as a Convex DB managed
instance or the GUI DLFS browser, uses `NodeServer` directly.

The node may configure a primary DID and an authorised key pair with `setIdentity`.
The two-argument overload validates `did:key`; other methods require an explicit
`DIDKeyAuthorizer` backed by authenticated resolver data. The binding is checked
when configured. New groups use that key by default; without a configured identity
their key is null. No transient keys are generated. Group-specific key overrides
remain possible, and existing groups keep their keys when the node default changes.
The primary DID is local configuration: remote peers still verify possession of
the expected public key, resolving any DID binding through their own trust policy.

`serveAllInbound(group)` is an explicit public serving choice on the node's own
listener. Otherwise a listener denies inbound access unless the caller installs a
selector. The selector can change while running; existing sockets keep their assigned
group until closed or revoked. `addListener(listenerConfig)` adds a listener on another
port, with its own connection and message limits, for groups reached separately, such
as a trusted or replication port. Any group can be assigned through any listener, and
a new listener denies every connection until given a selector. It opens with the
node, or at once if the node is running. The wrapper starts the server before its
listeners, closes every listener before draining the server, and cleans up a failed
launch. The host store remains caller-owned.
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
| Service | Construction, lifecycle order, transport composition, connection assignment and network policy, such as discovery and application message handlers | Generic protocol internals |

`LatticeListener` is the public standard TCP transport.
`LatticeProtocolEndpoint` remains package-private behind the transport-facing
methods on `LatticePropagator`, so alternate transports need not access protocol
internals.

## Application composition

For custom serving stores or transports, the application can assemble groups directly:

```java
NodeConfig nodeConfig = NodeConfig.port(0);
// The host and its applications own merge policy; the node replicates what the host publishes.
NodeServer<MyValue> node = new NodeServer<>(
    RelayHost.open(lattice, nodeStore, nodeContext), nodeConfig);

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

// Shutdown (see SHUTDOWN.md): closes the host, then the node, then the store
try (nodeStore; node; var host = node.getHost()) {
    transport.close();
    node.stopIngress();
}
```

`NodeConfig` contains authoritative persistence, standard-listener and application
advertisement settings. Passing it to `NodeServer` does not create a listener;
the application supplies it independently to `LatticeListener`.
`LatticePropagatorConfig` contains one group's route,
queue, acquisition and publication limits. `NodeServer.addPropagator` copies
neither object and does not copy keys, filters or handlers into the group. An
application with several groups retains their references and selects the
intended group explicitly for pulls and connection assignment.

Both configurations are CVM maps; the Java classes are typed views of them.
`LatticePropagatorConfig.from(NodeConfig)` reads the node's map as a group's, which
is how the DLFS server and `P2PNode` let one config section carry both. A key is then
read by every component that reads that name: `maxConnections` caps the listener's
sockets and also sets when each group sweeps its per-connection statistics. New
composition gives the node and each group a map of its own.

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

A group holds one upgraded route per key. When another connection proves a key whose
route is live, the group pings that route: if it answers within the five-second
admission deadline it is kept and the new connection is closed; otherwise the new
connection replaces it. A route that still works is not taken over, and a node that
reconnects over a socket that has silently died regains its route within that deadline.

Manager-owned outbound connections follow their own admission path:

```text
bounded desired-peer intent
        -> dialed socket in limbo
        -> expected key proves possession
        -> admitted manager-owned route
```

A node known only by its endpoint, named with `connectPeer(URI)`, is challenged for
no particular key, then again naming the key it proved. The key becomes explicit
intent at that endpoint, so reconnections challenge for that key. This proves the node holds the key it names, not
that it is the node the caller meant; over `tls://` the certificate vouches for the
host name first. A node that cannot be reached or proves no key gets three attempts,
with the usual retry backoff, then the caller's future fails, so a service that needs
its peers in order to start fails promptly rather than waiting on one that is not
there.

Operators name peers the same way wherever a peer is named: `tcp://host:port`,
`tls://host:port`, `host:port` (a missing port is the default peer port), `did:web` or
`did:key`, optionally beside an expected
key (`<key>@<peer>`, or `{key, url}` in configuration). `PeerResolver.parse` checks a
name without network access, and `PeerResolver.resolve` turns it into a target. A
transport name is dialled as written. A DID is resolved through the shared
`DIDResolver`, which fetches a `did:web` document over HTTPS: the peer must prove a key
the document lists under `authentication`, and is dialled at the endpoints of its
`ConvexLatticeNode` services ([CAD043](https://docs.convex.world/docs/cad/did)). A
`did:key`, or a document without those services, is located by key in the node's
records, which a node that keeps them supplies to its resolver; without a record the
name fails as having no known address. An expected key narrows the target to that key.

What an operator relies on depends on how the peer is named. A `tcp://` address proves
nothing about who answers: the peer is admitted under whatever key it proves. Over
`tls://` the JVM trust store vouches for the host name first. An expected key is the
operator's own copy, and the address only locates it. A `did:web` rests on whoever
controls the domain's web server and certificate, who can name any key; an expected key
beside it guards against a compromised domain. A `did:key` names the key itself, and
its address comes from a node record the key holder signed. The resolver resolves
whichever DIDs it is asked for, bounding each fetch in size and time, so a caller that
resolves DIDs arriving from the network sets its own limits on how many and how often.

A peer resolved from its name is a `PeerTarget`: the keys it may prove, none meaning
any, and the endpoints to dial it at. `connectPeer(PeerTarget)` makes one bounded
attempt, dialling the endpoints in turn, each at least once and three times in all at
least, with the usual backoff. It challenges for the target's key when it names one;
otherwise it learns the node's key as above and admits it only if the target names no
keys or lists it. The admitted route is held: kept, safe from trimming, but never
redialled nor given an address from discovery. When it closes the peer is forgotten
and `whenDisconnected(key)` completes, and what happens next is the caller's; a caller
wanting the manager to reconnect by itself declares desired intent instead, and a peer
that already has it keeps it. Cancelling the attempt abandons it.

`LatticeNode.connectPeer(group, name)` resolves a peer name with the node's
`PeerResolver` and makes that one attempt. `LatticeNode.keepPeer` keeps a peer an
operator named: when it cannot be reached or its route goes, the name is resolved
again before the next attempt, after the usual backoff, so a moved endpoint or a
changed DID document is picked up and a key the document no longer lists is not
admitted again. A peer named by a transport URI without a key is held to the key it
first proved. A peer unreachable for a whole period, ten minutes by default, is given
up and reported; each lost route starts a new period.

A group can also gossip with nodes that connect to it. With
`setChallengerAdmission(predicate)`, a node that challenges the group over an inbound
connection, naming the group's transport key, is challenged back if the predicate
admits its key, and proving it upgrades the connection. The predicate is how the
application admits keys, as CAD036 requires. A challenge naming no key, or another
node's, is answered but admits nothing: it could be a replay of one the challenger
sent elsewhere. Admission spends the group's bandwidth on the node, bounded by the
listener's connection limit, and grants no trust in the values it sends.

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
action. `whenAnnounced(condition)` is the deterministic signal for callers that
need to wait until a group serves a state: it completes once an announced value
satisfies the condition and that value's broadcast is queued. See
[persistence](PERSISTENCE.md#waiting-in-tests-and-applications).

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

Shutdown runs in the opposite order; [shutdown](SHUTDOWN.md) gives the full
sequence. Each group's cleanup is independent, and endpoint cleanup continues
after a timed-out acquisition, so one stuck resource does not suppress the
remaining steps.

Store failures while the node runs are surfaced by `NodeServer` and host sync;
saving failures by the host's `checkpoint()` or `flush()`. Listener failures are
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
- **held**: an explicit route admitted from a resolved peer target, kept while it
  lasts and never redialled.
- **pending**: a manager-owned socket exists but admission is incomplete.
- **assigned**: application policy selected one group for an inbound socket.
- **trusted**: a live challenge proved possession of the expected transport key, or of
  the key a node known only by its endpoint named.
- **upgraded**: an assigned inbound socket is also installed as an authenticated
  outbound route.
- **owner-authorised**: signed application data passed lattice validation; this
  is independent of every transport term above.

## Live peer selection

A discovered node is a candidate only while it advertises a supported transport
to dial; `maxDesiredPeers` bounds candidates (default 256). Explicit
`connectPeer`/`addPeer` intent is not counted and is never refused for capacity,
and inbound route upgrades do not depend on it. The independent soft targets
`ambientPeers` and `activePeers` default to 16 each. Maintenance retains healthy
ambient incumbents, samples replacements, and prioritises the most recently used
active peers for two minutes. Explicit `connectPeer`/`addPeer` intent survives
discovery metadata updates and may exceed these targets until `removePeer`, or until
`releasePeer` withdraws it: that closes the route this node opened, keeps any route
the peer opened, and leaves the peer a candidate only if discovery lists it.
`whenAmbientTargetMet` completes once routes to peers that are not explicit fill the
ambient target, and number at least one, which is when an application can release
peers it used as seeds. `whenIsolated` completes once a maintenance pass finds no
authenticated route in either direction, or at once when there is none, which is when
it can seed again.
Pending socket opens and identity challenges reserve slots, with at most four
automatic attempts outstanding, attempts to reach a node known only by its endpoint
among them. Each attempt runs independently of maintenance.

`markActive` records useful directed traffic. `withPeer` protects an asynchronous
operation from trimming and can optionally record activity. Application pulls and
authenticated point-message routes use these hooks; background bootstrap, broadcast
gossip and PING traffic do not earn priority.

The targets count only routes this node opens. A peer reached only through an
authenticated inbound route takes no ambient or active slot and is not dialled, so
peers that connect in cannot displace the ones this node chose. Inbound physical
sockets remain owned by their listener/endpoint, so soft targets are not an
aggregate socket limit.

Maintenance runs every second, but normal propagation supplies the heartbeat and
healthy ambient peers are not periodically rotated. Each end also sends its root to
every route every 30 seconds, changed or not, which keeps NATs and load balancers
from dropping a quiet route. A live route is probed only after `keepAlive` (60
seconds by default, two root syncs) without decoded incoming traffic, with a further
30-second grace period, so a healthy route is never probed and a silent one is
retired within 90 seconds. Keep `keepAlive` above the root-sync interval, or probes
race the root sync. Nodes without a route are never probed.
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
