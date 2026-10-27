package convex.lattice.app;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeHost;
import convex.lattice.cursor.AForkableCursor;

/**
 * Performance trial for atomic adds to named counters in N shards, each published to a
 * {@link LatticeHost} once a second.
 *
 * <p>The host starts with every shard in place under {@code :counters}. Each shard has its
 * own counters component with a private draft, and its own thread, which adds to the draft
 * and publishes it once a second. Threads share no working state; publications only
 * contend for the host root. Each draft is its shard's only writer, so publication never
 * needs to merge. The run checks that every add reached the host. Run with, optionally,
 * the length of the run in seconds and the number of shards:</p>
 * <pre>
 * ./mvnw -q -pl convex-core test-compile exec:java -Dexec.mainClass=convex.lattice.app.LatticeUpdateTrials -Dexec.classpathScope=test -Dexec.args="10 8"
 * </pre>
 */
public final class LatticeUpdateTrials {
	private LatticeUpdateTrials() {}

	static final Keyword COUNTERS=Keyword.intern("counters");
	static final int SECONDS=10;
	static final int SHARDS=8;
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

	public static void main(String... args) throws InterruptedException {
		int seconds=(args.length<1)?SECONDS:Integer.parseInt(args[0]);
		int shardCount=(args.length<2)?SHARDS:Integer.parseInt(args[1]);

		var shards=new Keyword[shardCount];
		Index<Keyword,ACell> emptyShards=Index.none();
		for (int i=0;i<shardCount;i++) {
			shards[i]=Keyword.intern("shard-"+i);
			emptyShards=emptyShards.assoc(shards[i],Index.none());
		}
		var host=LatticeHost.<Index<Keyword,ACell>>create(Index.of(COUNTERS,emptyShards),null);

		long[][] adds=new long[shardCount][seconds];
		long[][] publishes=new long[shardCount][seconds];
		var workers=new Thread[shardCount];
		long start=System.nanoTime();
		for (int t=0;t<shardCount;t++) {
			var draft=new Counters(host.cursor().path(COUNTERS,shards[t])).fork();
			long[] added=adds[t],published=publishes[t];
			workers[t]=Thread.ofPlatform().start(() -> {
				long next=start;
				for (int second=0;second<seconds;second++) {
					next+=1_000_000_000L;
					while (System.nanoTime()<next) {
						for (AString name:NAMES) draft.add(name,1);
						added[second]+=NAMES.length;
					}
					long publishing=System.nanoTime();
					draft.sync();
					published[second]=System.nanoTime()-publishing;
				}
			});
		}
		for (Thread worker:workers) worker.join();

		System.out.println(shardCount+" shards, one thread each");
		long total=0;
		for (int second=0;second<seconds;second++) {
			long added=0,slowest=0;
			for (int t=0;t<shardCount;t++) {
				added+=adds[t][second];
				slowest=Math.max(slowest,publishes[t][second]);
			}
			total+=added;
			System.out.printf("second %3d %,13d adds %,10d ns slowest publish%n",second+1,added,slowest);
		}
		System.out.printf("average    %,13d adds/s%n",total/seconds);

		long published=0;
		for (Keyword shard:shards) {
			for (CVMLong count:new Counters(host.cursor().path(COUNTERS,shard)).get().values()) published+=count.longValue();
		}
		if (published!=total) throw new IllegalStateException("Host holds "+published+" adds, expected "+total);
	}
}
