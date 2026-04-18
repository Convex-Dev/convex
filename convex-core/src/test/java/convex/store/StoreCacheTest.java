package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
import convex.core.store.CacheStats;
import convex.etch.Etch;
import convex.etch.EtchStore;

/**
 * Tests for the two-tier decode cache in ACachedStore (L1 RefCache + L2 SoftCache).
 *
 * <p>Strategy: generate cells that exceed the L1 capacity, read them, then re-read.
 * Without an L2 cache, the second pass re-decodes because L1 collision-eviction drops
 * earlier entries. With L2 enabled, the second pass is served from L2.</p>
 */
public class StoreCacheTest {

	/** L1 fixed size — see ACachedStore.refCache. */
	private static final int L1_SIZE = 10000;

	/** Non-embedded payload so decode()/getEncoding() round-trips through the store. */
	private static Blob nonEmbedded(Random r) {
		return Blob.createRandom(r, Format.MAX_EMBEDDED_LENGTH + 1);
	}

	private static List<Blob> generate(int n, long seed) {
		Random r = new Random(seed);
		List<Blob> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(nonEmbedded(r));
		return out;
	}

	private static void decodeAll(EtchStore store, List<Blob> cells) throws BadFormatException {
		for (Blob b : cells) store.decode(b.getEncoding());
	}

	/**
	 * L2 catches L1 collision-evictions. Working set is 4× L1: many entries thrash out of
	 * L1 on the first pass, so a second pass with no L2 would re-decode most of them.
	 * With L2 the second pass should be served almost entirely by L2.
	 */
	@Test
	public void testL2CatchesL1Eviction() throws IOException, BadFormatException {
		EtchStore store = new EtchStore(Etch.createTempEtch(), true);
		int n = L1_SIZE * 4;
		List<Blob> cells = generate(n, 0xC0FFEEL);

		// Pin cells in memory so L2 SoftReferences are not cleared during the test.
		List<ACell> pinned = new ArrayList<>(n);
		for (Blob b : cells) pinned.add(store.decode(b.getEncoding()));

		store.resetCacheStats();
		decodeAll(store, cells);
		CacheStats pass2 = store.getCacheStats();

		assertEquals(n, pass2.total());
		assertTrue(pass2.l2Hits > n / 2,
				"expected majority of pass-2 reads to hit L2, got " + pass2);
		assertTrue(pass2.decodes < n / 10,
				"expected few re-decodes with L2 enabled, got " + pass2);
		pinned.size(); // retain reference until here
		store.close();
	}

	/**
	 * Same-instance guarantee: a cell decoded once, then re-decoded after L1 eviction,
	 * should be the same Java instance (shared via L2). This is the direct proof that
	 * "multiple copies of the same value" has been eliminated for cached cells.
	 */
	@Test
	public void testSharedInstanceAcrossEviction() throws IOException, BadFormatException {
		EtchStore store = new EtchStore(Etch.createTempEtch(), true);
		Blob target = nonEmbedded(new Random(0xABCDL));

		ACell first = store.decode(target.getEncoding());

		// Thrash L1 with unrelated decodes. 2× L1_SIZE distinct cells guarantees the
		// target's slot is overwritten with very high probability.
		List<Blob> thrash = generate(L1_SIZE * 2, 0xBEEFL);
		List<ACell> pinned = new ArrayList<>(thrash.size());
		for (Blob b : thrash) pinned.add(store.decode(b.getEncoding()));

		ACell second = store.decode(target.getEncoding());
		assertSame(first, second, "L2 should return same instance after L1 eviction");

		pinned.size();
		store.close();
	}

	/**
	 * With L2 disabled, re-reading a working set larger than L1 must trigger large numbers
	 * of re-decodes on the second pass. This is the negative control for the first test —
	 * it proves the L2 is what's doing the work, not some other effect.
	 */
	@Test
	public void testL2DisabledForcesRedecode() throws IOException, BadFormatException {
		EtchStore store = new EtchStore(Etch.createTempEtch(), false);
		assertFalse(store.isL2Enabled());

		int n = L1_SIZE * 4;
		List<Blob> cells = generate(n, 0xDEADL);

		decodeAll(store, cells);
		store.resetCacheStats();
		decodeAll(store, cells);
		CacheStats pass2 = store.getCacheStats();

		assertEquals(n, pass2.total());
		assertEquals(0, pass2.l2Hits, "L2 should be disabled");
		// With L2 off and working set 4× L1, the vast majority must re-decode.
		assertTrue(pass2.decodes > n / 2,
				"expected many re-decodes with L2 disabled, got " + pass2);
		store.close();
	}

	/**
	 * Fast-path sanity: a working set that fits in L1 must be served entirely from L1 on
	 * repeat. L2 must not be consulted when L1 hits — this protects hot-path latency.
	 */
	@Test
	public void testL2NotConsultedWhenL1Hits() throws IOException, BadFormatException {
		EtchStore store = new EtchStore(Etch.createTempEtch(), true);
		// Small working set so single-slot RefCache collisions are negligible.
		// (RefCache uses single-slot linear hashing, so even modest n gets some collisions —
		// hence the "vast majority" assertion rather than equality.)
		int n = 50;
		List<Blob> cells = generate(n, 0xFEEDL);

		List<ACell> pinned = new ArrayList<>(n);
		for (Blob b : cells) pinned.add(store.decode(b.getEncoding()));

		store.resetCacheStats();
		decodeAll(store, cells);
		CacheStats pass2 = store.getCacheStats();

		assertEquals(0, pass2.decodes, "no re-decodes expected: " + pass2);
		assertTrue(pass2.l1Hits >= n - 5, "vast majority should hit L1: " + pass2);
		pinned.size();
		store.close();
	}
}
