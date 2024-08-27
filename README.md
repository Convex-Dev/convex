# Convex

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)

<!-- ![Workflow](https://github.com/convex-dev/convex/actions/workflows/tests.yml/badge.svg) -->


Convex is a decentralised network and execution engine for the Internet of Value. It can be seen as an implementation of a "Stateful Internet" where the network itself securely hosts and executes code and data.

It is designed as a full stack solution for decentralised applications and programmable economic systems that manage digital assets, where asset ownership is cryptographically secured and can be managed (optionally) with Smart Contracts. 

Convex is based on Lattice Technology, exploiting the mathematical properties of lattices to achieve efficient consensus and verifiability. Lattice technology can be used to solve problems in a similar manner to blockchains, but offers some significant advantages:

- Global State model with immutable data structures and atomic transactions
- Lambda Calculus based VM supporting Turing complete Smart Contracts
- High transaction throughput (tens of thousands of write transactions per second, potentially scaling to millions in the future)
- Simple networking protocol based on random gossip
- Low latency for transaction confirmation (milliseconds for global consensus, depending on network speed)
- 100% Green - energy efficiency using the Convergent Proof of Stake consensus algorithm
- Integrated on-chain compiler (Convex Lisp)

## About this repository

This repository contains the core Convex distribution including:

- The Convex Virtual Machine (CVM) including data structures and execution environment
- The standard Convex Peer server implementation (NIO based) implementing Convergent Proof of Stake (CPoS) for consensus
- CLI Tools for operating Peers, scripting transactions and more
- The Etch database for persistent data storage
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

For making use of Convex data structures, CVM execution etc. locally you probably just need `convex-core`. If you want to run a Peer or talk to a Peer over the network, then you will need `convex-peer` as a dependency. The other modules are mainly intended to run as standalone applications.

## Key features

* *Convex Virtual Machine (CVM)* - The CVM provides a secure execution environment based on the Lambda Calculus and capable of acting as the execution layer for smart contracts and autonomous agents.
* *Decentralised Consensus* - Similar to Blockchain technology, Convex incorporates a consensus mechanism that ensures all nodes ultimately agree on true values in the system without the control of any single entity. This property means that it is inherently tamper-proof and censorship-resistant.
* *Performance and Scalability* - Convex is capable of executing large volumes of transactions (tens of thousands of transactions per second) with low latency (sub-second global consensus)
* *100% Green* - No wasteful consumption of energy or computing resources

## Running Convex

### Download

Recent development snapshot builds of `convex.jar` are made available here:

- [Snapshots](https://drive.google.com/drive/folders/1AZdyuZOmC70i_TtuEW3uEKvjYLOqIMiv?usp=drive_link)

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

Open Source contributions are welcome under the terms of the Convex Public License. Contributors retain copyright to their work, but must accept the terms of the license.

We have instituted a Contributors Agreement for all contributions to the core Convex repository.

The Convex Foundation may, at its sole discretion, award contributors with Convex Coins as recognition of value contributed to the Convex ecosystem. Convex coins are the native coin of the Convex network, and function as a utility token that provides the right to make use of the services of the network. Convex coins may be exchangeable for other digital assets and currencies.

## Community

We use Discord as for discussing Convex - you can join the public server at [https://discord.gg/5j2mPsk](https://discord.gg/5j2mPsk)

Alternatively, email: info(at)convex.world

## Copyright

Copyright 2017-2024 The Convex Foundation and Contributors

Unless otherwise specified, source code is available under the terms of the [Convex 
Public License](LICENSE.md)
