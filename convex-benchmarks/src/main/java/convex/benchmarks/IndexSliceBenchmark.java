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

/** Structural slicing against the former deletion loop, on resident random keys. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
public class IndexSliceBenchmark {
	@Param({"1024","65536"}) public int size;
	@Param({"prefix","suffix","middle","almostAll"}) public String range;
	private Index<Blob,CVMLong> index;
	private long start,end;
	private Blob lower,upper;

	@Setup public void setup() {
		Random random=new Random(12345);
		index=Index.none();
		for (int i=0;i<size;i++) {
			byte[] bytes=new byte[16];
			random.nextBytes(bytes);
			index=index.assoc(Blob.wrap(bytes),CVMLong.ONE);
		}
		switch (range) {
			case "prefix": start=0; end=size/2; break;
			case "suffix": start=size/2; end=size; break;
			case "middle": start=size/2; end=start+10; break;
			case "almostAll": start=1; end=size-1; break;
			default: throw new IllegalArgumentException(range);
		}
		lower=index.entryAt(start).getKey();
		upper=end==size?null:index.entryAt(end).getKey();
		index.seek(lower);
		if (upper!=null) index.seek(upper);
	}

	@Benchmark public Index<Blob,CVMLong> slice() { return index.slice(start,end); }

	@Benchmark public Index<Blob,CVMLong> seekAndSlice() {
		return index.slice(index.seek(lower),upper==null?index.count():index.seek(upper));
	}

	/** Exact previous implementation, retained only as a benchmark baseline. */
	@Benchmark public Index<Blob,CVMLong> deleteEntries() {
		var result=index;
		for (long i=index.count()-1;i>=end;i--) result=result.dissoc(result.entryAt(i).getKey());
		for (long i=0;i<start;i++) result=result.dissoc(result.entryAt(0).getKey());
		return result;
	}
}
