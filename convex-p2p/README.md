# Convex P2P

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-p2p.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-p2p/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-p2p)

Core peer-to-peer lattice functionality for Convex. Builds on the lattice data
structures in `convex-core` and the `NodeServer` binary networking in `convex-peer`
to provide nodes that discover each other, exchange lattice values and converge on
shared state.

> **Status: early stub.** The module scaffolding and entry point are in place;
> discovery, region subscription and replication policy are still to be built.

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
write their own slot. Two sharp edges are worth knowing, both pinned by tests:

**Owner checks run on merge, not on write — deliberately.** `LatticeContext.verifyOwner`
is called from `OwnerLattice`'s context-aware merge, so it catches everything arriving
from a peer. A *direct* local `cursor().set(..)` is not a merge and is not policed. An
app that writes a slot it cannot properly sign has corrupted only its own subtree:
owner-keying means it can wedge no slot but its own, peers discard the bad slot on
merge, and a node that keeps sending them trips `NodeServer`'s per-connection
circuit-breaker (`maxConsecutiveRejects`) and loses the connection. Guarding local
writes would buy nothing at the boundary that matters, and would wrongly block a node
that legitimately holds keys for more than one identity.

**The two-argument merge skips the check entirely.** `merge(own, other)` does not verify
the signer against the owner key — only the context-aware overload does. Every real path
is safe (`ALatticeCursor.merge` always passes a context, and `LatticeContext.EMPTY` still
triggers the `AccountKey` fast path), but don't reach for a raw `ALattice.merge(a, b)`
on these regions.

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-p2p</artifactId>
    <version>0.8.10</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-p2p:0.8.10'
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

    // Modify this user's P2P area through a cursor, then push it back
    P2PUser me = node.p2p(keyPair.getAccountKey());   // or node.p2p()
    me.cursor().set(P2PLattice.createIdentity(Strings.create("alice"), null, null, ts));
    me.sync();
}
```

### The user area API

`node.p2p(userID).cursor()` returns a cursor at that user's identity slot
(`[:id <userKey> :value]`), already through the signing boundary — the application reads
and writes plain values and never touches `SignedData`:

```java
P2PUser me = node.p2p();              // own area, using the node's key pair
me.cursor().set(identityMap);         // signed on write
me.sync();                            // pushed to the lattice root

me.nodeCursor()                       // [:p2p :nodes <userKey> :value], same deal

P2PUser draft = me.fork();            // batch edits, isolated
draft.cursor().set(...);
draft.sync();                         // merged back under LWW
```

The cursor is scoped to one user: no other user's data is reachable through it, and the
component holds no handle to the wider lattice root. `node.p2p(someoneElse)` is a
readable view of their published area; writing it is a mistake the lattice does not need
to prevent (see [Owner binding](#owner-binding) above).

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
