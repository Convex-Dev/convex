# Lattice Regions

The global lattice root is defined by `Lattice.ROOT` (`KeyedLattice`). Each top-level keyword names a **region** with its own lattice type and merge semantics. Regions are independent — a node can participate in any subset (see Selective Attention below).

## Root Structure

```
Lattice.ROOT (KeyedLattice)
├── :data  → DataLattice                                    Content-addressable storage
├── :fs    → OwnerLattice(MapLattice(DLFSLattice))          Decentralised file systems
├── :kv    → OwnerLattice(MapLattice(KVStoreLattice))       Key-value databases
├── :queue → OwnerLattice(MapLattice(TopicLattice))         Message queues
├── :p2p   → P2PLattice (KeyedLattice)                      Peer discovery metadata
│   └── :nodes → OwnerLattice(LWWLattice)                     Signed NodeInfo per peer
└── :local → OwnerLattice(MapLattice(LWWLattice))           Peer-local state (not propagated)
```

Source: `convex.lattice.Lattice.ROOT`

## Region Details

### :data — Content-Addressable Storage

`DataLattice` — `Index<Hash, ACell>`. Union merge: any data stored by any peer becomes available to all. Used for sharing immutable content by hash.

### :fs — Decentralised File Systems

`OwnerLattice(MapLattice(DLFSLattice))`. Per-owner signed file system namespaces, each containing named drives with DLFS merge semantics.

Path: `[:fs <ownerKey> :value <driveName>]`

### :kv — Key-Value Databases

`OwnerLattice(MapLattice(KVStoreLattice))`. Per-owner signed key-value stores. Each store is an `Index<AString, AVector<ACell>>` with entry-level merge.

Path: `[:kv <ownerKey> :value <storeName>]`

### :queue — Message Queues

`OwnerLattice(MapLattice(TopicLattice))`. Per-owner signed message topics with partitions and metadata.

Path: `[:queue <ownerKey> :value <topicName>]`

### :p2p — Peer Discovery

`P2PLattice` (`KeyedLattice`) with one sub-region:

- **`:nodes`** — `OwnerLattice(LWWLattice)`. Each peer publishes `SignedData<NodeInfo>` containing transport URIs, supported regions, protocol version, and timestamp. LWW merge ensures the latest metadata wins. Validated against on-chain `PeerStatus`.

Path: `[:p2p :nodes <accountKey>]`

See [P2P_DESIGN.md § P2P Discovery Lattice](../../convex-peer/docs/P2P_DESIGN.md#4-p2p-discovery-lattice-proposed) for the full design.

### :local — Peer-Local State

`OwnerLattice(MapLattice(LWWLattice))`. Per-owner key-value maps with LWW merge. **Not propagated** to other nodes — used for node-private configuration and state.

## Extending the Root

Applications register new regions via `addLattice`:

```java
KeyedLattice root = Lattice.ROOT.addLattice(Keywords.intern("myapp"), myAppLattice);
```

The keyword becomes the first path element when navigating from the root. See [LATTICE_APPLICATIONS.md § Register with the root lattice](LATTICE_APPLICATIONS.md#5-register-with-the-root-lattice) for details.

## Proposed Regions

These regions are designed but not yet registered in `Lattice.ROOT`:

| Region | Path | Lattice Type | Purpose |
|--------|------|-------------|---------|
| `:convex` | `[:convex <genesis-hash> :peers]` | `BeliefLattice` (CPoS merge) | Consensus beliefs scoped by network |
| `:sql` | `[:sql <ownerKey> :value <tableName>]` | `OwnerLattice(MapLattice(TableStoreLattice))` | Relational data (convex-db) |

See [P2P_DESIGN.md § Consensus as a Lattice Region](../../convex-peer/docs/P2P_DESIGN.md#3-consensus-as-a-lattice-region-proposed) for the `:convex` design.

## Selective Attention

Nodes only propagate regions they participate in. A lightweight data node need not propagate consensus beliefs. A consensus validator need not propagate DLFS file systems. The P2P node registry (`:p2p :nodes`) advertises which regions each peer serves, enabling efficient gossip targeting.

This is natural in the `NodeServer` model — propagators only transmit deltas for paths that have changed, and nodes only merge values for lattice types they have registered.
