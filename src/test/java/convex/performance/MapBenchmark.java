package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.AMap;
import convex.core.data.Maps;

public class MapBenchmark {

	@Benchmark
	public void longHash() {
		AMap<Long, Long> m = Maps.empty();
		for (long i = 0; i < 1000; i++) {
			m = m.assoc(i, i);
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(MapBenchmark.class);
		new Runner(opt).run();
	}
}
