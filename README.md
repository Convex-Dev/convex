# Convex

Convex is an engine for building and running trusted, decentralised applications.

## Key features

* *Virtual Machine* - The Convex Virtual Machine provides a secure execution environment based on the Lambda Calculus and capable of acting as the execution layer for smart contracts and autonomous agents.
* *Decentralised Consensus* - Similar to Blockchain technology, Convex incorporates a consensus mechanism that ensures all nodes ultimately agree on true values in the system without the control of any single entity. This property means that it is inherently tamper-proof and censorship-resistant.
* *Performance and Scalability* - Convex is capable of executing large volumes of transactions (1000s of transactions per second) with low latency (typically ~1 second for global consensus) 


### Running with jfr

java -XX:+FlightRecorder -XX:StartFlightRecording=duration=200s,filename=flight.jfr convex.performance.CVMBenchmark

### Runing benchmark with Maven

mvn test exec:java -Dexec.mainClass="convex.performance.CVMBenchmark" -Dexec.args="%classpath" -Dexec.classpathScope="test"


