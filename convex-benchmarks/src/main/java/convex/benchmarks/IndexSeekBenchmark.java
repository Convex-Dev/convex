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

import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/** Resident lower-bound search; run with JMH's GC profiler to check allocation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
public class IndexSeekBenchmark {

	@Param({"1024","65536"}) public int size;
	@Param({"random","timestamp"}) public String layout;
	@Param({"hit","miss","prefix"}) public String query;

	private Index<Blob,CVMLong> index;
	private Blob[] queries;
	private int next;

	@Setup public void setup() {
		Random random=new Random(12345);
		Blob[] keys=new Blob[size];
		index=Index.none();
		for (int i=0;i<size;i++) {
			byte[] bytes=new byte[16];
			random.nextBytes(bytes);
			if (layout.equals("timestamp")) Utils.writeLong(bytes,0,1700000000000L+i/16);
			keys[i]=Blob.wrap(bytes);
			index=index.assoc(keys[i],CVMLong.ONE);
		}
		queries=new Blob[1024];
		for (int i=0;i<queries.length;i++) {
			byte[] bytes=keys[random.nextInt(size)].getBytes();
			if (query.equals("miss")) bytes[15]^=1;
			queries[i]=query.equals("prefix")?Blob.wrap(bytes,0,8):Blob.wrap(bytes);
		}
		// The measured workload has resident keys/nodes and warm prefix caches.
		for (Blob key:queries) index.seek(key);
	}

	@Benchmark public long seek() {
		return index.seek(queries[next++ & (queries.length-1)]);
	}

	/** Baseline using only existing public ordered-index operations. */
	@Benchmark public long binarySearch() {
		Blob key=queries[next++ & (queries.length-1)];
		long low=0, high=index.count();
		while (low<high) {
			long mid=(low+high)>>>1;
			if (index.entryAt(mid).getKey().compareTo(key)<0) low=mid+1;
			else high=mid;
		}
		return low;
	}
}
