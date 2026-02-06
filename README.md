# Convex - Lattice technology for Open Economic Systems

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)

**Convex** is a decentralised network and execution platform that powers the Internet of Value. It realises the vision of a true **Stateful Internet**, where the network itself securely hosts, executes, and persists both code and data.

Convex provides a full-stack platform for decentralised applications and programmable economic systems that manage digital assets. Ownership of accounts and assets is cryptographically enforced and can be governed (optionally) through smart contracts.

## Core Technology: Lattice-Based Architecture

Unlike traditional blockchains, Convex is built on **Lattice Technology**, which leverages the mathematical properties of lattices to deliver superior consensus and verifiability. This foundation yields significant advantages over conventional blockchain designs:

| Feature                                      | Convex Advantage                                                                 |
|----------------------------------------------|-----------------------------------------------------------------------------------|
| **Global State Model**                       | Single, consistent global state with immutable data structures and atomic transactions |
| **Virtual Machine**                          | Lambda-calculus-based VM supporting fully Turing-complete smart contracts         |
| **Throughput**                               | Tens of thousands of write TPS today; designed to scale to millions in the future |
| **Networking**                               | Simple, robust random-gossip protocol                                             |
| **Confirmation Latency**                     | Millisecond-range global consensus (network-speed dependent)                      |
| **Energy Efficiency**                        | **100% Green** – powered by Convergent Proof-of-Stake (no energy-intensive mining) |
| **Programming Language**                     | Integrated on-chain compiler for **Convex Lisp** (a modern, secure Lisp dialect)   |

## Why Convex?

- **Developer-friendly**: Write smart contracts in a powerful, functional Lisp that compiles and executes directly on-chain.
- **Instant finality**: Transactions confirm in milliseconds with cryptographic guarantees.
- **Truly scalable**: Lattice agreement eliminates the bottlenecks of linear blockchains.
- **Sustainable by design**: Minimal energy footprint while maintaining full decentralization and security.

Convex is the high-performance, eco-friendly backbone for the next generation of decentralised finance, agentic economies, self-sovereign ownership, and beyond.

## About this repository

This repository contains the core Convex distribution including:

- The Convex Virtual Machine (CVM) including data structures and execution environment
- The standard Convex Peer server implementation (NIO based) implementing Convergent Proof of Stake (CPoS) for consensus
- CLI Tools for operating Peers, scripting transactions and more
- The Etch database for persistent data storage
- SQL database layer with JDBC driver and PostgreSQL wire protocol server
- A Swing GUI for managing local peers / exploring the network
- A simple REST API server
- JMH Benchmarking suite
- Java Client API

The repository also contains core "on-chain" libraries providing key full-stack functionality and tools for decentralised applications, including:

- `convex.fungible` - Fungible Tokens
- `asset.nft.simple` - Basic lightweight Non-fungible tokens
- `convex.asset` - library for managing arbitrary digital assets using a common abstraction
- `convex.trust` - library for access control and trusted operations
- `torus.exchange` - decentralised exchange for trading fungible tokens and currencies
- Example code and templates for various forms of smart contracts

## Modules

| Name  | Description | Maven | Javadoc |
| ----- | ----------- | ----- | ------- |
| [convex-core](https://github.com/Convex-Dev/convex/tree/develop/convex-core/) | CVM, data structures and consensus | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-core.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-core/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-core) |
| [convex-peer](https://github.com/Convex-Dev/convex/tree/develop/convex-peer/) | Peer implementation and networking | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-peer.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-peer/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-peer) |
| [convex-cli](https://github.com/Convex-Dev/convex/tree/develop/convex-cli/) | Command Line Tools | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-cli.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-cli/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-cli) |
| [convex-gui](https://github.com/Convex-Dev/convex/tree/develop/convex-gui/) | Convex Desktop GUI Interface | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-gui.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-gui/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-gui) |
| [convex-db](https://github.com/Convex-Dev/convex/tree/develop/convex-db/) | SQL database with JDBC and PostgreSQL protocol | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-db.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-db/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-db) |

For local use of Convex data structures and CVM execution, `convex-core` is typically sufficient. To run a peer or communicate with one over the network, include `convex-peer` as a dependency. Other modules are designed primarily as standalone applications or client libraries.

## Key features

* *Convex Virtual Machine (CVM)* - Secure, Lambda Calculus-based environment for smart contracts and autonomous agents.
* *Decentralised Consensus* - Tamper-proof and censorship-resistant via Convergent Proof of Stake (CPoS).
* *Performance and Scalability* - Processes tens of thousands (and sometimes up to millions) of transactions per second with sub-second global consensus.
* *Eco-Friendly* - Minimal energy consumption with CPoS, ensuring a sustainable platform.

## Running Convex

### Pre-requisities

- Java JDK 21+ (for running Convex and related tools)
- Maven 3.7+ (for building Convex from source)
- git (for source control / contributing)

### Download

**Stable Releases:**
- [Latest Release](https://github.com/Convex-Dev/convex/releases/latest) - Production-ready builds with full changelog

**Development Snapshots:**
- [Snapshot Build](https://github.com/Convex-Dev/convex/releases/tag/snapshot-develop) - Latest `develop` branch build (⚠️ may be unstable)
- [Google Drive Snapshots](https://drive.google.com/drive/folders/1AZdyuZOmC70i_TtuEW3uEKvjYLOqIMiv?usp=drive_link) - Archive of development builds

The snapshot build is automatically updated with each push to the `develop` branch.

### Building locally

To get a local development build of Convex you need [git](https://git-scm.com/) and [Apache Maven](https://maven.apache.org/). You will also need a recent version of Java ([JDK 21+ recommended](https://www.oracle.com/java/technologies/downloads/).

1. Clone [this repository](https://github.com/Convex-Dev/convex) using `git` - you probably want the `develop` branch (the default)
2. Build using `mvn install` in the root directory

This should download all necessary dependencies and perform a standard build.

### Running Convex Desktop

Convex Desktop is a GUI application for power users and developers providing full access to the capabilities of Convex. To run, you will need a modern version of Java installed (21+) and the `convex.jar` executable file (which can be found in the outputs of the Maven build above, or downloaded from trusted sources).

If Java is correctly installed, you should be able to double-click the executable `convex.jar` file to run it. Depending on your security settings, you may need to grant approval for this to run.

Alternatively, run Convex Desktop using `java` at the command line as follows:

```bash
java -jar convex.jar desktop
```

### Running the Convex Command Line Interface (CLI)

If you have an already built version of the Convex CLI `convex.jar` file and installed a recent version of Java you can run it as follows:

```bash
java -jar convex.jar <optional args>
```

For convenience, there are shell scripts to automate this for common platforms in the root directory of this repo, e.g.

```bash
./convex --help
```

Using the CLI, you can start the Convex Desktop GUI for a local peer test network by using the `local gui` command:

```
./convex local gui
```

## Contributing

Contributions are welcome under the Convex Public License. Contributors retain copyright but must accept the license terms. A Contributors Agreement is required for all submissions to the core repository.

The Convex Foundation may award Convex Coins to contributors for significant ecosystem contributions. These native utility tokens enable network service access and may be exchangeable for other digital assets.

## Community

We use Discord as for discussing Convex - you can join the public server at [https://discord.gg/5j2mPsk](https://discord.gg/5j2mPsk)

Alternatively, email: info(at)convex.world

## Copyright

Copyright 2017-2025 The Convex Foundation and Contributors

Unless otherwise specified, source code is available under the terms of the [Convex 
Public License](LICENSE.md)
