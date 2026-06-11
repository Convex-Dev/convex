# Convex Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-java.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-java/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-java)

The official Java SDK for building applications on the [Convex](https://convex.world) decentralised lattice network.

This module provides `ConvexJSON`, a lightweight client that talks to any Convex peer exposing the REST API (for example a local peer, or a public testnet). It speaks JSON over HTTPS, so it works through firewalls and proxies and is the recommended starting point for most applications.

## 📚 Documentation

**Official documentation is available at [docs.convex.world/docs/tutorial/client-sdks/java](https://docs.convex.world/docs/tutorial/client-sdks/java)**

- [Quickstart Guide](https://docs.convex.world/docs/tutorial/client-sdks/java/quickstart) - Build your first Java app
- [Query Guide](https://docs.convex.world/docs/tutorial/client-sdks/java/queries) - Read network state
- [Transaction Guide](https://docs.convex.world/docs/tutorial/client-sdks/java/transactions) - Submit transactions
- [Account Management](https://docs.convex.world/docs/tutorial/client-sdks/java/accounts) - Manage keys and accounts

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-java</artifactId>
    <version>0.8.5</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-java:0.8.5'
```

## Quick Start

### Connect to the Network

`ConvexJSON.connect(url)` opens a REST client against a peer. No account is set up
until you provide one (or create a new one).

```java
import convex.java.ConvexJSON;

// Connect to a public testnet (or run a local peer for development)
ConvexJSON convex = ConvexJSON.connect("https://mikera1337-convex-testnet.hf.space");
```

### Create an Account

```java
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;

// Request a new account funded from the test faucet (up to 10,000,000 copper).
// The connection is set to use this account for subsequent transactions.
Address address = convex.useNewAccount(10_000_000);

// Access the generated key pair (required for signing transactions)
AKeyPair keyPair = convex.getKeyPair();
```

### Execute Queries

Queries are read-only operations that don't modify state and are free to run. The
result is returned as a JSON map; the computed value is under the `"value"` key (an
`"errorCode"` key is present if the query failed).

```java
import java.util.Map;

// Query the current account's balance
Map<String, Object> result = convex.query("*balance*");
System.out.println("Balance: " + result.get("value"));
```

### Submit Transactions

Transactions modify on-chain state and require a funded account with a key pair set
(see "Create an Account" above). They are signed locally and return the same JSON
map shape as queries.

```java
// Transfer coins to another account
Map<String, Object> result = convex.transact("(transfer #11 1000000)");
System.out.println("Result: " + result.get("value"));

// Deploy a smart contract (only ^:callable functions are reachable from outside)
Map<String, Object> deployed =
    convex.transact("(deploy '(do (defn ^:callable greet [name] (str \"Hello, \" name))))");
System.out.println("Actor address: " + deployed.get("value"));
```

### Asynchronous use

Every blocking call has a non-blocking counterpart returning a
`CompletableFuture<Map<String, Object>>`:

```java
convex.queryAsync("*balance*")
      .thenAccept(r -> System.out.println("Balance: " + r.get("value")));

convex.transactAsync("(def my-value 42)")
      .thenAccept(r -> System.out.println("Result: " + r.get("value")));
```

> **Thread-safety:** `ConvexJSON` may be shared across threads, but avoid submitting
> transactions for the *same account* concurrently — each transaction increments a
> sequence number that can become mismatched under concurrent submission. Queries
> have no such restriction.

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Account** | Self-sovereign identity on Convex, identified by address (e.g., `#42`) |
| **Key Pair** | Ed25519 cryptographic keys for signing transactions |
| **Query** | Read-only operation, free to execute |
| **Transaction** | State-changing operation, consumes juice |
| **Convex Lisp** | On-chain programming language for smart contracts |

## Lower-level peer client

For applications that need the binary peer protocol (lower latency, streaming,
running an embedded peer), `convex-java` also pulls in [`convex-peer`](../convex-peer/),
whose `convex.api.Convex` client connects directly to a peer and returns
`CompletableFuture<convex.core.Result>` from its `query` / `transact` methods. Most
applications should prefer `ConvexJSON` above; reach for `convex.api.Convex` only when
you specifically need the binary transport.

## Building from Source

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
mvn install -pl convex-java -am
```

## Resources

- **[Official Documentation](https://docs.convex.world/docs/tutorial/client-sdks/java)** - Complete SDK guide
- **[Javadoc API Reference](https://javadoc.io/doc/world.convex/convex-java)** - API documentation
- **[Maven Central](https://search.maven.org/artifact/world.convex/convex-java)** - Releases and versions
- **[Convex Network](https://convex.world)** - Main website
- **[Discord Community](https://discord.com/invite/xfYGq4CT7v)** - Get help and share ideas

## License

Copyright 2021-2025 The Convex Foundation and Contributors

Code in convex-java is provided under the [Convex Public License](../LICENSE.md).
