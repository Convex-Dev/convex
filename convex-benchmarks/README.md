# Convex Benchmarks

## Benchmarking

Convex includes a wide set of benchmarks, which are used to evaluate performance enhancements. These are mostly implemented with the JMH framework, and reside in the `convex.benchmarks` package.

## Preparing to run benchmarks

To run benchmarks, it is best to build the full `convex-benchmarks` jar with dependencies which includes all benchmarks, tests and dependencies. This can be done with the following commend:

`mvn clean install`

## Directly running benchmarks

After building the testing `.jar`, you can launch benchmarks as main classes in the `convex.benchmarks` package, e.g.

```
java -cp target/convex-benchmarks-jar-with-dependencies.jar convex.benchmarks.EtchBenchmark
```

## Running with Java Flight Recorder

If you want to analyse profiling results for the benchmarks, you can run using JFR to produce a profiling output file `flight.jfr`

```
java -cp target/convex-benchmarks-jar-with-dependencies.jar -XX:+FlightRecorder -XX:StartFlightRecording=duration=200s,filename=flight.jfr convex.benchmarks.CVMBenchmark
```

The resulting `flight.jfr` can the be opened in tools such as JDK Mission Control which enables detailed analysis and visualisation of profiling results. This is a useful approach that the Convex team use to identify performance bottlenecks.

## Benchmark results

After running benchmarks, you should see results similar to this:

```
Benchmark                      Mode  Cnt        Score        Error  Units
EtchBenchmark.readDataRandom  thrpt    5  4848620.857 ± 110622.054  ops/s
EtchBenchmark.writeData       thrpt    5   728486.145 ± 168739.491  ops/s
```

For example, this can be interpreted as an indication that the Etch database layer is handling approximately 4.8 million reads and 729k million atomic writes per second in the testing environment. Usual benchmarking caveats apply and results may vary considerably based on your system setup (available RAM, disk performance etc.) - it is advisable to examine the benchmark source to determine precisely which operations are being performed.
