# Convex Peer

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-peer.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-peer/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-peer)

Peer server implementation and networking layer for the [Convex](https://convex.world) decentralised network.

## Features

- **Peer Server** - Full peer node implementation for participating in Convex consensus
- **Binary Protocol** - Efficient binary messaging protocol for peer-to-peer communication
- **Netty Networking** - High-performance async I/O for network operations
- **State Synchronisation** - Automatic state sync and belief propagation
- **Lattice Node** - Lightweight `NodeServer` for syncing lattice data regions

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-peer</artifactId>
    <version>0.8.14</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-peer:0.8.14'
```

## Usage

### Running a Peer Programmatically

```java
import java.util.HashMap;
import java.util.Map;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.peer.API;
import convex.peer.Server;

// Generate or load peer key pair
AKeyPair keyPair = AKeyPair.generate();

// Configure and launch peer
Map<Keyword, Object> config = new HashMap<>();
config.put(Keywords.KEYPAIR, keyPair);
Server server = API.launchPeer(config);

// Server is now participating in consensus
```

With no `:state` or `:source` configured the peer starts from a fresh genesis
state, i.e. its own independent network. Further config keys (`:port`,
`:store`, `:url` etc.) are documented on `convex.peer.API.launchPeer`. For a
throwaway local test network, `API.launchPeer()` with no arguments generates a
key pair and genesis state automatically.

### Connecting to a Peer

```java
import convex.api.Convex;
import convex.core.Result;

// Connect to remote peer (anonymous connection, suitable for queries)
Convex convex = Convex.connect("peer.convex.live:18888");

// Submit query
Result result = convex.querySync("(balance #11)");
```

`convex.api.Convex` also supports asynchronous queries (`query(...)` returning
a future) and signed transactions once an address and key pair are set.

## Architecture

| Component | Description |
|-----------|-------------|
| `Server` | Main peer server managing consensus and client connections |
| `Convex` | Client API (`convex.api`) for queries and transactions against a peer |
| `ConnectionManager` | Manages peer-to-peer network connections |
| `BeliefPropagator` | Handles CPoS belief propagation protocol |
| `NodeServer` | Lattice node server (`convex.node`) for syncing lattice data regions |
| `LatticePropagator` | Persists, filters and broadcasts lattice updates for a `NodeServer` |

## Lattice Node

Alongside the consensus peer, this module provides a lightweight node server
for lattice data regions — values that merge like CRDTs rather than passing
through CPoS consensus. `NodeServer` (in `convex.node`) speaks the same binary
protocol as the peer server, but exchanges and merges lattice values instead
of beliefs. The `convex-p2p` module builds its node server on it.

- **Construction** - Create a `NodeServer` with a lattice (defining merge
  semantics), a store and an optional `NodeConfig`, then call `launch()`.
- **Configuration** - `NodeConfig` carries the tuning knobs: port, persistence
  and restore behaviour, public URL, message size limits, connection cap,
  inbound queue capacity and shutdown drain timeout.
- **Bounded inbound path** - Inbound messages are admitted to a bounded queue
  sized by `NodeConfig`; decode and merge run on a dispatcher thread off the
  network I/O thread, and a full queue applies backpressure to the connection.
- **Propagators** - Each `LatticePropagator` owns a store and a
  `LatticeFilter` which projects values before they are announced, persisted
  or broadcast, so private data never leaves that propagator's view.
- **Inbound policy** - `setInboundPropagatorSelector` assigns each inbound
  connection to exactly one propagator, which determines both the query view
  and the store used for acquisition. No default policy is installed: inbound
  lattice traffic is denied until the operator sets one.

## Documentation

- [Javadoc API Reference](https://javadoc.io/doc/world.convex/convex-peer)
- [Convex Documentation](https://docs.convex.world)
- [Running a Peer](https://docs.convex.world/docs/convex-peer)

## Building from Source

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
./mvnw -B -T1C install -pl convex-peer -am
```

## License

Copyright 2019-2025 The Convex Foundation and Contributors

Code in convex-peer is provided under the [Convex Public License](../LICENSE.md).
