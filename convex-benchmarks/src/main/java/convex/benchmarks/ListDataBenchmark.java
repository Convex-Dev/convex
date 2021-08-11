package convex.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public class ListDataBenchmark {

	@Benchmark
	public void append1000() {
		AVector<CVMLong> list = Vectors.empty();
		for (long i = 0; i < 1000; i++) {
			list = list.append(CVMLong.create(i));
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(ListDataBenchmark.class);
		new Runner(opt).run();
	}
}
