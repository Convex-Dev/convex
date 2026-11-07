package convex.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.Vectors;
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
	 * A cell read from the file enters the cache only as the store's own ref: reading the
	 * file directly, as GC does, leaves the cache as it was, and a store read after a miss
	 * counts one decode and caches the ref it returns.
	 */
	@Test
	public void testFileReadCachesOnlyStoreRef() throws IOException, BadFormatException {
		try (EtchStore store=EtchStore.createTemp(EtchConfig.create().withRefCacheSize(1))) {
			Blob value=nonEmbedded(new Random(0xF11EL));
			Blob evict=nonEmbedded(new Random(0xE71CL)).getEncoding();
			store.storeTopRef(value.getRef(), Ref.STORED, null);
			store.decode(evict); // takes the only slot
			assertNull(store.checkCache(value.getHash()));

			RefSoft<ACell> direct=store.getEtch().read(value.getHash());
			assertEquals(value, direct.getValue());
			assertNull(store.checkCache(value.getHash()), "A direct file read must not fill the cache");

			store.resetCacheStats();
			Ref<ACell> ref=store.refForHash(value.getHash());
			assertEquals(value, ref.getValue());
			assertSame(store, ((RefSoft<?>) ref).getStore());
			assertTrue(ref.getStatus()>=Ref.STORED);
			assertSame(ref, store.checkCache(value.getHash()));
			CacheStats stats=store.getCacheStats();
			assertEquals(1, stats.decodes, "A store read after a miss is one decode: "+stats);
			assertEquals(1, stats.l1Hits, "Only the check above hits: "+stats);
		}
	}

	/**
	 * A child cell sent in a message is reused from the cache instead of decoded again.
	 * One that misses is decoded with its own encoding, not a view of the message.
	 */
	@Test
	public void testMessageChildReusesCachedCell() throws IOException, BadFormatException {
		try (EtchStore store=EtchStore.createTemp()) {
			Blob child=nonEmbedded(new Random(0x5EEDL));
			Blob message=Format.encodeMultiCell(Vectors.of(child), true);

			AVector<ACell> fresh=store.decodeMultiCell(message);
			ACell decoded=fresh.get(0);
			assertEquals(child, decoded);
			assertEquals(child.getHash(), decoded.getHash());
			assertNotSame(message.getInternalArray(), decoded.getEncoding().getInternalArray(),
					"A decoded child must not keep the whole message in memory");

			ACell cached=store.decode(child.getEncoding());
			AVector<ACell> again=store.decodeMultiCell(message);
			assertSame(cached, again.get(0), "The cached cell should replace the message's copy");
		}
	}

	/**
	 * A branch reference to a cached cell takes the cache's ref. An unstored cell stays
	 * with the decoded parent after the cache evicts it; a stored cell comes back as the
	 * store's own ref.
	 */
	@Test
	public void testBranchRefReusesCachedRef() throws IOException, BadFormatException {
		// One slot makes eviction deterministic
		try (EtchStore store=EtchStore.createTemp(EtchConfig.create().withRefCacheSize(1))) {
			Blob child=nonEmbedded(new Random(0xC0DEL));
			Blob parent=Vectors.of(child).getEncoding(); // the branch alone, without the child
			ACell cached=store.decode(child.getEncoding());

			AVector<ACell> decoded=store.decodeMultiCell(parent);
			assertSame(cached.getRef(), decoded.getElementRef(0));

			store.decode(nonEmbedded(new Random(0xBEEFL)).getEncoding());
			assertNull(store.checkCache(child.getHash()));
			assertSame(cached, decoded.get(0), "The parent should keep the unstored cell");

			Ref<ACell> stored=store.storeTopRef(cached.getRef(), Ref.PERSISTED, null);
			AVector<ACell> again=store.decodeMultiCell(parent);
			assertSame(stored, again.getElementRef(0));
		}
	}

	/** A message child that is embedded is rejected even when the cache holds that value. */
	@Test
	public void testEmbeddedChildRejectedWhenCached() throws IOException {
		try (EtchStore store=EtchStore.createTemp()) {
			// A fresh embedded value: storing a shared instance would change it JVM-wide
			Blob small=Blob.fromHex("0101");
			store.storeTopRef(small.getRef(), Ref.PERSISTED, null);
			assertNotNull(store.checkCache(small.getHash()));

			Blob child=small.getEncoding();
			Blob message=concat(Vectors.of(1).getEncoding(), Blob.wrap(new byte[] {(byte) child.count()}), child);
			assertThrows(BadFormatException.class, ()->store.decodeMultiCell(message));
		}
	}

	private static Blob concat(Blob... parts) {
		int length=0;
		for (Blob b:parts) length+=(int) b.count();
		byte[] bytes=new byte[length];
		int pos=0;
		for (Blob b:parts) pos=b.getBytes(bytes, pos);
		return Blob.wrap(bytes);
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
