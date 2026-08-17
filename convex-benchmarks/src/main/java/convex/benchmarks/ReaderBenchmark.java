package  convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.lang.Reader;

/**
 * Benchmarks for Convex Lisp reader performance.
 *
 * Included to test parsing throughput, since reading source forms is on the
 * path for transaction construction and REPL usage.
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
