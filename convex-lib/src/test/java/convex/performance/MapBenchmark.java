package convex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.data.AMap;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;

public class MapBenchmark {

	@Benchmark
	public void assocMap1000() {
		AMap<CVMLong, CVMLong> m = Maps.empty();
		for (long i = 0; i < 1000; i++) {
			CVMLong ci=CVMLong.create(i);
			m = m.assoc(ci, ci);
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(MapBenchmark.class);
		new Runner(opt).run();
	}
}
