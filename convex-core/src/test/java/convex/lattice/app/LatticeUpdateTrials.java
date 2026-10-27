package convex.lattice.app;

import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;

/**
 * Performance trial for atomic adds to named counters held in a {@link LatticeHost}.
 *
 * <p>The counters index is the host root, so each add is one atomic update of the root.
 * Writer threads share that root: extra threads add contention, not throughput. Each run
 * checks that no add was lost. Rounds repeat the runs on fresh hosts, so JIT warm-up
 * shows in the first. Run with:</p>
 * <pre>
 * ./mvnw -q -pl convex-core test-compile exec:java -Dexec.mainClass=convex.lattice.app.LatticeUpdateTrials -Dexec.classpathScope=test
 * </pre>
 */
public final class LatticeUpdateTrials {
	private LatticeUpdateTrials() {}

	static final int ROUNDS=3;
	static final int ADDS=1_000_000;
	static final int[] THREADS={1,2,4,8};
	static final AString[] NAMES=new AString[1_000];
	static {
		for (int i=0;i<NAMES.length;i++) NAMES[i]=Strings.create("counter-"+i);
	}

	/** Named counters. */
	static final class Counters extends IndexComponent<AString,CVMLong> {
		Counters(AForkableCursor<Index<AString,CVMLong>> cursor) { super(cursor); }

		@Override protected Counters rebind(AForkableCursor<Index<AString,CVMLong>> cursor) {
			return new Counters(cursor);
		}
		@Override public Counters fork() { return rebind(storage.fork()); }

		/** Adds to a counter atomically; an absent counter starts at zero. */
		void add(AString name,long delta) {
			cursor().update(counters -> {
				Index<AString,CVMLong> index=(counters==null)?Index.none():counters;
				CVMLong count=index.get(name);
				return index.assoc(name,CVMLong.create((count==null)?delta:count.longValue()+delta));
			});
		}
	}

	public static void main(String... args) throws InterruptedException {
		for (int round=1;round<=ROUNDS;round++) {
			System.out.println("Round "+round);
			for (int threads:THREADS) concurrentAdds(threads);
		}
	}

	/** Each thread adds one to every counter in turn, starting at a different counter. */
	static void concurrentAdds(int threads) throws InterruptedException {
		var host=LatticeHost.create(Index.<AString,CVMLong>none(),null);
		var counters=new Counters(host.cursor());
		int perThread=ADDS/threads;
		var workers=new Thread[threads];
		long start=System.nanoTime();
		for (int t=0;t<threads;t++) {
			int offset=t*NAMES.length/threads;
			workers[t]=Thread.ofPlatform().start(() -> {
				for (int i=0;i<perThread;i++) counters.add(NAMES[(offset+i)%NAMES.length],1);
			});
		}
		for (Thread worker:workers) worker.join();
		long nanos=System.nanoTime()-start;

		long total=0;
		for (CVMLong count:host.cursor().get().values()) total+=count.longValue();
		if (total!=ADDS) throw new IllegalStateException("Counters total "+total+", expected "+ADDS);
		System.out.printf("%d thread(s) %,12d adds %,9.1f ms %,6d ns/add %,12d adds/s%n",
			threads,ADDS,nanos/1e6,nanos/ADDS,ADDS*1_000_000_000L/nanos);
	}
}
