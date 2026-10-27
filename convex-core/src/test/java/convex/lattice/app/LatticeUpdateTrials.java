package convex.lattice.app;

import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;

/**
 * Performance trial for atomic adds to named counters, published to a {@link LatticeHost}
 * once a second.
 *
 * <p>The counters are a region of the host root. Adds go to a private draft, one atomic
 * update each, and the draft is published once a second. Publication hashes the changed
 * counters, so the draft pays that once per publication rather than per add. The draft is
 * the region's only writer, so publication never needs to merge. One thread adds and
 * publishes; the run checks that every add reached the host. Run with, optionally
 * giving the length of the run in seconds:</p>
 * <pre>
 * ./mvnw -q -pl convex-core test-compile exec:java -Dexec.mainClass=convex.lattice.app.LatticeUpdateTrials -Dexec.classpathScope=test -Dexec.args=120
 * </pre>
 */
public final class LatticeUpdateTrials {
	private LatticeUpdateTrials() {}

	static final Keyword COUNTERS=Keyword.intern("counters");
	static final int SECONDS=10;
	static final AString[] NAMES=new AString[100];
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

	public static void main(String... args) {
		int seconds=(args.length==0)?SECONDS:Integer.parseInt(args[0]);
		var host=LatticeHost.create();
		var counters=new Counters(host.cursor().path(COUNTERS)).fork();
		long total=0;
		long next=System.nanoTime();
		for (int second=1;second<=seconds;second++) {
			next+=1_000_000_000L;
			long adds=0;
			while (System.nanoTime()<next) {
				for (AString name:NAMES) counters.add(name,1);
				adds+=NAMES.length;
			}
			long start=System.nanoTime();
			counters.sync();
			long publish=System.nanoTime()-start;
			total+=adds;
			System.out.printf("second %2d %,12d adds %,9d ns publish%n",second,adds,publish);
		}
		System.out.printf("average   %,12d adds/s%n",total/seconds);

		long published=0;
		for (CVMLong count:new Counters(host.cursor().path(COUNTERS)).get().values()) published+=count.longValue();
		if (published!=total) throw new IllegalStateException("Host holds "+published+" adds, expected "+total);
	}
}
