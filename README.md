# Convex

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

- Fungible Tokens
- Non-fungible tokens
- `convex.asset` - library for managing arbitrary digital assets using a common abstraction
- `convex.trust` - library for access control and trusted operations
- `torus.exchange` - decentralised exchange for trading fungible tokens and currencies
- Example code and templates for various forms of smart contracts

## Key features

* *Virtual Machine* - The Convex Virtual Machine provides a secure execution environment based on the Lambda Calculus and capable of acting as the execution layer for smart contracts and autonomous agents.
* *Decentralised Consensus* - Similar to Blockchain technology, Convex incorporates a consensus mechanism that ensures all nodes ultimately agree on true values in the system without the control of any single entity. This property means that it is inherently tamper-proof and censorship-resistant.
* *Performance and Scalability* - Convex is capable of executing large volumes of transactions (tens of thousands of transactions per second) with low latency (sub-second global consensus)
* *100% Green* - No wasteful consumption of energy or computing resources

## Running Convex

### Command Line Interface (CLI)

Convex is available to run as a CLI application out of the box. After building (with e.g. `mvn install`) it can be run directly as an executable `jar` file:

```
java -jar target/convex-jar-with-dependencies.jar <args>
```

Or using the convenience batch script in windows:

```
convex <args>
```

or in linux/mac you can use the shell script:

```
./convex <args>
```

### Staring a private local network using the CLI

If you want to start a local network for testing and try out Convex without accesing the public (test) network at https://convex.world. You can start a local network using the CLI command.

The local Convex network uses 3 types of files:

1. The *Etch Storage database*. This contains the stored state of the Convex network. Usually when starting up the initial cluster the first set of peers share the same Etch database. CLI Parameter: **--etch**

2. *Keystore file*. This file contains the private/public key pairs used for the peers and any subsequent users. CLI Parameters: **--keystore**, **--password**

3. *Session file*. This is created by the CLI to keep track of the running peers, so that if you want to access the local network or add another peer to the network, the CLI will look at the session file for a randomly available peer to connect too. CLI Parameter: **--session**


So to start a new network using the mimimum number of parameters:

```
./convex local start
```

This will startup 8 peers that are sharing the same Etch database.

To create another peer to join the network, you can enter the following command:

```
./convex peer create --password=my-secret
```
You wil always need to provide the `--password` so that the CLI can access your Keystore file and save the new peer key.

The `peer create` command will generate the peer start command so you can copy and paste it into the command line, such as..

```
./convex peer start --password=my-secret --address=48 --public-key=59a457
```

The `--address` parameter is the address of the peer account, `--public-key` is the first hex chars of the private/public key in your keystore file and `--password` is allowing access to your keystore file.



### Peer manager

The convex Peer Manager (GUI application) can be used to run a local test network.

This can be invoked by running `convex.gui.manager.PeerManager` as the main class, e.g. with the following command:

`java -cp convex.jar convex.gui.manager.PeerManager`

or you can run this from the command line by using the `peer manager` command:

```
./convex local manager
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
