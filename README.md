# Convex

Convex is an decentralised network and execution engine for the Internet of Value.

## About this repository

This repository contains the core Convex distribution including:

- The Convex Virtual Machine (CVM) including data structures and execution environment
- The standard Convex Peer server implementation (NIO based) implementing Converget Proof of Stake (CPoS) for consensus
- The Etch database for persistent data storage
- A Swing GUI for managing local peers
- Java Client API


## Key features

* *Virtual Machine* - The Convex Virtual Machine provides a secure execution environment based on the Lambda Calculus and capable of acting as the execution layer for smart contracts and autonomous agents.
* *Decentralised Consensus* - Similar to Blockchain technology, Convex incorporates a consensus mechanism that ensures all nodes ultimately agree on true values in the system without the control of any single entity. This property means that it is inherently tamper-proof and censorship-resistant.
* *Performance and Scalability* - Convex is capable of executing large volumes of transactions (1000s of transactions per second) with low latency (typically under a second for global consensus) 

## Running Convex

### Peer manager

The convex Peer Manager (GUI application) can be used to run a local test network.

This can be invoked by running `convex.gui.manager.PeerManager` as the main class, e.g. with the following command:

`java -jar convex.jar convex.gui.manager.PeerManager`

### Running benchmarks 

Convex includes some benchmarks, which are used to evaluate performance enhancments. These are mosty implemented with the JMH framework.

#### Directly running benchmarks

You can launch benchmarks as main classes in the `convex.performance` package, e.g.

`java -jar convex.jar convex.performance.EtchBenchmark`

#### Running with jfr

java -XX:+FlightRecorder -XX:StartFlightRecording=duration=200s,filename=flight.jfr convex.performance.CVMBenchmark

#### Runing with Maven

mvn test exec:java -Dexec.mainClass="convex.performance.CVMBenchmark" -Dexec.args="%classpath" -Dexec.classpathScope="test"

## Contributing

Open Source contributions are welcome under the terms of the Convex Public License. Contributors retain copyright to their work, but must accept the terms of the license.

The Convex Foundation may, at its sole discretion, award contributors with Convex Coins as recognition of value contributed to the Convex ecosystem. 

## Copyright

Copyright 2017-2020 The Convex Foundation 
