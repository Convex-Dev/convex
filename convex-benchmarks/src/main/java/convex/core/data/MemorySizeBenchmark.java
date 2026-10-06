package convex.core.data;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import convex.core.Constants;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;

/**
 * Memory accounting on cached values and newly changed state paths.
 * Uses the data package to reset only the memory-size cache in sizing-only
 * benchmarks, without adding allocation or reflection to the measured work.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations=3, time=1)
@Measurement(iterations=5, time=1)
public class MemorySizeBenchmark {
	private convex.core.cvm.State state;
	private AccountStatus account;
	private ACell[] children;
	private ACell flat;
	private ACell[] path;
	private long balance=10000;
	private final Address address=Address.create(2000);

	@Setup(Level.Trial)
	public void setup() {
		account=AccountStatus.create(1000,null);
		AVector<AccountStatus> accounts=Vectors.repeat(account,4096);
		state=convex.core.cvm.State.create(accounts,convex.core.cvm.State.EMPTY_PEERS,
			Constants.INITIAL_GLOBALS,convex.core.cvm.State.EMPTY_SCHEDULE);
		state.getMemorySize();
		Blob leaf=Blob.wrap(new byte[256]);
		leaf.getMemorySize();
		children=new ACell[16];
		Arrays.fill(children,leaf);
		flat=Vectors.create(children);
		flat.getMemorySize();
		path=new ACell[8];
		ACell cell=leaf;
		for (int i=0; i<path.length; i++) {
			cell=Vectors.create(cell,leaf,leaf,leaf,leaf);
			path[i]=cell;
		}
		cell.getMemorySize();
	}

	@Benchmark
	public long cachedRoot() {
		return state.getMemorySize();
	}

	@Benchmark
	public long sizeCachedChildren() {
		flat.memorySize=-1;
		return flat.getMemorySize();
	}

	@Benchmark
	public long newParentCachedChildren() {
		return Vectors.create(children).getMemorySize();
	}

	@Benchmark
	public long accountUpdate() {
		return account.withBalance(balance++).getMemorySize();
	}

	@Benchmark
	public long stateUpdate() {
		return state.withBalance(address,balance++).getMemorySize();
	}

	@Benchmark
	public long eightColdLevels() {
		for (ACell cell:path) cell.memorySize=-1;
		return path[path.length-1].getMemorySize();
	}
}
