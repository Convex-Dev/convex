# Convex Benchmarks

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-benchmarks.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)

Performance benchmarking suite for [Convex](https://convex.world) using the JMH (Java Microbenchmark Harness) framework.

## Available Benchmarks

| Benchmark | Description |
|-----------|-------------|
| `CVMBenchmark` | Convex Virtual Machine execution performance |
| `CVMQueryBenchmark` | CVM query evaluation throughput and thread scaling (standalone probe, not JMH) |
| `EvalBenchmark` | CVM evaluation of compiled forms |
| `OpBenchmark` | Execution of low-level CVM ops |
| `BigBlockBenchmark` | Applying a large block of signed transactions to state |
| `MapBenchmark` | Immutable map update operations |
| `IndexSeekBenchmark` | Lower-bound search for exact keys, absent keys and prefixes |
| `IndexSliceBenchmark` | Structural slices and seek-based cuts against per-entry deletion |
| `IndexConcatBenchmark` | Ordered concatenation and split/rejoin against ordinary map merge |
| `MemorySizeBenchmark` | Memory accounting for cached cells and newly changed state paths |
| `ListDataBenchmark` | Immutable list append operations |
| `EncodingBenchmark` | Data encoding and decoding |
| `ReaderBenchmark` | Convex Lisp reader parsing performance |
| `EtchBenchmark` | Etch database read/write throughput |
| `EtchCacheBenchmark` | L1 lookup and decode-hit latency for direct and soft refs |
| `EtchConcurrentBenchmark` | Fixed-work concurrent Etch throughput and reopen verification |
| `HashBenchmark` | Cryptographic hashing operations |
| `SignatureBenchmark` | Ed25519 signature generation and verification |
| `LatencyBenchmark` | Round-trip transaction latency on a small local peer network |
| `LocalPeerBenchmark` | Transactions and queries through a local in-process peer |
| `ThreadCoordinationBenchmark` | Inter-thread queue handoff overhead |

## Running Benchmarks

### Build

```bash
cd convex
./mvnw -B -T1C install -pl convex-benchmarks -am
```

### Execute

Run a specific benchmark:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  convex.benchmarks.EtchBenchmark
```

Profile memory-accounting allocations as well as elapsed time:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  org.openjdk.jmh.Main MemorySizeBenchmark -prof gc
```

The sizing-only cases reuse cells and reset their memory-size caches; the update
cases include constructing the changed values. Compare allocation per operation
to distinguish traversal overhead from those necessary value allocations.

Measure index lower-bound searches and allocation:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  org.openjdk.jmh.Main IndexSeekBenchmark -prof gc
```

The benchmark compares direct radix descent with binary search using `entryAt`.
Parameters cover 1,024 and 65,536 entries, random and timestamp-plus-salt keys,
and exact, absent and prefix queries. Queries are preallocated and cycle through
a deterministic set. Nodes and prefix caches are warm; this measures resident
search cost, not cold store loading. Use multiple forks and compare confidence
intervals when evaluating changes to the traversal.

A local JDK 25.0.1 run on 2026-10-24 used two forks, three 300 ms warmup
iterations and four 300 ms measurement iterations. At 65,536 entries it measured:

| Keys / query | Radix seek (ns/op) | Binary search (ns/op) |
|---|---:|---:|
| Random / present | 99 | 1,717 |
| Random / absent | 113 | 1,695 |
| Random / prefix | 108 | 1,684 |
| Timestamp / present | 125 | 1,251 |
| Timestamp / absent | 128 | 1,320 |
| Timestamp / prefix | 57 | 1,269 |

Seek allocation was below 0.003 bytes/op (measurement noise), with no collections
reported. An isolated variant always summing preceding children measured
116–161 ns/op for the four full-key cases, versus 99–128 ns/op for counting
from the cheaper side. Small-index differences were often within the confidence
intervals. These are indicative resident-data measurements, not latency promises;
rerun on the target workload and hardware.

Measure structural slices, including the old deletion loop as a baseline:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  org.openjdk.jmh.Main IndexSliceBenchmark -prof gc
```

Parameters cover 1,024 and 65,536 resident random keys, retaining either half,
ten middle entries, or all but the first and last entries. `seekAndSlice` includes
locating both key bounds. The source index is immutable and reused across calls.

For the initial iterative slice implementation, a local JDK 25.0.1 run on
2026-10-24 used two forks, three 300 ms warmup
iterations, four 300 ms measurement iterations, and `-Xms256m -Xmx512m`.
At 65,536 entries:

| Retained range | Structural slice (ns/op) | Deletion loop (ns/op) | Structural allocation (bytes/op) |
|---|---:|---:|---:|
| First half | 128 | 5,044,014 | 816 |
| Last half | 168 | 6,171,760 | 744 |
| Ten middle entries | 108 | 10,409,255 | 576 |
| All except first and last | 280 | 346 | 1,504 |

The ten-entry cut including both seeks measured 203 ns/op. Its deletion baseline
allocated 31.9 MB/op. Removing only two entries still favours the old loop on
allocation (1,248 bytes/op), though structural slicing was faster in this run.
These are indicative resident-data measurements; short runs have noise, and
lazy store loads have separate costs. Empty and complete slices return existing
objects without traversal or allocation.

Measure ordered concatenation and split/rejoin:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  org.openjdk.jmh.Main IndexConcatBenchmark -prof gc
```

The benchmark joins independently sliced, ordered ranges of resident random keys.
Parameters cover 1,024 and 65,536 total entries, split equally or with a single
entry on either side. `merge` is the ordinary per-entry map merge baseline;
`splitAndRejoin` includes both slices and the concatenation. Boundary validation
is included in every concatenation measurement.

For the initial iterative concat implementation, a local JDK 25.0.1 run on
2026-10-24 used two forks, three 300 ms warmup
iterations, four 300 ms measurement iterations, and `-Xms256m -Xmx512m`.
At 65,536 total entries:

| Split | Validated concat (ns/op) | Map merge (ns/op) | Concat allocation (bytes/op) |
|---|---:|---:|---:|
| Equal halves | 573 | 7,636,518 | 1,016 |
| Single entry on right | 461 | 172 | 848 |
| Single entry on left | 441 | 17,816,496 | 856 |

Splitting into halves and rejoining measured 904 ns/op and 2,576 bytes/op.
The balanced map merge allocated 20.9 MB/op. Single-entry append remains cheaper
through ordinary insertion: map merge does not check that the new key follows
the existing range. These fixed, resident ranges measure traversal and allocation;
they do not include store loading, and short-run timings are indicative.

Slice and concat now use recursive boundary traversal, retaining the iterative
implementations as fallbacks after stack overflow. Ordinary calls allocate no
traversal frames; new Index nodes and child arrays are still required. Slice
also avoids revisiting retained interior child roots after selecting its range.

A before/after comparison on 2026-10-24 used the same JDK, heap settings and
resident inputs at both 1,024 and 65,536 entries. Slice and split/rejoin used two
forks, four 500 ms warmup iterations and five 500 ms measurement iterations.
Standalone concat used two forks, three 300 ms warmup iterations and four
300 ms measurement iterations. All runs used `-prof gc`. At 65,536 entries:

| Operation | Iterative (ns/op) | Recursive (ns/op) | Iterative (bytes/op) | Recursive (bytes/op) |
|---|---:|---:|---:|---:|
| Slice first half | 123 | 78 | 816 | 536 |
| Slice last half | 136 | 94 | 744 | 464 |
| Slice ten middle entries | 105 | 96 | 576 | 240 |
| Slice all except first and last | 269 | 164 | 1,504 | 1,112 |
| Concat equal halves | 612 | 594 | 1,016 | 736 |
| Split into halves and rejoin | 876 | 789 | 2,576 | 1,736 |

Allocation fell in every measured case. Standalone concat's timing difference
was within the measurement uncertainty; the larger slice improvements were
clearer. Maximum-depth small-stack tests separately verify recovery through
the fallback. These resident benchmarks measure the normal path: overflowing
the stack incurs a retry, so the same timing gains are not claimed for it.

Measure Etch L1 hits and allocation with both unpersisted direct refs and stored
soft refs:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  org.openjdk.jmh.Main EtchCacheBenchmark -prof gc
```

These cases hold the value in memory and exercise `checkCache` and `decode`,
including their hit counters. They measure L1 hit overhead, not disk access or
contention between threads.

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

## Concurrent Etch workload

`EtchConcurrentBenchmark` is an end-to-end fixed-work utility rather than a
JMH microbenchmark. It preloads deterministic values, runs multiple readers and
writers together, flushes and closes the store, then reopens it and verifies a
sample of both preloaded and concurrently written values. Reader work mixes
random absent hashes, cache-path hits, direct store reads and reads of freshly
published writes.

The defaults perform 150 million reads and one million writes, with 50 syncs
distributed evenly through one reader's progress so they span the concurrent
phase without a contended global counter. They are intended to take roughly 20
seconds on a development machine. Work counts are fixed, so different store
configurations receive exactly the same workload:

```bash
java -cp convex-benchmarks/target/convex-benchmarks-jar-with-dependencies.jar \
  convex.benchmarks.EtchConcurrentBenchmark \
  --config v1-mapped \
  --config v2-mapped \
  --config v3-auto \
  --config v3-aes-auto \
  --config v3-chacha-auto
```

Use `--help` for workload sizes, thread counts, read-mix controls, deterministic
seed selection and file-retention options. `v2-auto` selects the runtime's
default mapper; append `-no-chains` to a configuration name to disable short
collision chains. V3 supports `v3-auto` (plaintext), `v3-aes-auto` and
`v3-chacha-auto` (encrypted data with a plaintext index), plus
`v3-aes-index-auto` and `v3-chacha-index-auto` (encrypted data and index).
The utility uses a fixed public benchmark secret for reproducibility; retained
benchmark files are therefore not confidential.

## Documentation

- [Convex Documentation](https://docs.convex.world)
- [JMH Documentation](https://openjdk.org/projects/code-tools/jmh/)
- [JDK Mission Control](https://www.oracle.com/java/technologies/jdk-mission-control.html)

## License

Copyright 2020-2025 The Convex Foundation and Contributors

Code in convex-benchmarks is provided under the [Convex Public License](../LICENSE.md).
