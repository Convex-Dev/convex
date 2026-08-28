# Lattice networking responsibilities

This document maps the implementation in `convex.node` to CAD036. CAD015
defines the underlying messages and transport, while each application lattice
defines its own merge and authorisation rules.

The central boundary is simple:

- `NodeServer` hosts one authoritative lattice value.
- The calling application constructs propagation policy groups.
- Each `LatticePropagator` owns its routes, serving view and protocol work.
- Attaching a propagator supplies a merge destination; it does not configure the
  propagator.

A node with no propagators is a valid local, store-backed lattice host.

## Ownership

| Component | Owns | Does not own |
|---|---|---|
| `NodeServer` | Authoritative lattice cursor, merge context, node store root, persistence barriers, listener lifecycle, attached-group lifecycle and update fan-out | Peer sets, transport identity, trust, protocol decoding, acquisition, filters, discovery or application messages |
| `LatticeListener` | TCP acceptance and one immutable connection-to-group assignment | Per-connection protocol state, serving stores, trust or lattice merge |
| `LatticePropagator` | One propagation policy group: publication projection, serving store, connection manager, protocol endpoint, novelty tracking and broadcast worker | Authoritative node root or application composition |
| `LatticeProtocolEndpoint` | The group's bounded ingress queue, complete-message decoding, acquisition, ingress policy, challenge state, statistics and extension handler | Node persistence, peer discovery or application schemas |
| `LatticeConnectionManager` | Bounded desired-peer intent, dialing, retry, possession verification and admitted outbound routes | Discovery records, listener assignment, lattice merge or application-owner validation |
| `ALattice` / `LatticeContext` | Value validation, merge semantics and application signing authority | Transport identity and routing |
| Application wrapper | Construction, policy configuration, connection assignment, discovery translation and application handlers | Generic transport internals |

`LatticeListener` and `LatticeProtocolEndpoint` are package-private deliberately.
Applications compose `NodeServer`, `LatticePropagator` and
`LatticeConnectionManager`; they do not wire transport internals themselves.

## Application composition

The application must configure every group before attaching it:

```java
NodeConfig nodeConfig = NodeConfig.port(0);
NodeServer<MyValue> node = new NodeServer<>(lattice, nodeStore, nodeConfig);
node.setMergeContext(nodeContext);

LatticePropagatorConfig groupConfig = LatticePropagatorConfig.create();
LatticePropagator publicGroup = new LatticePropagator(
    servingStore, lattice, publicProjection, groupConfig);
publicGroup.setMergeContext(groupContext);
publicGroup.setTransportKeyPair(transportKey);
publicGroup.setIngressFilter(ingressPolicy);
publicGroup.setApplicationMessageHandler(extensionHandler);

node.addPropagator(publicGroup);
node.setInboundPropagatorSelector(connection -> publicGroup);
node.launch();
```

`NodeConfig` contains host listener, authoritative persistence and application
advertisement settings. `LatticePropagatorConfig` contains one group's route,
queue, acquisition and publication limits. `NodeServer.addPropagator` copies
neither object and does not copy the node lattice, context, key, filters or
handlers. An application with several groups retains their references and selects
the intended group explicitly for pulls and connection assignment.

The deprecated propagator constructors and
`LatticePropagatorConfig.from(NodeConfig)` preserve combined-map callers during
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
4. The ingress filter admits or projects a complete value.
5. The endpoint gives only `(path, completeValue)` to `NodeServer`.
6. `NodeServer` performs the authoritative lattice merge and node-root
   publication.
7. The node schedules a non-blocking update for every attached group.

`DATA` only materialises independently addressable cells in the selected serving
store. It never merges a lattice root. `DATA_REQUEST` is scoped to the same
store, so one group cannot expose another group's private or filtered cache.

The correlated response to `LATTICE_VALUE` confirms the authoritative node
merge and logical root publication. It does not wait for every propagation group
to materialise or broadcast its view.

## Publication pipeline

Each group receives authoritative node snapshots independently. Its worker:

1. reconciles the previous group view with the new node snapshot;
2. applies the publication projection;
3. announces the result into the group's serving store;
4. updates the group's queryable announced cursor; and
5. sends a bounded delta, or later a root sync, to its routes.

The update queue may coalesce consecutive monotonic snapshots. The latest value
must converge; there is no requirement for one network frame per application
action. `nextAnnounce()` is the deterministic signal for callers that need to
wait until a group has materialised its next served view.

Attached groups never write the authoritative node store root. Their stores are
serving and novelty boundaries. A separate persistent serving store is rebuilt
from the node's authoritative value during launch.

## Lifecycle and failure isolation

`NodeServer.launch()` performs these phases:

1. freeze the node root-publication policy;
2. restore and publish the authoritative node root;
3. offer the initial value to each attached group independently;
4. start every group independently;
5. open the shared listener; and
6. start node persistence maintenance.

There is no implicit/default group. A group that fails to materialise, start or
receive a notification is logged and isolated; it cannot fail node publication
or prevent another group from progressing.

Applications can inspect `propagator.getStatus()` for lifecycle, failure count
and the most recent contained failure. `propagator.nextFailure()` returns a
one-shot future for the next failure, allowing supervision without polling or
coupling recovery policy to `NodeServer`. A running group may report prior or
intermittent failures; `Status.isOperational()` describes lifecycle, while
`Status.hasFailures()` describes observed degradation.

Shutdown closes listener admission, asks every endpoint to drain, publishes the
final authoritative root, drains each group and completes the node-store
durability barrier. Each group cleanup is independent. Endpoint cleanup also
continues after a timed-out acquisition so one stuck resource does not suppress
the remaining cleanup steps.

Failures of the node's own authoritative store or listener are still surfaced:
those resources are the responsibility of `NodeServer`, not optional policy.

## Naming

- **desired**: retained bounded intent to maintain a route to an identity.
- **pending**: a manager-owned socket exists but admission is incomplete.
- **assigned**: application policy selected one group for an inbound socket.
- **trusted**: a live challenge proved possession of the expected transport key.
- **upgraded**: an assigned inbound socket is also installed as an authenticated
  outbound route.
- **owner-authorised**: signed application data passed lattice validation; this
  is independent of every transport term above.

## Extension rule

Application protocols belong in application wrappers. Use ingress/publication
filters, the post-merge listener, the extension-message handler and authenticated
route APIs. Do not add application paths, record parsers, discovery side effects
or connection policy to `NodeServer`.
