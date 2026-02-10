# Convex Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-java.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-java/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-java)

The official Java SDK for building applications on the [Convex](https://convex.world) decentralised lattice network.

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
    <version>0.8.2</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-java:0.8.2'
```

## Quick Start

### Connect to the Network

```java
import convex.api.Convex;

// Connect to a public testnet (or run a local peer for development)
Convex convex = Convex.connect("https://mikera1337-convex-testnet.hf.space");
```

### Create an Account

```java
// Request a new account with test funds (up to 10,000,000 copper coins)
convex.useNewAccount(10_000_000);

// Access your key pair (required for signing transactions)
AKeyPair keyPair = convex.getKeyPair();
```

### Execute Queries

Queries are read-only operations that don't modify state:

```java
// Query the current balance
ACell result = convex.query("*balance*");
```

### Submit Transactions

Transactions modify on-chain state and require a funded account:

```java
// Transfer funds to another account
ACell result = convex.transact("(transfer #42 1000000)");

// Deploy a smart contract
ACell result = convex.transact("(deploy '(do (defn greet [name] (str \"Hello, \" name))))");
```

## Key Concepts

| Concept | Description |
|---------|-------------|
| **Account** | Self-sovereign identity on Convex, identified by address (e.g., `#42`) |
| **Key Pair** | Ed25519 cryptographic keys for signing transactions |
| **Query** | Read-only operation, free to execute |
| **Transaction** | State-changing operation, consumes juice (gas) |
| **Convex Lisp** | On-chain programming language for smart contracts |

## Building from Source

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
mvn install -pl convex-java -am
```

## Quick Example

```java
import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.lang.Reader;

// Connect to a public testnet
Convex convex = Convex.connect("https://mikera1337-convex-testnet.hf.space");

// Execute a query
Result result = convex.query(Reader.read("(balance #13)")).get();
System.out.println("Balance: " + result.getValue());

// Use your account
AKeyPair keyPair = AKeyPair.generate();
convex.setKeyPair(keyPair);

// Submit a transaction
Result txResult = convex.transact(Reader.read("(def my-value 42)")).get();
System.out.println("Result: " + txResult.getValue());
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
