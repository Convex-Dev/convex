# Lattice Regions

The global lattice root, `Lattice.ROOT`, is a `KeyedLattice` whose top-level keywords
name independent **regions**, each with its own lattice type and merge semantics. This
document is the catalogue of the regions `convex-core` registers, the path shape used
to navigate into each, and how applications add their own. The lattice model itself is
specified in [CAD024](https://docs.convex.world/docs/cad/data_lattice) and node
behaviour in [CAD036](https://docs.convex.world/docs/cad/lattice_node).

## Key points

- A region is a keyword key at the root, with one meaning allocated by CAD053.
  `Lattice.ROOT` registers a lattice for each core region; which regions a node
  merges, and how, is for the applications it runs.
- Applications receive incoming values through components registered with their
  host. Each component decides which regions to accept and when to publish.
- Owner-scoped regions hold a complete signed envelope at `[:<region> <ownerKey>]`.
  Local domain views unwrap it explicitly; remote paths cannot cross the signing boundary.
- Application-specific state belongs under `[:app <application-name>]`, as specified
  by CAD045. Shared root names are allocated by CAD053.
- Serving and disclosure are configured separately through propagation-group
  projections. Registering merge rules does not restrict what a node serves.

## Root structure

```
Lattice.ROOT (KeyedLattice)
├── :data  → DataLattice                                    Content-addressable storage
├── :fs    → OwnerLattice(MapLattice(DLFSLattice))          Decentralised file systems
├── :kv    → OwnerLattice(MapLattice(KVStoreLattice))       Key-value databases
├── :queue → OwnerLattice(MapLattice(TopicLattice))         Message queues
├── :p2p   → P2PLattice (KeyedLattice)                      Peer discovery metadata
│   └── :nodes → OwnerLattice(LWWLattice)                     Signed NodeInfo per peer
└── :local → LocalLattice                                   Signed per-peer configuration
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
| `:local` | `LocalLattice` | `[:local <peerKey>]` | Signed per-peer configuration. A serving projection must exclude any state intended to remain private. |

The `:value` paths in the table describe local unsigned views only. Deliver the
complete owner's envelope when replicating these regions.

The `:p2p` region and its use for discovery are designed in
`convex-peer/docs/P2P_DESIGN.md`.

## Adding application state

```java
KeyedLattice applications = KeyedLattice.create(Keyword.intern("myapp"), myAppLattice);
KeyedLattice root = Lattice.ROOT.addLattice(Keyword.intern("app"), applications);
```

This declares merge semantics at `[:app :myapp]`; it does not register a receiving
application or configure networking. Register the corresponding root component
with `host.addApplication(...)`. Components can also compose `Regions` directly,
without a monolithic root lattice definition. See
[LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) for assembly and publication.

Regions that are designed but not registered in `Lattice.ROOT`:

| Region | Path | Lattice | Purpose |
|---|---|---|---|
| `:convex` | `[:convex <genesis-hash> :peers]` | `BeliefLattice` (CPoS merge) | Consensus beliefs scoped by network; see `convex-peer/docs/P2P_DESIGN.md`. |
| `:sql` | `[:sql <ownerKey> ...]` | Not yet defined | Reserved by [CAD053](https://docs.convex.world/docs/cad/lattice_regions) for databases published for many applications to read, such as reference tables: each owner's signed databases, as in the replica format of [CAD039](https://docs.convex.world/docs/cad/convex_sql). Convex DB components take the cursor for their level and assume no fixed region, so an application roots its own databases where it chooses, conventionally under `:app`. |

## Selective attention

A host delivers incoming state to its registered application components. They can
ignore unknown regions while retaining unknown data already held locally. This is
an acceptance policy, not a disclosure policy. Configure each propagation group's
serving projection to select what peers may receive; omitted data is not a deletion.
See [lattice networking](../../convex-peer/docs/LATTICE_NETWORKING.md).

## Where the code lives

- `convex.lattice.Lattice` — the root definition.
- `convex.lattice.generic` — `KeyedLattice`, `OwnerLattice`, `MapLattice`, `LWWLattice`.
- Domain packages —
  `DataLattice`, `DLFSLattice`, `KVStoreLattice`, `TopicLattice`, `P2PLattice`,
  `LocalLattice` — the region lattices.
- `convex.node.NodeServer` (`convex-peer`) — hosting and propagation.

## Related

- [CAD024 Data Lattice](https://docs.convex.world/docs/cad/data_lattice) — lattice model and root structure.
- [CAD036 Lattice Node](https://docs.convex.world/docs/cad/lattice_node) — node architecture, propagation and standard lattice types.
- [LATTICE_APPLICATIONS.md](LATTICE_APPLICATIONS.md) — building components over a region.
- [LATTICE_CURSOR_DESIGN.md](LATTICE_CURSOR_DESIGN.md) — navigating regions with cursors.
- `convex-peer/docs/P2P_DESIGN.md` — discovery lattice and proposed consensus region.
