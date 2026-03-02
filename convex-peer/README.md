# Convex Peer

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-peer.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-peer/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-peer)

Peer server implementation and networking layer for the [Convex](https://convex.world) decentralized network.

## Features

- **Peer Server** - Full peer node implementation for participating in Convex consensus
- **Binary Protocol** - Efficient binary messaging protocol for peer-to-peer communication
- **Netty Networking** - High-performance async I/O for network operations
- **State Synchronization** - Automatic state sync and belief propagation

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-peer</artifactId>
    <version>0.8.2</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-peer:0.8.2'
```

## Usage

### Running a Peer Programmatically

```java
import convex.peer.Server;
import convex.peer.Config;
import convex.core.crypto.AKeyPair;

// Generate or load peer key pair
AKeyPair keyPair = AKeyPair.generate();

// Configure and launch peer
Server server = Server.create(keyPair);
server.launch();

// Server is now participating in consensus
```

### Connecting to a Peer

```java
import convex.peer.Connection;
import convex.core.Result;

// Connect to remote peer
Connection conn = Connection.connect("peer.convex.live", 18888);

// Submit query
Result result = conn.query("*balance*", address).get();
```

## Architecture

| Component | Description |
|-----------|-------------|
| `Server` | Main peer server managing consensus and client connections |
| `Connection` | Client connection to a remote peer |
| `ConnectionManager` | Manages peer-to-peer network connections |
| `BeliefPropagator` | Handles CPoS belief propagation protocol |

## Documentation

- [Javadoc API Reference](https://javadoc.io/doc/world.convex/convex-peer)
- [Convex Documentation](https://docs.convex.world)
- [Running a Peer](https://docs.convex.world/docs/convex-peer)

## Building from Source

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
mvn install -pl convex-peer -am
```

## License

Copyright 2019-2025 The Convex Foundation and Contributors

Code in convex-peer is provided under the [Convex Public License](../LICENSE.md).
