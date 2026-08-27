# Lattice networking responsibilities

This document is the implementation map for the `convex.node` package. CAD036
defines the Lattice Node model, CAD015 defines messages and transport trust, and
CAD038 defines signed application ownership at the merge boundary.

The essential rules are that the transport never interprets an application's
lattice schema, and that a physical connection, permission to access a
publication view, and permission to send outbound traffic are three different
capabilities.

The transport challenge key and `LatticeContext` signing/owner policy are also
separate configuration. A wrapper may deliberately bind them, but `NodeServer`
never infers transport identity from an application signing key.

## Component ownership

| Component | Owns | Does not own |
|---|---|---|
| `NodeServer` | Supplied lattice root, listener lifecycle, bounded ordered inbound dispatch, connection-to-propagator assignment, acquisition orchestration | Application paths or records, discovery policy, lattice merge rules, outbound dialing, delta construction |
| `LatticePropagator` | One filtered publication view, its store, announce/persist pipeline, delta and root broadcasts | Physical listener, peer identity policy |
| `LatticeConnectionManager` | Bounded connection intent, outbound dialing and retry, remote node-key verification, authenticated outbound routes | Discovery schemas, inbound view assignment, root merge, application-owner verification |
| `LatticeInboundVerifier` | Challenge/response for an application-admitted key and the explicit route-upgrade decision | Identity discovery, operator view assignment, application signatures |
| `ALattice` / `LatticeContext` | Foreign-value validation, merge semantics and signer authorisation for application owners | Transport identity and routing |
| `P2PNode` / `NodeDirectory` (`convex-p2p`) | `[:p2p :nodes]`, NodeInfo publication/validation, discovery translation and PoP metadata | Generic transport lifecycle and merge mechanics |

One `NodeServer` may own several propagators. Propagator zero is the primary
authoritative publication path; each additional propagator is a filtered
capability view with its own store and connection manager.

## Connection and route states

```text
operator-configured or discovered node identity
                       |
                       v
                 desired peer
                       |
                   dial socket
                       |
                       v
             verification limbo
          no store or message handler
                       |
       expected node key proves possession
                       |
                       v
       authenticated manager-owned route
       store access + reverse-message handler
```

A socket accepted by `NodeServer` follows a distinct path:

```text
physically inbound socket
        |
        +-- operator selector --> one immutable propagator view
        |
        +-- challenge proves desired node key
                    |
                    v
          explicitly upgraded outbound route
```

Operator assignment allows bounded inbound use of one view. It does not prove a
remote identity and does not make the socket an outbound propagation route.
Conversely, a manager-owned outbound connection is assigned to its manager's
propagator only after admission.

## NodeServer launch phases

`NodeServer.launch()` is an orchestrator. Its named phases run in this order:

1. Validate lifecycle and immutable configuration.
2. Create the default primary propagator if the operator supplied none.
3. Freeze the root publication policy and configure each propagator.
4. Restore persisted views and seed every store before opening network input.
5. Start the ordered dispatcher and optional listener.
6. Start maintenance, propagation and connection managers.
7. Report the generic transport as running.

Application wrappers run their own post-launch work. `P2PNode`, for example,
publishes NodeInfo only after the listener has supplied its actual bound port.

Failure in any phase runs the launch-specific unwind path. Normal close first
stops new admission, drains acquisitions and the ordered dispatcher, then drains
propagators and completes configured durability barriers.

## Inbound message pipeline

Every transport and full-duplex outbound client feeds the same ordered message
pipeline:

1. **Bounded admission** — network event loops only offer to the bounded queue.
2. **Capability selection** — the physical connection resolves to exactly one
   propagator, or has no lattice capability.
3. **Decode** — untrusted input is decoded storelessly. Trusted partial values
   may acquire missing cells into the selected propagator's store.
4. **Protocol dispatch** — query, value, data, challenge and application
   messages follow separate handlers.
5. **Merge boundary** — complete values pass ingress projection and the
   path-specific lattice merge. Signed application ownership is checked here,
   independently of transport trust.
6. **Publication** — a changed authoritative root is synchronously announced by
   the primary propagator; secondary views are then triggered.
7. **Application notification** — an optional `InboundLatticeListener` observes
   the accepted path and value. The transport attaches no meaning to either.

`DATA` only stages independently addressable cells. It never merges or publishes
a root. An untrusted connection may submit only a complete lattice value, so it
cannot use missing-cell acquisition to write arbitrary cells into a store.

## LatticeConnectionManager state

The manager keeps four deliberately separate maps:

| State | Meaning |
|---|---|
| desired peers | Bounded node identities the manager should maintain; not a connection or trust assertion |
| pending connections | Manager-owned sockets in challenge/response limbo |
| active connections | Admitted manager-owned outbound clients |
| upgraded inbound routes | Authenticated outbound capability over sockets physically owned by `NodeServer` |

Broadcasts use active connections and upgraded inbound routes, once per remote
identity. Closing the manager closes its own clients but only revokes upgraded
capability on `NodeServer`-owned sockets.

`DesiredPeer` combines immutable dial metadata with private retry state. A
discovery adapter may update transport metadata only with a newer revision.
Application metadata such as node software, Point of Presence declarations and
relay willingness stays in that application's directory; it is never copied into
the connection manager.

## Application boundary

`NodeServer` has no built-in path constants and does not inspect a root's shape.
It offers narrowly scoped integration points instead:

- `ALattice` and `LatticeContext` define validation and merge authority.
- ingress and publication filters select complete values and stored views.
- `InboundLatticeListener` observes a completed inbound merge.
- `authenticateInboundRoute` starts transport possession proof for a key already
  admitted by application policy.
- the application-message handler receives complete extension messages without
  giving them transport authority.

For P2P, `NodeDirectory` consumes these hooks: it recognises the node-registry
path, validates NodeInfo, updates generic desired-peer transports and requests an
inbound challenge. No reverse dependency from `convex-peer` to that schema is
allowed.

## Naming guide

- **desired** means the identity is admitted to the bounded reconnect set.
- **pending** means a physical outbound connection exists but identity is not
  yet admitted.
- **active** means a manager-owned outbound client is admitted.
- **assigned** means operator policy selected one inbound publication view.
- **trusted** means live challenge/response proved possession of the expected
  node key.
- **upgraded** means an authenticated inbound socket was additionally installed
  as an outbound route.
- **owner-authorised** refers only to signed application data at lattice merge;
  it is independent of all connection terms above.

## Extension rule

New application protocols should compose the generic filters/listeners, enter
through the complete-message application handler when needed, and use
authenticated route APIs for outbound relay. They must not add application path
constants, record parsers or discovery side effects inside `NodeServer`.
