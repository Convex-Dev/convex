package convex.core.examples;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.store.CacheStats;
import convex.etch.Etch;
import convex.etch.EtchStore;
import convex.etch.EtchUtils;

/**
 * Runnable example: a full online Etch GC cycle under concurrent load.
 *
 * Writers continuously update a shared lattice root (a map of worker keys to
 * payload values), superseding their previous payloads - the superseded data
 * is the garbage the cycle collects. Readers continuously verify store reads
 * against the in-memory model. The GC cycle (start / sweep / verify /
 * complete) runs mid-stream, including a deliberate window where workers keep
 * using the OLD store handle after cutover (the legacy-view handover).
 *
 * Runs for ~20 seconds and prints a trace of actions and statistics. The
 * final result is verified as correct (root equals the in-memory model, every
 * payload matches its deterministic recomputation) and complete (the new file
 * alone contains the entire root tree), and the sampled pre-cycle garbage is
 * checked to be absent. Exits non-zero on any verification failure.
 *
 * Run with:
 *   mvn -pl convex-core test-compile exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=convex.core.examples.EtchGCExample
 */
public class EtchGCExample {

	static final int WRITERS = 4;
	static final int READERS = 4;

	static volatile boolean stop = false;
	static volatile boolean gcStarted = false;

	/** Current store handle: workers re-read this each iteration */
	static final AtomicReference<EtchStore> currentStore = new AtomicReference<>();

	/** In-memory model of the lattice root: the source of truth for verification */
	static final AtomicReference<AMap<AString, ACell>> model = new AtomicReference<>(Maps.empty());

	/** Serialises root updates so model and store root advance atomically together */
	static final Object rootLock = new Object();

	static final AtomicLong payloadWrites = new AtomicLong();
	static final AtomicLong rootUpdates = new AtomicLong();
	static final AtomicLong reads = new AtomicLong();
	static final AtomicLong readChecks = new AtomicLong();

	/** First unexpected failure from any worker: aborts the run and is rethrown */
	static final AtomicReference<Throwable> firstError = new AtomicReference<>();

	/** Hashes of payloads superseded BEFORE the GC cycle: guaranteed garbage */
	static final ConcurrentLinkedQueue<Hash> earlyGarbage = new ConcurrentLinkedQueue<>();

	static long t0;

	static void log(String msg) {
		System.out.printf("[%6dms] %s%n", System.currentTimeMillis() - t0, msg);
	}

	/** Records a worker failure noisily and aborts the whole run */
	static void fail(Throwable e) {
		e.printStackTrace();
		firstError.compareAndSet(null, e);
		stop = true;
	}

	/** Throws noisily if a verification condition does not hold */
	static void check(boolean condition, String what) {
		log("VERIFY " + what + ": " + (condition ? "OK" : "FAILED"));
		if (!condition) throw new Error("Verification failed: " + what);
	}

	static AString workerKey(int w) {
		return Strings.create("worker-" + w);
	}

	/** Deterministic non-embedded payload: verifiable by recomputation */
	static AString payload(int w, long iter) {
		String base = "Payload for worker " + w + " iteration " + iter + ". ";
		return Strings.create(base.repeat(1 + (200 / base.length())));
	}

	static Thread writer(int w) {
		Thread t = new Thread(() -> {
			AString key = workerKey(w);
			long iter = 0;
			try {
				while (!stop) {
					iter++;
					EtchStore s = currentStore.get();

					// An ephemeral scratch value: persisted but never referenced
					// from the root, so guaranteed garbage
					AString scratch = payload(w, -iter);
					Cells.persist(scratch, s);
					payloadWrites.incrementAndGet();

					// Update the lattice root: replace this worker's entry
					AVector<ACell> val = Vectors.of(CVMLong.create(iter), payload(w, iter));
					ACell prevVal;
					synchronized (rootLock) {
						AMap<AString, ACell> m = model.get();
						prevVal = m.get(key);
						AMap<AString, ACell> m2 = m.assoc(key, val);
						s.setRootData(m2); // persists the tree and sets the root hash
						model.set(m2);
					}
					payloadWrites.incrementAndGet();
					rootUpdates.incrementAndGet();

					// Track pre-cycle superseded values: the garbage GC must collect
					if (!gcStarted) {
						if (prevVal != null) earlyGarbage.add(Cells.getHash(prevVal));
						earlyGarbage.add(scratch.getHash());
					}
					Thread.sleep(2);
				}
			} catch (Throwable e) {
				fail(new Error("Writer " + w + " failed unexpectedly", e));
			}
		}, "writer-" + w);
		return t;
	}

	static Thread reader(int r) {
		Thread t = new Thread(() -> {
			Random rand = new Random(1000 + r);
			try {
				while (!stop) {
					EtchStore s = currentStore.get();
					AMap<AString, ACell> m = model.get();
					if (m.count() > 0) {
						AString key = workerKey(rand.nextInt(WRITERS));
						ACell expected = m.get(key);

						// Read the captured root back through the store by hash:
						// exercises target-first reads and old-file fallback
						Ref<ACell> rootRef = s.refForHash(Cells.getHash(m));
						reads.incrementAndGet();
						if (rootRef == null)
							throw new Error("Persisted root not readable by hash: " + Cells.getHash(m));
						if (expected != null) {
							@SuppressWarnings("unchecked")
							ACell fromStore = ((AMap<AString, ACell>) rootRef.getValue()).get(key);
							readChecks.incrementAndGet();
							if (!expected.equals(fromStore))
								throw new Error("Read mismatch for " + key
										+ ": expected " + expected + " but store returned " + fromStore);
						}
					}
					Thread.sleep(1);
				}
			} catch (Throwable e) {
				fail(new Error("Reader " + r + " failed unexpectedly", e));
			}
		}, "reader-" + r);
		return t;
	}

	public static void main(String[] args) throws Exception {
		t0 = System.currentTimeMillis();
		File f = File.createTempFile("etch-gc-example", ".etch");
		f.deleteOnExit();
		EtchStore store = EtchStore.create(f);
		currentStore.set(store);
		log("Store created: " + f);

		List<Thread> threads = new ArrayList<>();
		for (int w = 0; w < WRITERS; w++) threads.add(writer(w));
		for (int r = 0; r < READERS; r++) threads.add(reader(r));
		threads.forEach(Thread::start);
		log(WRITERS + " writers + " + READERS + " readers running; lattice root updated continuously");

		// ----- Phase 1: normal operation, garbage accumulating -----
		Thread.sleep(5000);
		long preLen = store.getEtch().getDataLength();
		log(String.format("Before GC: old file %,d bytes | %,d payload writes | %,d root updates | %,d reads",
				preLen, payloadWrites.get(), rootUpdates.get(), reads.get()));

		// Snapshot guaranteed-garbage sample: stop collecting, let in-flight
		// additions land, then snapshot BEFORE the cycle starts - everything in
		// the sample was superseded strictly before startGC()
		gcStarted = true;
		Thread.sleep(100);
		List<Hash> garbageSample = new ArrayList<>(earlyGarbage);
		log("Garbage sample snapshot: " + garbageSample.size() + " superseded pre-cycle values");

		// ----- Phase 2: GC cycle with live traffic -----
		store.startGC();
		log("startGC(): writes now redirect to target " + store.getEtch().getFile().getName() + "~*");

		Thread.sleep(2000);
		log(String.format("Cycle running: %,d root updates so far; verifyGC reports %d missing (sweep not yet run)",
				rootUpdates.get(), store.verifyGC().size()));

		long sweepStart = System.currentTimeMillis();
		store.transferGC();
		log(String.format("transferGC() swept root tree in %,d ms", System.currentTimeMillis() - sweepStart));
		check(store.isGCComplete(), "sweep completed");
		check(store.verifyGC().isEmpty(), "target complete after sweep (verifyGC empty)");

		Thread.sleep(3000);
		log(String.format("After 3s more live traffic (%,d root updates):", rootUpdates.get()));
		check(store.isGCComplete(), "completeness sticky under live traffic");
		check(store.verifyGC().isEmpty(), "verifyGC still empty under live traffic");

		// ----- Phase 3: cutover with gradual handover -----
		EtchStore newStore = store.completeGC();
		newStore.getEtch().getFile().deleteOnExit();
		log("completeGC(): successor store on " + newStore.getFileName()
				+ " - workers still using the OLD handle (legacy view)");

		Thread.sleep(1000); // deliberate window: old-handle writes route to the successor's file
		currentStore.set(newStore);
		log("Handles swapped: workers now use the new store directly");

		Thread.sleep(5000);

		// ----- Shutdown workers; any worker failure aborts noisily -----
		stop = true;
		for (Thread t : threads) t.join();
		if (firstError.get() != null)
			throw new Error("Run aborted by worker failure", firstError.get());
		log(String.format("Workers stopped: %,d payload writes | %,d root updates | %,d reads (%,d verified)",
				payloadWrites.get(), rootUpdates.get(), reads.get(), readChecks.get()));

		// ----- Final verification: throws on the first failed condition -----

		// 1: the new file ALONE contains the complete root tree
		List<Hash> missing = EtchUtils.verify(newStore.getEtch(), newStore.getRootHash());
		check(missing.isEmpty(), "completeness - new file alone holds the root tree ("
				+ missing.size() + " missing)");

		// 2: the store root equals the in-memory model exactly
		AMap<AString, ACell> expected = model.get();
		check(expected.getHash().equals(newStore.getRootHash())
				&& expected.equals(newStore.getRootData()),
				"root equals in-memory model (" + expected.count() + " entries)");

		// 3: every entry's payload is readable from the new store BY HASH and
		// matches its deterministic recomputation. (The [iter, payload] vector
		// itself is embedded, so only the non-embedded payload has its own entry)
		for (int w = 0; w < WRITERS; w++) {
			@SuppressWarnings("unchecked")
			AVector<ACell> val = (AVector<ACell>) expected.get(workerKey(w));
			long iter = ((CVMLong) val.get(0)).longValue();
			AString pay = (AString) val.get(1);
			Ref<ACell> ref = newStore.refForHash(pay.getHash());
			check((ref != null) && pay.equals(ref.getValue())
					&& payload(w, iter).equals(pay),
					"payload integrity for worker " + w + " (iteration " + iter + ")");
		}

		// 4: pre-cycle garbage was actually collected
		int collected = 0;
		for (Hash h : garbageSample) {
			if (newStore.getEtch().read(h) == null) collected++;
		}
		check(collected == garbageSample.size(), String.format(
				"garbage collected - %,d/%,d sampled pre-cycle values absent from new file",
				collected, garbageSample.size()));

		// ----- Statistics -----
		// Note: the new file is LARGER than the old one here - it holds all
		// traffic persisted after startGC() (retention contract), which at this
		// write rate dwarfs the collected pre-cycle garbage
		long oldLen = store.getEtch().getDataLength();
		long busyLen = newStore.getEtch().getDataLength();
		log(String.format("SPACE: old file %,d bytes (frozen at startGC) | busy-cycle file %,d bytes (includes all post-startGC traffic)",
				oldLen, busyLen));
		log("CACHE old store: " + statsLine(store.getCacheStats())
				+ " | new store: " + statsLine(newStore.getCacheStats()));

		// ----- Second, quiescent GC cycle: the clean reclamation figure -----
		// With writers stopped, a cycle extracts exactly the live set
		newStore.startGC();
		newStore.transferGC();
		check(newStore.verifyGC().isEmpty(), "quiescent cycle complete after sweep");
		EtchStore finalStore = newStore.completeGC();
		finalStore.getEtch().getFile().deleteOnExit();
		long finalLen = finalStore.getEtch().getDataLength();
		log(String.format("SPACE: quiescent GC %,d bytes -> %,d bytes (%.2f%% reclaimed: live set only)",
				busyLen, finalLen, 100.0 * (busyLen - finalLen) / busyLen));

		check(EtchUtils.verify(finalStore.getEtch(), finalStore.getRootHash()).isEmpty(),
				"final store completeness (new file only)");
		check(expected.equals(finalStore.getRootData()),
				"final store root equals in-memory model");

		// 5: caller-decided retirement must not affect successors: close both
		// predecessors, the final store must keep serving
		store.close();
		newStore.close();
		check(finalStore.getRootData() != null
				&& expected.equals(finalStore.getRootData()),
				"final store still serving after predecessors closed");
		finalStore.close();

		log("RESULT: PASS - final state correct and complete");
		System.exit(0);
	}

	static String statsLine(CacheStats s) {
		return "l1Hits=" + s.l1Hits + " l2Hits=" + s.l2Hits + " decodes=" + s.decodes;
	}
}
