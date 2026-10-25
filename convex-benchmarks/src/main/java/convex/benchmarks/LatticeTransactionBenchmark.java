package convex.benchmarks;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.ACursor;
import convex.lattice.cursor.Root;
import convex.lattice.cursor.TransactionalRootCursor;

/** Isolates the cost of root write coordination, without signing or persistence. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3,time=1)
@Measurement(iterations=5,time=1)
@Fork(2)
public class LatticeTransactionBenchmark {

	private final Root<CVMLong> plain=new Root<>(CVMLong.ZERO);
	private final TransactionalRootCursor<CVMLong> coordinated=new TransactionalRootCursor<>(CVMLong.ZERO);
	private static final Keyword REGION=Keyword.intern("region"), OWNER=Keyword.intern("owner"), VALUE=Keyword.intern("value");
	private final ACursor<CVMLong> plainPath=new Root<>(initial()).path(REGION,OWNER,VALUE);
	private final ACursor<CVMLong> coordinatedPath=new TransactionalRootCursor<>(initial()).path(REGION,OWNER,VALUE);

	private static AHashMap<ACell,ACell> initial() {
		AHashMap<ACell,ACell> entries=Maps.empty();
		for (int i=0;i<32;i++) entries=entries.assoc(CVMLong.create(i),CVMLong.create(i));
		entries=entries.assoc(VALUE,CVMLong.ZERO);
		return Maps.of(REGION,Maps.of(OWNER,entries));
	}

	private static CVMLong toggle(CVMLong value) {
		return value.longValue()==0?CVMLong.ONE:CVMLong.ZERO;
	}

	@Benchmark public CVMLong plainRootUpdate() { return plain.updateAndGet(LatticeTransactionBenchmark::toggle); }
	@Benchmark public CVMLong coordinatedRootUpdate() { return coordinated.updateAndGet(LatticeTransactionBenchmark::toggle); }
	@Benchmark public CVMLong plainPathUpdate() { return plainPath.updateAndGet(LatticeTransactionBenchmark::toggle); }
	@Benchmark public CVMLong coordinatedPathUpdate() { return coordinatedPath.updateAndGet(LatticeTransactionBenchmark::toggle); }
}
