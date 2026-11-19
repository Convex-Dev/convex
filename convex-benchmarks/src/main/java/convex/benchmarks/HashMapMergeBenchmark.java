package convex.benchmarks;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import convex.core.data.AHashMap;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;

/** Resident right-biased hash-map merge versus the original per-entry insertion loop. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3, time=1)
@Measurement(iterations=5, time=1)
public class HashMapMergeBenchmark {
	@Param({"8", "1024", "65536"}) public int size;
	@Param({"identical", "shared", "overlap", "disjoint", "single", "smallLeft"}) public String scenario;
	private AHashMap<CVMLong, CVMLong> left, right;

	@Setup public void setup() {
		left = range(0, size, CVMLong.ONE);
		right = switch (scenario) {
			case "identical" -> left;
			case "shared" -> left.assoc(CVMLong.create(size / 2), null);
			case "overlap" -> range(size / 2, size, CVMLong.ZERO);
			case "disjoint" -> range(size, size, CVMLong.ZERO);
			case "single" -> Maps.create(CVMLong.create(size / 2), null);
			case "smallLeft" -> left;
			default -> throw new IllegalArgumentException(scenario);
		};
		if (scenario.equals("smallLeft")) left = Maps.create(CVMLong.create(size), null);
		// Validate outside the timed section; keep identical resident inputs for both methods.
		if (!structural().equals(insertionLoop())) throw new AssertionError("Merge mismatch");
	}

	private static AHashMap<CVMLong, CVMLong> range(int start, int count, CVMLong value) {
		AHashMap<CVMLong, CVMLong> result = Maps.empty();
		for (int i = 0; i < count; i++) result = result.assoc(CVMLong.create(start + i), value);
		return result;
	}

	@Benchmark public AHashMap<CVMLong, CVMLong> structural() { return left.merge(right); }

	@Benchmark public AHashMap<CVMLong, CVMLong> insertionLoop() {
		AHashMap<CVMLong, CVMLong> result = left;
		for (int i = 0; i < right.count(); i++) result = result.assocEntry(right.entryAt(i));
		return result;
	}
}
