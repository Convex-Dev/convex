package convex.benchmarks;

import java.util.Random;
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

import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.prim.CVMLong;

/** Joining ordered resident ranges, with ordinary map merge as a baseline. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
public class IndexConcatBenchmark {
	@Param({"1024","65536"}) public int size;
	@Param({"balanced","append","prepend"}) public String split;
	private Index<Blob,CVMLong> index,left,right;
	private long cut;

	@Setup public void setup() {
		Random random=new Random(12345);
		index=Index.none();
		for (int i=0;i<size;i++) {
			byte[] bytes=new byte[16];
			random.nextBytes(bytes);
			index=index.assoc(Blob.wrap(bytes),CVMLong.ONE);
		}
		cut=switch (split) {
			case "balanced" -> size/2;
			case "append" -> size-1;
			case "prepend" -> 1;
			default -> throw new IllegalArgumentException(split);
		};
		left=index.slice(0,cut);
		right=index.slice(cut);
	}

	@Benchmark public Index<Blob,CVMLong> concat() { return left.concat(right); }

	@Benchmark public AMap<Blob,CVMLong> merge() { return left.merge(right); }

	@Benchmark public Index<Blob,CVMLong> splitAndRejoin() {
		return index.slice(0,cut).concat(index.slice(cut));
	}
}
