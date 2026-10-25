# Convex P2P

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-p2p.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-p2p/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-p2p)

Rollup package for Convex P2P nodes. Builds on the lattice data structures in
`convex-core` and the node, propagator and transport components in
`convex-peer` to provide nodes that discover each other, exchange lattice values
and converge on shared state — and bundles the application regions those nodes
serve, so one dependency gives you a complete node.

> **Status: early implementation.** Authenticated one-peer bootstrap, signed
> NodeInfo discovery, bounded desired-peer state, follow-filtered social replication,
> outbound-only NAT leaf nodes and opt-in public Point of Presence message relay work
> over TCP. On-chain bootstrap, direct hole punching, region-aware connection selection
> and additional transports remain to be built.

## Lattice structure

`P2PLattice.ROOT` declares only what the P2P system itself needs to know and merge —
none of the application regions from `Lattice.ROOT`:

```
P2PLattice.ROOT (KeyedLattice)
├── :p2p → KeyedLattice                        shared node registry
│     └── :nodes → OwnerLattice(LWWLattice)      node key → Signed(NodeInfo)
├── :id  → OwnerLattice(LWWLattice)            user key → Signed(IdentityInfo)
└── :kad → ReservedLattice                     reserved, nothing merges yet
```

**`:p2p`** — the shared node registry. Each node publishes a signed, LWW `NodeInfo`
(transports, PoPs, relay willingness, regions, version, timestamp) under its node
`AccountKey` at `[:p2p :nodes <nodeKey>]`. Reuses core's region instance directly, so registry merge
semantics cannot drift between this root and `Lattice.ROOT`, and
`NodeDirectory` publishes and validates this application-owned path while the
generic propagation layer transports it without interpreting its schema.

**`:id`** — P2P user identity, separate from transport details, so one identity can
advertise several nodes and change its claims without republishing node records.

**`:kad`** — reserved for Kademlia routing. The path is claimed and stable, but the
merge semantics are still open (`P2P_DESIGN.md` §4.3 and decision 13.2 lean towards
k-buckets being node-local rather than propagated), so it is backed by core's
`convex.lattice.generic.ReservedLattice`, which discards anything sent there. Discarding
rather than throwing matters: an unimplemented lattice that throws would abort the whole
enclosing merge, costing a peer its valid sibling regions and counting against
the propagation group's per-connection circuit-breaker.

### Independent top-level regions

The three regions are siblings rather than nested, because a lattice node ignores any
top-level region it does not recognise — `AKeyedLattice` merge iterates its *registered*
keys, so an incoming key with no registered lattice is never visited and is dropped.

A node running `Lattice.ROOT` therefore merges `:p2p` normally and discards `:id` and
`:kad` without error; a P2P node discards `:data`/`:fs`/`:kv`/`:queue` the same way.
Regions can be added, adopted and retired independently. Both directions are covered by
tests.

### Owner binding

Both populated regions use `OwnerMapLattice` with direct-key verification bound
to the lattice instance. Both context-free and context-aware merge require the
signature's public key to equal the owner key. A permissive context cannot change
that rule. Identity and node records use whole-value LWW: a relay selects an
existing envelope, retaining its signature without needing any private key.

Local writes receive an explicit owner-aware signer, `(owner, value) -> signed`.
One signer may use a wallet or keystore to serve several identities. Returning
null means an owner is unavailable; a local write then fails without changing
state. Signer output must cover the requested value, have a valid signature and
pass the same owner rule as incoming delivery.

`P2PApplication.connect(root, host, ownKey, signer, clock, didAuthoriser)` supplies
the node's public identity independently of signing capability. `NodeDirectory`
uses that public key and the supplied `Supplier<CVMLong>` to publish node records.
`P2PUser.create(application, owner, signer)` opens explicit authoring views;
the two-argument overload is a read view. `P2PApplication.p2p(owner)` uses the
application's configured signer.

The application binds the same DID authoriser to social delivery and local social
views. Registering multiple local DIDs returns one shared `Social` component whose
signer looks up each DID's key. Key replacement is observed by existing views.
Cached follow keys are metadata; they cannot bypass current authority after
rotation or revocation. A relay accepts signed social indexes without requiring
every field to match this client's schema.

P2P incoming merges use the supplied lattice's own rules. Timestamp admission
belongs to each application or component: for example, a DLFS lattice can bind
its clock and allowance with `DLFSLattice.withClock(clock, skew)`. Node configuration
does not impose a shared allowance. Social and P2P domain views do not inherit
authoring capabilities or mutation clocks from `LatticeContext`.

## A rollup package

convex-p2p is a **rollup**: one dependency that gives you a complete, runnable P2P node.
It aggregates three things that would otherwise have to be assembled by hand —

- the P2P infrastructure regions (`:p2p`, `:id`, `:kad`) it defines itself,
- the application regions bundled with a node (currently `:social`),
- and the node server that serves them, `P2PNode`.

so that operators run *one* node rather than a P2P one and a social one. Which of the
rolled-up regions a node actually serves is a **per-node choice, not a per-build one**.

Rolling up is deliberately one-directional. convex-p2p depends on the applications it
bundles; they do not depend on it, and each stays usable on its own — convex-social works
standalone, with no P2P node in sight. Adding a region to the bundle is a convex-p2p
decision, and the only place that decision is recorded.

```
        ┌─────────────┬─────────────┬─────────────┐
        │   :social   │    :sql     │     ...     │   application regions (optional)
        ├─────────────┴─────────────┴─────────────┤
        │        :p2p    :id    :kad              │   infrastructure floor (always on)
        ├─────────────────────────────────────────┤
        │ NodeServer — replicates the host root   │   convex-peer
        │ Propagator — filtered view + routes      │
        ├─────────────────────────────────────────┤
        │   lattice types, cursors, CAD3, Etch    │   convex-core
        └─────────────────────────────────────────┘
```

Two region sets are provided — the whole rollup, or the infrastructure floor:

| | Regions | Use |
|---|---|---|
| `P2PLattice.NODE_ROOT` | infrastructure + bundled apps | default |
| `P2PLattice.ROOT` | infrastructure only | applications switched off |

```java
P2PNode node = P2PNode.create(store, config, keyPair);                      // social on
P2PNode relay = P2PNode.create(store, config, keyPair, P2PLattice.ROOT);    // social off
```

Both are the same class serving the same infrastructure regions, so a node with social
switched off is a **fully capable discovery node** — nothing about its P2P role is
diminished. That is why the split is a configuration knob rather than a second binary:
the overlap between "a P2P node" and "a social node" is nearly all of it.

Region sets do not have to match across a network. An unrecognised top-level region is
ignored on merge rather than rejected, so a `ROOT` node and a `NODE_ROOT` node
interoperate on everything they share — which is what makes switching a region off a
safe local decision, and lets an application be rolled out to part of a network without
a coordinated upgrade.

A region outside the rollup composes the same way `NODE_ROOT` composes social:

```java
KeyedLattice root = P2PLattice.NODE_ROOT.addLattice(MyApp.KEY, MyApp.LATTICE);
```

A region that should ship with every node instead becomes part of the rollup: add the
dependency to this module's POM and the region to `NODE_ROOT`, and every node picks it
up with the option to switch it off.

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-p2p</artifactId>
    <version>0.8.18</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-p2p:0.8.18'
```

## Usage

```java
import convex.p2p.P2PNode;
import convex.core.crypto.AKeyPair;
import convex.etch.EtchStore;
import convex.node.NodeConfig;

AKeyPair keyPair = AKeyPair.generate();
EtchStore store = EtchStore.createTemp("p2p");

try (P2PNode node = P2PNode.create(store, NodeConfig.port(18888), keyPair)) {
    node.serveAllInbound();   // intentionally public single-view node
    node.launch();

    P2PApplication app = node.getApplication();

    // Modify this user's identity component, then publish the application root
    P2PUser me = node.p2p(keyPair.getAccountKey());   // or node.p2p()
    me.identity().setIdentity(Strings.create("alice"), null, ts);
    app.sync();
}
```

For an isolated two-node network, use OS-assigned ports and tell only one node
about the other:

```java
P2PNode alice = P2PNode.create(aliceStore, NodeConfig.localNetwork(), aliceKey)
    .serveAllInbound();
P2PNode bob = P2PNode.create(bobStore, NodeConfig.localNetwork(), bobKey)
    .serveAllInbound();
alice.launch();
bob.launch();

// Bob proves his key; Alice pushes only her own signed NodeInfo entry, then
// pulls :p2p, :id and Alice's currently desired social-owner paths.
alice.connect(bobKey.getAccountKey(), bob.getHostAddress()).join();

// Bob independently authenticates Alice on that same socket before upgrading it
// from an inbound connection to an outbound propagation route.
bob.whenInboundConnectionUpgraded(aliceKey.getAccountKey()).join();

// Subsequent application changes gossip in both directions.
alice.getApplication().sync();
```

`connect` completes after the bootstrap endpoint is authenticated and has
acknowledged the path-scoped `[:p2p :nodes]` update, and after the connecting
node has pulled and merged the bootstrap node's infrastructure regions and current
desired social-owner slots. It never bootstraps from an unrestricted full root.
A node joining an established network therefore obtains its follow-filtered view
without waiting for another publication or periodic root sync.

For a node behind NAT, give `P2PNode` a local-only transport configuration. Its
`NodeDirectory` signs and publishes NodeInfo with an empty `:transports` vector, so
other nodes know its identity without trying to dial it. The node's authenticated
outbound connection remains full-duplex:

```java
P2PNode dave = P2PNode.create(daveStore, NodeConfig.port(-1), daveKey);
dave.launch();
dave.connect(bobKey.getAccountKey(), bob.getHostAddress()).join();

// Bob may send lattice updates back through Dave's original outbound socket only
// after Dave has answered Bob's independent challenge.
bob.whenInboundConnectionUpgraded(daveKey.getAccountKey()).join();
```

This distinction is intentional. Assigning an inbound socket to a propagator permits
the operator-selected inbound lattice view, but leaves the connection untrusted. It
becomes an outbound gossip route only after challenge/response proves the node key and
that key has an admitted signed NodeInfo record. A NAT leaf does not need
`serveAllInbound()` merely to receive reverse traffic from a peer it connected to and
authenticated; arbitrary incoming sockets remain denied by default.

For routed point-to-point messages, the leaf declares that same peer as a PoP and the
public node opts into relay service:

```java
P2PNode dave = P2PNode.create(daveStore, NodeConfig.port(-1), daveKey)
    .pointsOfPresence(bobKey.getAccountKey());
P2PNode bob = P2PNode.create(bobStore, NodeConfig.localNetwork(), bobKey)
    .serveAllInbound()
    .relayMessages();

dave.setMessageHandler(message -> consume(message.sender(), message.value()));
alice.sendMessage(daveKey.getAccountKey(), Strings.create("hello"));
alice.sendPrivateMessage(daveKey.getAccountKey(), Strings.create("secret"));
```

Messages are end-to-end signed by the source node. Private bodies use the existing
ECIES wrapper; relays see only routing metadata and ciphertext. See
[Points of Presence](docs/POINTS_OF_PRESENCE.md) for the wire format, routing rules,
bounds and trust model.

`P2PNode` assembles the node up to the lattice root: the host opened from the store,
the replicating node server, one propagation group and the transport. `P2PApplication`
is the application the host hands incoming values to. It owns the P2P policy that
reads the lattice and imports nothing from the node package, so it can also be
connected directly to a standalone `LatticeHost` for local use.

The owning service chooses durability through `node.getNodeServer().getHost()`:
call `checkpoint()` for a durable save, or publish with `sync()` and use a scoped
`autoFlush(Duration)` for batched barriers. Stop request handling and networking
before the final service save; close the durability resource before its store.
`P2PNode.close()` stops replication without accepting pending host edits or
flushing the borrowed store.

### The user area API

One user has data in two independent regions. `P2PIdentity` represents
`[:id <userKey> :value]`; `P2PNodeRecord` represents
`[:p2p :nodes <userKey> :value]`. Both are path-specific components already through
the signing boundary. `P2PUser` is a convenience facade that contains them rather
than pretending the two paths are one component:

```java
P2PUser me = node.p2p();              // own area, using the node's key pair
me.identity().setIdentity(identityMap); // signed on write
me.identity().sync();                 // merge this working path

me.node().cursor()                    // independent node-record component

P2PUser draft = me.fork();            // identity edits, isolated
draft.cursor().set(...);
draft.sync();                         // merged back under LWW
node.getApplication().sync();         // publish the complete root
```

Each component cursor is scoped to one user and one region; no other user's data is
reachable through it. `node.p2p(someoneElse)` is a readable facade over their
published components. Writes require an authorised signer for that owner
(see [Owner binding](#owner-binding) above). A facade fork isolates identity edits;
use `me.node().fork()` to stage the independent node-record component.

Or run a node standalone:

```bash
java -cp convex.jar convex.p2p.P2PNode [etch-file]
```

## Live peer selection

The desired-peer list is a candidate pool (`maxDesiredPeers`, default 256), not
a target number of concurrent connections. Each propagation group normally keeps
16 ambient peers plus up to 16 recently active communicators. Configure the soft
targets with `LatticePropagatorConfig.AMBIENT_PEERS` (`ambientPeers`) and
`ACTIVE_PEERS` (`activePeers`); either may be zero.

Healthy ambient routes stay stable and vacant slots sample eligible candidates.
Directed point messages and application-requested pulls earn two minutes of active
retention; gossip, bootstrap and keepalives do not. Explicit `connect`/`addPeer`
requests remain preferred until removed and may exceed the targets. Outstanding
requests are protected from trimming. Authenticated inbound routes count as existing
connectivity, without imposing a new physical-socket cap on NAT return paths.

Maintenance checks every second, but normal incoming propagation acts as the
heartbeat: healthy ambient peers have no scheduled rotation. Only after two minutes
without decoded incoming traffic does it send a correlated PING, allowing a further
30 seconds of silence before retiring the route. Any incoming traffic cancels the
liveness check, even if the PING reply is late or the request itself times out early.
Known-closed routes release their slots immediately; their own retries back off while
other candidates fill the vacancies. Automatic socket opening and identity admission
together are limited to four concurrent attempts; new identity challenges retain a
separate five-second deadline. Physical inbound sockets that remain silent are
retired by their owning endpoint so an outbound-only node can reconnect.

## Security

Inbound network lattice traffic is **denied by default**. A node serves queries and
accepts values only once the operator assigns inbound connections to a propagator —
either via `serveAllInbound()` for a public single-view node, or a custom policy set
on the application-owned listener returned by `getTransport()`. See
`convex-peer`'s `LatticeListener` and `NodeServer` for the full capability model.
Operator assignment is not authentication and does not add the connection to
outbound gossip. That separate upgrade requires live
challenge/response plus an admitted node identity. The challenge and response both
have verified Ed25519 signatures and bind a random nonce, the opposite party's node
key as audience, and the fixed `convex-lattice-peer-v1` context.

An unverified assigned connection may submit complete `LATTICE_VALUE` messages, because
the P2P data is public, but it cannot stage unsolicited `DATA` or trigger missing-cell
acquisition. Complete values are handed to the P2P application, which takes the
infrastructure regions whole and, from the social region, only the owners it wants:
locally registered social DIDs, explicit pins and their direct active follows. The
same set bounds what the node publishes. Every social slot must have a valid signature
from a key authorised for its DID. `maxConnections` bounds inbound
sockets and `maxDesiredPeers` bounds explicit plus discovery-driven peers.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-p2p is provided under the [Convex Public License](../LICENSE.md).
