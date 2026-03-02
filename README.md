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

## Getting Started

### Prerequisites

- Java 21+ ([Download](https://www.oracle.com/java/technologies/downloads/))

### Quick Install

**macOS / Linux:**

```bash
curl -fsSL https://raw.githubusercontent.com/Convex-Dev/convex/develop/install.sh | bash
```

**Windows (PowerShell):**

```powershell
irm https://raw.githubusercontent.com/Convex-Dev/convex/develop/install.ps1 | iex
```

This installs `convex.jar` and adds a `convex` command to your PATH.

### Other options

**Download directly:**
- [Latest stable release](https://github.com/Convex-Dev/convex/releases/latest/download/convex.jar)
- [Snapshot build](https://github.com/Convex-Dev/convex/releases/tag/snapshot-develop) (develop branch, may be unstable)

**Docker:**

```bash
docker pull convexlive/convex:latest
docker run convexlive/convex peer start
```

**Build from source:**

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
mvn clean install
```

### Running Convex

Launch the desktop GUI:

```bash
convex desktop
```

Start a local peer test network with GUI:

```bash
convex local gui
```

See all available commands:

```bash
convex --help
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
