package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.CacheStats;
import convex.core.store.MemoryStore;
import convex.etch.Etch;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;

/**
 * Tests for the decode cache in ACachedStore, a fixed-size reference cache.
 *
 * <p>Strategy: decode cells, then decode them again. Cells still in the cache are
 * reused; cells evicted by collisions are decoded again, so the cache's size bounds
 * what it holds.</p>
 */
public class StoreCacheTest {

	/** Default cache size — see ACachedStore.refCache. */
	private static final int CACHE_SIZE = 10000;

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
	 * A cached cell is reused, and the cache keeps this store's view of it: another
	 * store's ref attached to the shared cell does not change the cache entry, and once
	 * evicted nothing here claims the cell is stored until it is persisted.
	 */
	@Test
	public void testCachedCellReusedUntilEvicted() throws IOException, BadFormatException {
		// One slot makes every eviction deterministic, including after persistence.
		for (boolean direct:new boolean[] {false, true}) {
			try (EtchStore store=EtchStore.createTemp(EtchConfig.create().withRefCacheSize(1));
					AStore other=direct ? new MemoryStore() : EtchStore.createTemp()) {
				Blob target=nonEmbedded(new Random(0xABCDL));
				Blob evict=nonEmbedded(new Random(0xBEEFL)).getEncoding();
				ACell first=store.decode(target.getEncoding());
				Ref<?> original=store.checkCache(target.getHash());
				assertTrue(original.isDirect());
				assertEquals(Ref.UNKNOWN, original.getStatus());
				assertSame(first.getRef(), original, "Fresh decodes should reuse their direct ref");

				// Attaching another store's higher-status ref changes cell.getRef(),
				// but must not change the meaning of this store's cache entry.
				Ref<?> foreign=other.storeTopRef(first.getRef(), Ref.ANNOUNCED, null);
				first.attachRef(foreign); // MemoryStore returns a direct ref without attaching it
				assertSame(foreign, first.getRef());
				assertSame(original, store.checkCache(target.getHash()));
				assertSame(first, store.decode(target.getEncoding()), "The cache should reuse the cell");

				// Another cell takes the only slot: the evicted cell is no longer cached
				store.decode(evict);
				assertNull(store.checkCache(target.getHash()));
				assertSame(foreign, first.getRef(), "Eviction must not mutate the shared cell");
				assertNull(store.getEtch().read(target.getHash()));
				Ref<?> persisted=store.storeTopRef(first.getRef(), Ref.PERSISTED, null);
				assertSame(store, ((RefSoft<?>) persisted).getStore());
				assertEquals(Ref.PERSISTED, persisted.getStatus());
				assertNotNull(store.getEtch().read(target.getHash()), "Persistence must actually write here");
			}
		}
	}

	/**
	 * Re-reading a working set larger than the cache must decode most cells again:
	 * the cache holds only what fits its slots.
	 */
	@Test
	public void testWorkingSetLargerThanCacheDecodesAgain() throws IOException, BadFormatException {
		EtchStore store = new EtchStore(Etch.createTempEtch());

		int n = CACHE_SIZE * 4;
		List<Blob> cells = generate(n, 0xDEADL);

		decodeAll(store, cells);
		store.resetCacheStats();
		decodeAll(store, cells);
		CacheStats pass2 = store.getCacheStats();

		assertEquals(n, pass2.total());
		// Working set 4× the cache, so the vast majority must re-decode.
		assertTrue(pass2.decodes > n / 2,
				"expected many re-decodes with a working set larger than the cache, got " + pass2);
		// The cache size must survive GC cutover.
		store.startGC();
		store.transferGC();
		try (EtchStore successor=store.completeGC()) {
			assertEquals(store.getConfig(),successor.getConfig());
			assertEquals(CACHE_SIZE,successor.getRefCacheSize());
		}
		store.close();
	}

	/**
	 * Fast-path sanity: a working set well within the cache is served from it on repeat.
	 */
	@Test
	public void testWorkingSetWithinCacheHits() throws IOException, BadFormatException {
		EtchStore store = new EtchStore(Etch.createTempEtch());
		// Small working set so slot collisions are rare. (RefCache maps each hash to
		// one slot, so even modest n can collide — hence "vast majority", not equality.)
		int n = 50;
		List<Blob> cells = generate(n, 0xFEEDL);
		decodeAll(store, cells);

		store.resetCacheStats();
		decodeAll(store, cells);
		CacheStats pass2 = store.getCacheStats();

		assertEquals(n, pass2.total());
		assertTrue(pass2.l1Hits >= n - 5, "vast majority should hit the cache: " + pass2);
		store.close();
	}
}
