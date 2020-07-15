package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.AVector;
import convex.core.data.Vectors;

public class ListDataBenchmark {

	@Benchmark
	public void benchmark() {
		AVector<Integer> list = Vectors.empty();
		for (int i = 0; i < 1000; i++) {
			list = list.append(i);
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(ListDataBenchmark.class);
		new Runner(opt).run();
	}
}
