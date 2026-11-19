package convex.benchmarks;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/** Collect keys in map order, comparing traversal with repeated indexed lookup. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
public class MapKeysBenchmark {
	@Param({"0","8","1024","65536"}) public int size;
	@Param({"hash","index"}) public String type;
	private AMap<?,?> map;

	@Setup public void setup() {
		if (type.equals("hash")) {
			var built=Maps.empty();
			for (int i=0;i<size;i++) built=built.assoc(CVMLong.create(i),null);
			map=built;
		} else {
			var built=Index.none();
			for (int i=0;i<size;i++) built=built.assoc(CVMLong.create(i).getHash(),null);
			map=built;
		}
		if (!traversal().equals(indexedLookup())) throw new AssertionError("Key order mismatch");
	}

	@Benchmark public AVector<?> traversal() { return map.getKeys(); }

	@Benchmark public AVector<?> indexedLookup() {
		ACell[] keys=new ACell[size];
		for (int i=0;i<size;i++) keys[i]=map.entryAt(i).getKey();
		return Vectors.wrap(keys);
	}
}
