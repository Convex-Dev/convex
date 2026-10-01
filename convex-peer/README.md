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
    <version>0.8.16</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-peer:0.8.16'
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

### Optional TLS Transport

Unqualified addresses and `tcp://` use native TCP, the default transport.
`tls://` carries the same binary messages and framing over TLS:

```java
Convex convex = Convex.connect("tls://peer.example:18889");
```

When the expected peer key is known, it is the TLS trust anchor:

```java
ConvexRemote convex = ConvexRemote.connect(
    URI.create("tls://203.0.113.10:18889"), expectedPeerKey);
```

The server certificate must be signed directly by that Ed25519 peer key.
Hostname and IP matching are unnecessary in this mode; expiry, certificate
usage and cryptographic checks still apply. The TLS handshake proves possession
of the certificate's private key, which can be separate from the peer signing
key. Consensus and lattice connection managers select this policy automatically
when dialling a known peer. A failed key check never falls back to public-CA or
hostname authentication.

The CLI accepts the same endpoint with `--host tls://peer.example:18889`,
including as a peer's initial sync source. An explicit `--port` overrides the
endpoint's port. Advertised peer URLs retain their transport scheme when dialled.

To add a TLS listener alongside a peer's TCP listener, set `peer.tlsPort` in
the JSON5 configuration:

```json5
{ peer: { port: 18888, tlsPort: 18889 } }
```

The programmatic equivalent is `config.put(Config.TLS_PORT, 18889)` before
`API.launchPeer(config)`. Port `0` requests an available port; retrieve it with
`server.getTLSPort()`. Omitting `tlsPort` leaves TLS disabled.

The listener uses the JVM's default `SSLContext`. Configure its certificate and
private key using the `javax.net.ssl.keyStore`, `javax.net.ssl.keyStoreType`
(e.g. `PKCS12`) and `javax.net.ssl.keyStorePassword` system properties before
starting the JVM's TLS services. For known-peer connections, this keystore must
contain a peer-signed certificate and its matching TLS private key. Applications
can issue the certificate with
`CertUtils.signPeerCertificate(peerKey, tlsPublicKey, notBefore, notAfter)`;
the validity bounds are `Instant` values. Issuance and renewal are operator-owned.

Clients without an expected peer key use the JVM's trusted certificate
authorities, or a private trust store configured with `javax.net.ssl.trustStore`,
`javax.net.ssl.trustStoreType` and `javax.net.ssl.trustStorePassword`. In this mode
the certificate must also match the endpoint's hostname or IP address. This
includes CLI connections specified only with `--host`. TLS failures never fall
back to TCP.

Applications can instead supply an `SSLContext` with `Config.TLS_CONTEXT` on
the server or `ConvexRemote.connect(uri, Transports.tls(context))` on the client.
`Transports.tls(expectedPeerKey)` explicitly selects peer-key authentication.
TLS does not bypass protocol admission: signed messages and CAD15
challenge/response verification retain their existing roles.

The small [transport extension point](docs/MESSAGING.md#36-transport-selection-and-tls)
allows other message transports later. HTTPS is not yet a built-in transport.

## Architecture

| Component | Description |
|-----------|-------------|
| `Server` | Main peer server managing consensus and client connections |
| `Convex` | Client API (`convex.api`) for queries and transactions against a peer |
| `ConnectionManager` | Manages peer-to-peer network connections |
| `BeliefPropagator` | Handles CPoS belief propagation protocol |
| `NodeServer` | Authoritative lattice host (`convex.node`): merge, persistence and group notifications |
| `LatticeListener` | Application-owned inbound TCP transport and connection-to-group router |
| `LatticePropagator` | Owns one filtered serving view, protocol endpoint and external route set |
| `LatticeConnectionManager` | Maintains bounded connection intent and authenticated routes for one propagator; discovery schemas live in application modules |

## Lattice Node

Alongside the consensus peer, this module provides a lightweight node server
for lattice data regions — values that merge like CRDTs rather than passing
through CPoS consensus. `NodeServer` (in `convex.node`) owns the authoritative
value and persistence; `LatticePropagator` handles CAD036 messages and routes;
and the application-owned `LatticeListener` provides the standard inbound TCP
transport. None interprets application paths or records; the `convex-p2p`
module layers node discovery, social selection and PoP routing on them.

- **Construction** - Create a `NodeServer` with a lattice (defining merge
  semantics), a store and an optional `NodeConfig`, then call `launch()`.
- **Transport composition** - Create `LatticeListener` separately, register the
  eligible propagators, install a selector, and close it before `NodeServer`.
- **Configuration** - `NodeConfig` contains authoritative persistence, standard
  listener and application advertisement settings, but each component consumes
  only its own fields. Each independently
  constructed `LatticePropagatorConfig` controls one group's routes, protocol
  queue, acquisition and publication limits.
- **Bounded inbound path** - Inbound messages are admitted to a bounded queue
  sized by the selected group's `LatticePropagatorConfig`; decode and merge run
  on a dispatcher thread off the network I/O thread, and a full queue applies
  backpressure to the connection.
- **Propagators** - Each `LatticePropagator` owns a store and a
  `LatticeFilter` which projects values before they are announced or broadcast,
  so data outside that group's policy never enters its serving store. Contained
  failures are available through `getStatus()` and `nextFailure()`.
- **Inbound policy** - `LatticeListener.setSelector` assigns each inbound
  connection to exactly one propagator, which determines both the query view
  and the store used for acquisition. No default policy is installed: inbound
  lattice traffic is denied until the operator sets one.

## Documentation

- [Lattice Networking Responsibilities and Trust Boundaries](docs/LATTICE_NETWORKING.md)
- [Delta Propagation, Backpressure and Memory Bounds](docs/PROPAGATION.md)
- [Lattice Persistence and Node Configuration](docs/PERSISTENCE.md)
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
