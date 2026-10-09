package convex.benchmarks;

import java.io.IOException;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.etch.EtchStore;

/** L1 hits for decoded direct refs and persisted soft refs. Run with -prof gc. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3, time=1)
@Measurement(iterations=5, time=1)
@Fork(2)
public class EtchCacheBenchmark {

	@Param({"direct", "soft"})
	public String reference;

	@Param({"32"})
	public int workingSet;

	private EtchStore store;
	private Blob[] encodings;
	private Hash[] hashes;
	// Keep values alive so the soft-ref case measures in-memory hits.
	private ACell[] values;
	private int cursor;

	@Setup
	public void setup() throws IOException, BadFormatException {
		if ((workingSet<=0)||(Integer.bitCount(workingSet)!=1)) {
			throw new IllegalArgumentException("Use a positive power-of-two working set");
		}
		store=EtchStore.createTemp();
		encodings=new Blob[workingSet];
		hashes=new Hash[workingSet];
		values=new ACell[workingSet];
		Random random=new Random(1234);
		for (int i=0;i<workingSet;i++) {
			encodings[i]=Blob.createRandom(random, Format.MAX_EMBEDDED_LENGTH+1).getEncoding();
			hashes[i]=encodings[i].getContentHash();
			values[i]=store.decode(encodings[i]);
			if ("soft".equals(reference)) store.storeTopRef(values[i].getRef(), Ref.PERSISTED, null);
		}
		store.resetCacheStats();
		for (Hash hash:hashes) store.checkCache(hash);
		if (store.getCacheStats().l1Hits!=workingSet) throw new IllegalStateException("Working set has L1 collisions");
	}

	@Benchmark
	public Ref<ACell> cachedRef() {
		return store.checkCache(hashes[cursor++ & (workingSet-1)]);
	}

	@Benchmark
	public ACell decodeHit() throws BadFormatException {
		return store.decode(encodings[cursor++ & (workingSet-1)]);
	}

	@TearDown
	public void tearDown() throws IOException {
		java.lang.ref.Reference.reachabilityFence(values);
		store.close();
	}
}
