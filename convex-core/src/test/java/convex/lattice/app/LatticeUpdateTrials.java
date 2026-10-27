package convex.lattice.app;

import convex.core.data.ACell;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.impl.LongBlob;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.lattice.IndexComponent;
import convex.lattice.LatticeHost;
import convex.lattice.SingleRegion;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.IndexLattice;
import convex.lattice.generic.MaxLattice;

/**
 * Performance trial for local lattice updates through an in-memory {@link LatticeHost}.
 *
 * <p>The trial assembles its own application: a region of counters keyed by ID, merged
 * by keeping the larger count. It times:</p>
 * <ul>
 *   <li>live edits, each atomically replacing the host root;</li>
 *   <li>staged edits in a private draft, published to the host every batch;</li>
 *   <li>publication of a draft with no changes;</li>
 *   <li>two drafts editing disjoint counters and publishing alternately, so each
 *       publication merges the other's changes.</li>
 * </ul>
 *
 * <p>Each round repeats every trial on fresh hosts, so JIT warm-up shows in the early
 * rounds. Figures are indicative wall-clock times, not JMH measurements; raw cursor
 * costs are in {@code LatticeTransactionBenchmark}. Run with:</p>
 * <pre>
 * ./mvnw -q -pl convex-core test-compile exec:java -Dexec.mainClass=convex.lattice.app.LatticeUpdateTrials -Dexec.classpathScope=test
 * </pre>
 */
public final class LatticeUpdateTrials {
	private LatticeUpdateTrials() {}

	static final Keyword COUNTERS=Keyword.intern("counters");
	static final int ROUNDS=3;
	static final int EDITS=100_000;
	static final int[] BATCHES={1,100,10_000};
	static final int SYNCS=100_000;

	/** Counters keyed by ID. Incoming values and competing drafts keep the larger count. */
	static final class Counters extends IndexComponent<LongBlob,AInteger> {
		private static final IndexLattice<LongBlob,AInteger> LATTICE=IndexLattice.create(MaxLattice.create());

		Counters(AForkableCursor<Index<LongBlob,AInteger>> cursor) { super(cursor); }

		@Override protected Counters rebind(AForkableCursor<Index<LongBlob,AInteger>> cursor) {
			return new Counters(cursor);
		}
		@Override public Counters fork() { return rebind(storage.fork()); }

		@Override public Index<LongBlob,AInteger> merge(Index<LongBlob,AInteger> incoming) {
			return storage.updateAndGet(own -> LATTICE.merge(own,incoming));
		}
		@Override protected Index<LongBlob,AInteger> mergePublication(Index<LongBlob,AInteger> own,
				Index<LongBlob,AInteger> other) {
			return LATTICE.merge(own,other);
		}
	}

	/** The trial application: one counters region at the host root. */
	static final class CounterApp extends SingleRegion<Counters> {
		private CounterApp(LatticeHost<Index<Keyword,ACell>> host,Counters counters) {
			super(host,COUNTERS,counters);
		}

		/** Edits go straight to the host root. */
		static CounterApp live(LatticeHost<Index<Keyword,ACell>> host) {
			return host.addApplication(new CounterApp(host,new Counters(host.cursor().path(COUNTERS))));
		}

		/** Edits stay in a private draft until {@link #sync()}. */
		static CounterApp staged(LatticeHost<Index<Keyword,ACell>> host) {
			return host.addApplication(new CounterApp(host,new Counters(host.cursor().path(COUNTERS)).fork()));
		}

		void set(long id,long count) {
			component().put(LongBlob.create(id),CVMLong.create(count));
		}
	}

	public static void main(String... args) {
		for (int round=1;round<=ROUNDS;round++) {
			System.out.println("Round "+round);
			liveEdits();
			for (int batch:BATCHES) stagedEdits(batch);
			unchangedPublication();
			for (int batch:BATCHES) competingDrafts(batch);
			System.out.println();
		}
	}

	/** Inserts grow the region; updates then rewrite every existing counter. */
	static void liveEdits() {
		var host=LatticeHost.create();
		var app=CounterApp.live(host);
		long start=System.nanoTime();
		for (int i=0;i<EDITS;i++) app.set(i,1);
		long inserted=System.nanoTime();
		for (int i=0;i<EDITS;i++) app.set(i,2);
		long updated=System.nanoTime();
		report("live insert",EDITS,inserted-start);
		report("live update",EDITS,updated-inserted);
		checkPublished(host,EDITS);
	}

	/** The host does not change between publications, so each one adopts the draft. */
	static void stagedEdits(int batch) {
		var host=LatticeHost.create();
		var app=CounterApp.staged(host);
		publishBatches("staged insert, batch "+batch,app,batch,1);
		publishBatches("staged update, batch "+batch,app,batch,2);
		checkPublished(host,EDITS);
	}

	/** Sets every counter to a count, publishing every batch. */
	private static void publishBatches(String label,CounterApp app,int batch,long count) {
		long editing=0,publishing=0;
		for (int i=0;i<EDITS;) {
			long start=System.nanoTime();
			for (int j=0;j<batch;j++) app.set(i++,count);
			long edited=System.nanoTime();
			app.sync();
			publishing+=System.nanoTime()-edited;
			editing+=edited-start;
		}
		report(label,EDITS,editing);
		report("  publish",EDITS/batch,publishing);
		report("  per edit, with publication",EDITS,editing+publishing);
	}

	/** A draft that matches its last publication has nothing to reconcile. */
	static void unchangedPublication() {
		var host=LatticeHost.create();
		var app=CounterApp.staged(host);
		for (int i=0;i<EDITS;i++) app.set(i,1);
		app.sync();
		long start=System.nanoTime();
		for (int i=0;i<SYNCS;i++) app.sync();
		report("unchanged publish",SYNCS,System.nanoTime()-start);
	}

	/** Each publication finds the other draft's last batch in the host and merges it. */
	static void competingDrafts(int batch) {
		var host=LatticeHost.create();
		var first=CounterApp.staged(host);
		var second=CounterApp.staged(host);
		long editing=0,publishing=0;
		for (int i=0;i<EDITS;) {
			long start=System.nanoTime();
			for (int j=0;j<batch;j++) {
				first.set(i++,1);
				second.set(i++,1);
			}
			long edited=System.nanoTime();
			first.sync();
			second.sync();
			publishing+=System.nanoTime()-edited;
			editing+=edited-start;
		}
		report("competing drafts, batch "+batch,EDITS,editing);
		report("  merging publish",EDITS/batch,publishing);
		report("  per edit, with publication",EDITS,editing+publishing);
		checkPublished(host,EDITS);
	}

	private static void report(String label,long ops,long nanos) {
		System.out.printf("%-32s %,9d ops %,9.1f ms %,9d ns/op %,12d ops/s%n",
			label,ops,nanos/1e6,nanos/ops,ops*1_000_000_000L/Math.max(1,nanos));
	}

	/** Guards against timing a trial that silently published nothing. */
	private static void checkPublished(LatticeHost<Index<Keyword,ACell>> host,long expected) {
		Index<?,?> counters=(Index<?,?>)host.cursor().get().get(COUNTERS);
		long count=(counters==null)?0:counters.count();
		if (count!=expected) throw new IllegalStateException("Host holds "+count+" counters, expected "+expected);
	}
}
