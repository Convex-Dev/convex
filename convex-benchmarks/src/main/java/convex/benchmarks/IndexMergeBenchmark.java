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

import convex.core.data.Index;
import convex.core.data.Hash;

import convex.core.data.prim.CVMLong;

/** Resident right-biased Index merge versus the original per-entry insertion loop. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3, time=1)
@Measurement(iterations=5, time=1)
public class IndexMergeBenchmark {
	@Param({"8", "1024", "65536"}) public int size;
	@Param({"identical", "shared", "overlap", "disjoint", "single", "smallLeft"}) public String scenario;
	private Index<Hash, CVMLong> left, right;

	@Setup public void setup() {
		left = range(0, size, CVMLong.ONE);
		right = switch (scenario) {
			case "identical" -> left;
			case "shared" -> left.assoc(CVMLong.create(size / 2).getHash(), null);
			case "overlap" -> range(size / 2, size, CVMLong.ZERO);
			case "disjoint" -> range(size, size, CVMLong.ZERO);
			case "single" -> Index.of(CVMLong.create(size / 2).getHash(), null);
			case "smallLeft" -> left;
			default -> throw new IllegalArgumentException(scenario);
		};
		if (scenario.equals("smallLeft")) left = Index.of(CVMLong.create(size).getHash(), null);
		// Validate outside the timed section; keep identical resident inputs for both methods.
		if (!structural().equals(insertionLoop())) throw new AssertionError("Merge mismatch");
	}

	private static Index<Hash, CVMLong> range(int start, int count, CVMLong value) {
		Index<Hash, CVMLong> result = Index.none();
		for (int i = 0; i < count; i++) result = result.assoc(CVMLong.create(start + i).getHash(), value);
		return result;
	}

	@Benchmark public Index<Hash, CVMLong> structural() { return left.merge(right); }

	@Benchmark public Index<Hash, CVMLong> insertionLoop() {
		Index<Hash, CVMLong> result = left;
		for (int i = 0; i < right.count(); i++) result = result.assocEntry(right.entryAt(i));
		return result;
	}
}

