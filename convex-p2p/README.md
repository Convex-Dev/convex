# Convex P2P

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-p2p.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-p2p/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-p2p)

Rollup package for Convex P2P nodes. Builds on the lattice data structures in
`convex-core` and the `NodeServer` binary networking in `convex-peer` to provide nodes
that discover each other, exchange lattice values and converge on shared state — and
bundles the application regions those nodes serve, so one dependency gives you a
complete node.

> **Status: early implementation.** Authenticated one-peer bootstrap and signed
> NodeInfo discovery work over TCP. On-chain bootstrap, region-aware connection
> selection, connection bounds and additional transports remain to be built.

## Lattice structure

`P2PLattice.ROOT` declares only what the P2P system itself needs to know and merge —
none of the application regions from `Lattice.ROOT`:

```
P2PLattice.ROOT (KeyedLattice)
├── :p2p → KeyedLattice                        shared node registry
│     └── :nodes → OwnerLattice(LWWLattice)      user key → Signed(NodeInfo)
├── :id  → OwnerLattice(LWWLattice)            user key → Signed(IdentityInfo)
└── :kad → ReservedLattice                     reserved, nothing merges yet
```

**`:p2p`** — the shared node registry. Each P2P user publishes a signed, LWW `NodeInfo`
(transports, regions, version, timestamp) under their own `AccountKey` at
`[:p2p :nodes <userKey>]`. Reuses core's region instance directly, so registry merge
semantics cannot drift between this root and `Lattice.ROOT`, and
`NodeServer.publishNodeInfo` works unchanged.

**`:id`** — P2P user identity, separate from transport details, so one identity can
advertise several nodes and change its claims without republishing node records.

**`:kad`** — reserved for Kademlia routing. The path is claimed and stable, but the
merge semantics are still open (`P2P_DESIGN.md` §4.3 and decision 13.2 lean towards
k-buckets being node-local rather than propagated), so it is backed by core's
`convex.lattice.generic.ReservedLattice`, which discards anything sent there. Discarding
rather than throwing matters: an unimplemented lattice that throws would abort the whole
enclosing merge, costing a peer its valid sibling regions and counting against
`NodeServer`'s per-connection circuit-breaker.

### Independent top-level regions

The three regions are siblings rather than nested, because a lattice node ignores any
top-level region it does not recognise — `AKeyedLattice` merge iterates its *registered*
keys, so an incoming key with no registered lattice is never visited and is dropped.

A node running `Lattice.ROOT` therefore merges `:p2p` normally and discards `:id` and
`:kad` without error; a P2P node discards `:data`/`:fs`/`:kv`/`:queue` the same way.
Regions can be added, adopted and retired independently. Both directions are covered by
tests.

### Owner binding

Both populated regions are `OwnerLattice`s keyed by `AccountKey`, so a user can only
write their own slot. Three points are pinned by tests:

**One authorisation rule, applied at both boundaries.** `LatticeContext.signAs` decides
whether this node may author a value for an owner, and it is the same rule
`LatticeContext.verifyOwner` applies to data arriving from a peer. An owner that is an
`AccountKey` requires that key; an indirect owner (Address, DID) is resolved by the
installed owner verifier, and stays lenient when there is none.

**Owner paths request their signer on write.** A direct local write through a signed
owner path asks the installed `LatticeContext` for a signer authorised for that owner.
A key-store-backed policy can supply any accessible identity, whether or not it is the
primary key; without one the write throws rather than storing a slot no peer would
accept.

**A merge never fails over an owner you cannot author.** Most merges select one of the
two signed values and need no signature. When the inner lattice synthesises a genuinely
new value, `SignedLattice` signs it as the owner if it can and otherwise keeps the own
value — so merging a peer's data converges the owners this node holds keys for and
leaves the others to their owners, instead of aborting the whole merge.

**The two-argument merge skips the check entirely.** `merge(own, other)` does not verify
the signer against the owner key — only the context-aware overload does. Every real path
is safe (`ALatticeCursor.merge` always passes a context, and `LatticeContext.EMPTY` still
triggers the `AccountKey` fast path), but don't reach for a raw `ALattice.merge(a, b)`
on these regions.

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
        │   NodeServer — merge, gossip, transport │   convex-peer
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
    <version>0.8.15</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-p2p:0.8.15'
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

// Bob proves his key; Alice then pushes only her own signed NodeInfo entry.
alice.connect(bobKey.getAccountKey(), bob.getNodeServer().getHostAddress()).join();

// Bob learns Alice's transport from that entry and connects back automatically.
bob.whenConnected(aliceKey.getAccountKey()).join();

// Subsequent application syncs gossip in both directions.
alice.getApplication().sync();
```

`connect` completes after the bootstrap endpoint is authenticated and has
acknowledged the path-scoped `[:p2p :nodes]` update. It does not send the whole
application root as part of discovery.

`P2PNode` is the network bootstrap and lifecycle owner. `P2PApplication` is the
host-neutral lattice application component; it can also be connected directly to a
standalone `RootComponent` for local use.

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

P2PUser draft = me.fork();            // batch edits, isolated
draft.cursor().set(...);
draft.sync();                         // merged back under LWW
node.getApplication().sync();         // publish the complete root
```

Each component cursor is scoped to one user and one region; no other user's data is
reachable through it. `node.p2p(someoneElse)` is a readable facade over their
published components; writing it is a mistake the lattice does not need to prevent
(see [Owner binding](#owner-binding) above).

Or run a node standalone:

```bash
java -cp convex.jar convex.p2p.P2PNode [etch-file]
```

## Security

Inbound network lattice traffic is **denied by default**. A node serves queries and
accepts values only once the operator assigns inbound connections to a propagator —
either via `serveAllInbound()` for a public single-view node, or a custom policy set
with `NodeServer.setInboundPropagatorSelector`. See `convex-peer`'s `NodeServer` for
the full capability model.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-p2p is provided under the [Convex Public License](../LICENSE.md).
