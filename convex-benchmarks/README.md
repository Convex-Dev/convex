# Convex Benchmarks

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-benchmarks.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)

Performance benchmarking suite for [Convex](https://convex.world) using the JMH (Java Microbenchmark Harness) framework.

## Available Benchmarks

| Benchmark | Description |
|-----------|-------------|
| `CVMBenchmark` | Convex Virtual Machine execution performance |
| `EtchBenchmark` | Etch database read/write throughput |
| `HashBenchmark` | Cryptographic hashing operations |
| `SignatureBenchmark` | Ed25519 signature generation and verification |
| `DataStructureBenchmark` | Immutable data structure operations |

## Running Benchmarks

### Build

```bash
cd convex
mvn clean install -pl convex-benchmarks -am
```

### Execute

Run a specific benchmark:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  convex.benchmarks.EtchBenchmark
```

### With Profiling (Java Flight Recorder)

Generate profiling data for analysis:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  -XX:+FlightRecorder \
  -XX:StartFlightRecording=duration=200s,filename=flight.jfr \
  convex.benchmarks.CVMBenchmark
```

Open `flight.jfr` in JDK Mission Control for detailed analysis.

## Example Results

```
Benchmark                      Mode  Cnt        Score        Error  Units
EtchBenchmark.readDataRandom  thrpt    5  4848620.857 ± 110622.054  ops/s
EtchBenchmark.writeData       thrpt    5   728486.145 ± 168739.491  ops/s
```

This indicates ~4.8 million reads/sec and ~728k writes/sec for the Etch database layer. Results vary based on hardware (RAM, disk speed, CPU).

## Documentation

- [Convex Documentation](https://docs.convex.world)
- [JMH Documentation](https://openjdk.org/projects/code-tools/jmh/)
- [JDK Mission Control](https://www.oracle.com/java/technologies/jdk-mission-control.html)

## License

Copyright 2020-2025 The Convex Foundation and Contributors

Code in convex-benchmarks is provided under the [Convex Public License](../LICENSE.md).
