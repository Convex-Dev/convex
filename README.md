# Convex

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)

Convex is a decentralised network and execution engine for the Internet of Value.

It is designed as a full stack solution for decentralised application and economic systems that manage digital assets, where asset ownership is cryptographically secured and can be managed (optionally) with Smart Contracts. It can be considered functionally similar to a decentralised public blockchain, but offers some significant advantages:

- High transaction throughput (tens of thousands of write transactions per second, potentially scaling to millions)
- Low latency for transaction confirmation (milliseconds for global consensus, depending on network speed)
- 100% Green - energy efficiency using the the Convergent Proof of Stake consensus algorithm
- Global State model with immutable data structures and atomic transactions
- Lambda Calculus based VM supporting Turing complete Smart Contracts
- Integrated on-chain compiler (Convex Lisp)

## About this repository

This repository contains the core Convex distribution including:

- The Convex Virtual Machine (CVM) including data structures and execution environment
- The standard Convex Peer server implementation (NIO based) implementing Convergent Proof of Stake (CPoS) for consensus
- CLI Tools for operating Peers, scripting transactions and more
- The Etch database for persistent data storage
- A Swing GUI for managing local peers / exploring the network
- JMH Benchmarking suite
- Java Client API

The repository also contains core "on-chain" libraries providing key full-stack functionality and tools for decentralised applications, including:

- `convex.fungible` - Fungible Tokens
- `asset.nft.simple` - Basic lightweight Non-fungible tokens
- `asset.nft.tokens` - More advanced NFTs with customisable functionality
- `convex.asset` - library for managing arbitrary digital assets using a common abstraction
- `convex.trust` - library for access control and trusted operations
- `torus.exchange` - decentralised exchange for trading fungible tokens and currencies
- Example code and templates for various forms of smart contracts

## Modules

| Name  | Description | Maven | Javadoc |
| ----- | ----------- | ----- | ------- |
| `convex-core` | CVM, data structures and consensus | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-core.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-core/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-core) |
| `convex-peer` | Peer implementation and networking | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-peer.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-peer/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-peer) |
| `convex-cli` | Command Line Tools | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-cli.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-cli/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-cli) |
| `convex-gui` | GUI Peer Manager Interface | [![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-gui.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex) | [![javadoc](https://javadoc.io/badge2/world.convex/convex-gui/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-gui) |

For making use of Convex data structures, CVM execution etc. locally you probably just need `convex-core`. If you want to run a Peer or talk to a Peer over the network, then you will need `convex-peer` as a dependency. The other modules are mainly intended to run as standalone applications.

## Key features

* *Virtual Machine* - The Convex Virtual Machine provides a secure execution environment based on the Lambda Calculus and capable of acting as the execution layer for smart contracts and autonomous agents.
* *Decentralised Consensus* - Similar to Blockchain technology, Convex incorporates a consensus mechanism that ensures all nodes ultimately agree on true values in the system without the control of any single entity. This property means that it is inherently tamper-proof and censorship-resistant.
* *Performance and Scalability* - Convex is capable of executing large volumes of transactions (tens of thousands of transactions per second) with low latency (sub-second global consensus)
* *100% Green* - No wasteful consumption of energy or computing resources

## Running Convex

### Command Line Interface (CLI)

For more information about running a Convex Peer and the Command Line Interface see the [documentation](https://convex-dev.github.io/convex/convex-cli/)

### Local GUI Peers

The convex Peer Manager (GUI application) can be used to run a local test network.

This can be invoked by running the jar archive directly e.g. with the following command:

`java -jar convex-gui/target/convex-gui-0.7.0-SNAPSHOT-jar-with-dependencies.jar`

or you can run this from the command line by using the `local gui` command:

```
./convex local gui
```


## Contributing

Open Source contributions are welcome under the terms of the Convex Public License. Contributors retain copyright to their work, but must accept the terms of the license.

We are planning to institute a Contributors Agreement for all contributions to the core Convex repository.

The Convex Foundation may, at its sole discretion, award contributors with Convex Coins as recognition of value contributed to the Convex ecosystem. Convex coins are the native coin of the Convex network, and function as a utility token that provides the right to make use of the services of the network. Convex coins may be exchangeable for other digital assets and currencies.

## Community

We use Discord as the primary means for discussing Convex - you can join the public server at [https://discord.gg/5j2mPsk](https://discord.gg/5j2mPsk)

Alternatively, email: info(at)convex.world

## Copyright

Copyright 2017-2021 The Convex Foundation
