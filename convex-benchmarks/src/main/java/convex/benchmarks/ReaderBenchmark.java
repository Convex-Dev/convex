package  convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.lang.Reader;

/**
 * Benchmarks for hashing performance.
 * 
 * Included to test hashing algorithm performance, since this might be a
 * bottleneck in some scenarios.
 */
public class ReaderBenchmark {

	@Benchmark
	public void readNil() {
		Reader.read("nil");
	}
	
	@Benchmark
	public void readFunction() {
		Reader.read("(fn [a b] [b a])");
	}
	
	@Benchmark
	public void readVector() {
		Reader.read("[1 2 3]");
	}
	
	@Benchmark
	public void readList() {
		Reader.read("(foo bar baz)");
	}

	
	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(ReaderBenchmark.class);
		new Runner(opt).run();
	}
}
