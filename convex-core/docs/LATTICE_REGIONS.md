# Lattice Regions

The global lattice root, `Lattice.ROOT`, is a `KeyedLattice` whose top-level keywords
name independent **regions**, each with its own lattice type and merge semantics. This
document is the catalogue of the regions `convex-core` registers, the path shape used
to navigate into each, and how applications add their own. The lattice model itself is
specified in [CAD024](https://docs.convex.world/docs/cad/data_lattice) and node
behaviour in [CAD036](https://docs.convex.world/docs/cad/lattice_node).

## Key points

- A region is a keyword key on the root lattice; its value type and merge function are
  fixed by the lattice registered under that key.
- Regions are independent. A node participates in any subset and propagates only the
  regions it has registered (selective attention).
- Owner-scoped regions share one shape: `[:<region> <ownerKey> :value <name>]`, with an
  `OwnerLattice` enforcing that each owner's slot is signed by that owner.
- Adding a region is `Lattice.ROOT.addLattice(keyword, lattice)`; the keyword becomes
  the first path element from the root.
- `:local` is the one region that is never propagated.

## Root structure

```
Lattice.ROOT (KeyedLattice)
├── :data  → DataLattice                                    Content-addressable storage
├── :fs    → OwnerLattice(MapLattice(DLFSLattice))          Decentralised file systems
├── :kv    → OwnerLattice(MapLattice(KVStoreLattice))       Key-value databases
├── :queue → OwnerLattice(MapLattice(TopicLattice))         Message queues
├── :p2p   → P2PLattice (KeyedLattice)                      Peer discovery metadata
│   └── :nodes → OwnerLattice(LWWLattice)                     Signed NodeInfo per peer
└── :local → LocalLattice                                   Peer-local state (not propagated)
```

Source of truth: `convex.lattice.Lattice.ROOT`.

## Regions

| Region | Lattice | Path | Notes |
|---|---|---|---|
| `:data` | `DataLattice` (`Index<Hash, ACell>`) | `[:data <hash>]` | Union merge: anything stored by any peer becomes available to all. |
| `:fs` | `OwnerLattice(MapLattice(DLFSLattice))` | `[:fs <ownerKey> :value <driveName>]` | Per-owner signed namespaces of named drives with DLFS merge ([CAD028](https://docs.convex.world/docs/cad/dlfs)). |
| `:kv` | `OwnerLattice(MapLattice(KVStoreLattice))` | `[:kv <ownerKey> :value <storeName>]` | Per-owner key-value stores with entry-level merge ([CAD037](https://docs.convex.world/docs/cad/kv_database)). |
| `:queue` | `OwnerLattice(MapLattice(TopicLattice))` | `[:queue <ownerKey> :value <topicName>]` | Per-owner message topics with partitions and metadata ([CAD040](https://docs.convex.world/docs/cad/lattice_queue)). |
| `:p2p :nodes` | `OwnerLattice(LWWLattice)` | `[:p2p :nodes <accountKey>]` | Each node publishes `SignedData<NodeInfo>`: transport URIs, relay willingness, served regions, protocol version, timestamp. Latest timestamp wins. |
| `:local` | `LocalLattice` | `[:local ...]` | Node-private configuration and state; never propagated. |

The `:p2p` region and its use for discovery are designed in
`convex-peer/docs/P2P_DESIGN.md`.

## Extending the root

```java
KeyedLattice root = Lattice.ROOT.addLattice(Keyword.intern("myapp"), myAppLattice);
```

A node opts in to hosting an application's data by registering its lattice under a
keyword. Design guidance for the lattice itself, and for the components that sit over
it, is in [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md).

Regions that are designed but not registered in `Lattice.ROOT`:

| Region | Path | Lattice | Purpose |
|---|---|---|---|
| `:convex` | `[:convex <genesis-hash> :peers]` | `BeliefLattice` (CPoS merge) | Consensus beliefs scoped by network; see `convex-peer/docs/P2P_DESIGN.md`. |
| `:sql` | `[:sql <ownerKey> :value <tableName>]` | `OwnerLattice(MapLattice(TableStoreLattice))` | Relational data for `convex-db` ([CAD039](https://docs.convex.world/docs/cad/convex_sql)). |

## Selective attention

Nodes propagate only the regions they participate in: a data node need not carry
consensus beliefs, and a consensus peer need not carry file systems. The node registry
under `:p2p :nodes` advertises which regions each peer serves, so propagation can
target the right neighbours. `NodeServer` propagators transmit deltas only for paths
that changed, and a node merges values only for lattice types it has registered.

## Where the code lives

- `convex.lattice.Lattice` — the root definition.
- `convex.lattice.KeyedLattice`, `OwnerLattice`, `MapLattice`, `LWWLattice`,
  `DataLattice`, `DLFSLattice`, `KVStoreLattice`, `TopicLattice`, `P2PLattice`,
  `LocalLattice` — the region lattices.
- `convex.node.NodeServer` (`convex-peer`) — hosting and propagation.

## Related

- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice) — lattice model and root structure.
- [CAD036 Lattice Node](https://docs.convex.world/docs/cad/lattice_node) — node architecture, propagation and standard lattice types.
- [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) — building components over a region.
- [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) — navigating regions with cursors.
- `convex-peer/docs/P2P_DESIGN.md` — discovery lattice and proposed consensus region.
