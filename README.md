# Convex

Convex is an decentralised network and execution engine for the Internet of Value.

It is designed as a foundational layer for decentralised application and economic systems that manage digital assets, where asset ownership is cryptographically secured. It can be considered functionally similar to a decentralised public blockchain, but offers some significant advantages:

- High transaction throughput (tens of thousands of transactions per second)
- Low latency for transaction confirmation (a couple of second for global consensus)
- Energy efficiency using the the Convergent Proof of Stake consensus algorithm
- Smart contract capability with Turing Complete, atomic transactions
- Integrated on-chain compiler

## About this repository

This repository contains the core Convex distribution including:

- The Convex Virtual Machine (CVM) including data structures and execution environment
- The standard Convex Peer server implementation (NIO based) implementing Convergent Proof of Stake (CPoS) for consensus
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
- Example code and templates for various forms of smrt contracts

## Key features

* *Virtual Machine* - The Convex Virtual Machine provides a secure execution environment based on the Lambda Calculus and capable of acting as the execution layer for smart contracts and autonomous agents.
* *Decentralised Consensus* - Similar to Blockchain technology, Convex incorporates a consensus mechanism that ensures all nodes ultimately agree on true values in the system without the control of any single entity. This property means that it is inherently tamper-proof and censorship-resistant.
* *Performance and Scalability* - Convex is capable of executing large volumes of transactions (tens of thousands of transactions per second) with low latency (a second or two for global consensus) 

## Running Convex

### Command Line Interface (CLI)

Convex is available to run as a CLI application out of the box. After building (with e.g. `mvn install`) it can be run directly as an executable `jar` file:

```
java -jar target/convex.jar <args>
```

Or using the convenience batch / shell scripts:

```
convex <args>
```

A common usage of the CLI would be to start a Convex peer:

```
convex peer start
```

Which initiates a peer server using the current local configuration.

### Peer manager

The convex Peer Manager (GUI application) can be used to run a local test network.

This can be invoked by running `convex.gui.manager.PeerManager` as the main class, e.g. with the following command:

`java -jar convex.jar convex.gui.manager.PeerManager`

### Benchmarking

Convex includes a wide set of benchmarks, which are used to evaluate performance enhancements. These are mostly implemented with the JMH framework, and reside in the `convex.performance` package.

#### Preparing to run benchmarks

To run benchmarks, it is easiest to build the `convex-testing.jar` which includes all benchmarks, tests and dependencies. This can be done with the following commend:

`mvn package`

#### Directly running benchmarks

After building the testing `.jar`, you can launch benchmarks as main classes in the `convex.performance` package, e.g.

`java -cp target/convex-testing.jar convex.performance.EtchBenchmark`

#### Running with Java Flight Recorder

If you want to analyse profiling results for the benchmarks, you can run using JFR to produce a profiling output file `flight.jfr`

`java -cp target/convex-testing.jar -XX:+FlightRecorder -XX:StartFlightRecording=duration=200s,filename=flight.jfr -Djava.util.logging.config.file=logging.properties convex.performance.CVMBenchmark`

The resulting `flight.jfr` can the be opened in tools such as JDK Mission Control which enables detailed analysis and visualisation of profiling results. This is a useful approach that the Convex team use to identify performance bottlenecks.

#### Benchmark results

After running benchmarks, you should see results similar to this:

```
Benchmark                      Mode  Cnt         Score         Error  Units
EtchBenchmark.readDataRandom  thrpt    5  40929172.124 ± 1760027.956  ops/s
EtchBenchmark.writeData       thrpt    5  10923667.591 ± 1527754.917  ops/s
```

For example, this can be interpreted as an indication that the Etch database layer is handling approximately 40 million reads and 10 million atomic writes per second in the testing environment. Usual benchmarking caveats apply and results may vary considerably based on your system setup (available RAM, disk performance etc.) - it is advisable to examine the benchmark source to determine precisely which operations are being performed.


## Contributing

Open Source contributions are welcome under the terms of the Convex Public License. Contributors retain copyright to their work, but must accept the terms of the license.

The Convex Foundation may, at its sole discretion, award contributors with Convex Coins as recognition of value contributed to the Convex ecosystem. Convex coins are the native coin of the Convex network, and function as a utility token that provides the right to make use of the services of the network. Convex coins may be exchangeable for other digital assets and currencies.

## Community

We use Discord as the primary means for discussing Convex - you can join the public server at [https://discord.gg/5j2mPsk](https://discord.gg/5j2mPsk)

## Copyright

Copyright 2017-2021 The Convex Foundation 
