package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.Init;
import convex.core.exceptions.InvalidDataException;
import convex.test.Samples;

public class BlobMapsTest {
	@Test
	public void testEmpty() throws InvalidDataException {
		BlobMap<ABlob, Long> m = BlobMaps.empty();
		
		assertFalse(m.containsKey(Blob.EMPTY));
		assertFalse(m.containsKey(null));
		assertFalse(m.containsValue(1L));
		assertFalse(m.containsValue(null));

		assertEquals(0L, m.count());
		assertSame(m, m.dissoc(Blob.fromHex("cafe")));
		assertSame(m, m.dissoc(Blob.fromHex("")));

		doBlobMapTests(m);
	}

	@Test
	public void testAssoc() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Blob k3 = Blob.fromHex("ccca");
		BlobMap<ABlob, Long> m = BlobMaps.create(k1, 17L);

		doBlobMapTests(m);
		
		assertTrue(m.containsKey(k1));
		assertTrue(m.containsValue(17L));
		assertFalse(m.containsKey(k2));
		assertFalse(m.containsKey(Blob.EMPTY));
		assertFalse(m.containsKey(null));

		// add second entry
		m = m.assoc(k2, 23L);
		assertEquals(2L, m.count());
		MapEntry<ABlob, Long> e2 = m.entryAt(1);
		assertSame(k2, e2.getKey());
		assertEquals(23L, (long) e2.getValue());

		doBlobMapTests(m);

		// add third entry
		m = m.assoc(k3, 34L);
		assertNotNull(m.toString());
		assertEquals(3L, m.count());
		MapEntry<ABlob, Long> e3 = m.entryAt(2);
		assertEquals(e3, m.getEntry(k3));
		assertEquals(34L, (long) e3.getValue());

		doBlobMapTests(m);
		
		assertEquals(Vectors.of(17L,23L,34L),m.values());
	}
	
	@SuppressWarnings("unlikely-arg-type")
	@Test
	public void testGet() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		BlobMap<ABlob, Long> m = BlobMaps.create(k1, 17L);
		assertNull(m.get("cafe")); // needs a blob. String counts as non-existent key
		assertEquals(17L,m.get(k1));
		
		assertNull(m.get((Object)null)); // Null counts as non-existent key when used as an Object arg

	}


	@Test
	public void testBlobMapConstruction() throws InvalidDataException {
		BlobMap<ABlob, Long> m = BlobMap.create();
		for (int i = 0; i < 100; i++) {
			Long l = (long) Integer.hashCode(i);
			LongBlob lb = LongBlob.create(l);
			m = m.assoc(lb, l);
			assertEquals(l, m.get(lb));
		}
		assertEquals(100L, m.count());
		m.validate();

		doBlobMapTests(m);

		for (int i = 0; i < 100; i++) {
			Long l = (long) Integer.hashCode(i);
			LongBlob lb = LongBlob.create(l);
			m = m.dissoc(lb);
			assertFalse(m.containsKey(lb), "Index: " + lb.toHexString());
		}
		assertSame(BlobMap.create(), m);
	}

	@Test
	public void testBlobMapRandomConstruction() throws InvalidDataException {
		BlobMap<ABlob, Long> m = BlobMap.create();
		for (int i = 0; i < 100; i++) {
			Long l = (Long.MAX_VALUE / 91 * i * 18);
			LongBlob lb = LongBlob.create(l);
			m = m.assoc(lb, l);
			assertEquals(l, m.get(lb));
		}
		assertEquals(100L, m.count());
		m.validate();

		doBlobMapTests(m);

		for (int i = 0; i < 100; i++) {
			Long l = (Long.MAX_VALUE / 91 * i * 18);
			LongBlob lb = LongBlob.create(l);
			m = m.dissoc(lb);
			assertFalse(m.containsKey(lb), "Index: " + lb.toHexString());
		}
		assertSame(BlobMap.create(), m);
	}

	@Test
	public void testSingleEntry() throws InvalidDataException {
		Blob k = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		BlobMap<ABlob, Long> m = BlobMaps.create(k, 17L);
		assertEquals(1L, m.count());

		assertEquals(17L, m.get(k));

		assertNull(m.get(Blob.EMPTY));
		assertNull(m.get(k2));

		assertSame(BlobMaps.empty(), m.dissoc(k));
		assertSame(m, m.dissoc(k2)); // long key miss
		assertSame(m, m.dissoc(k.slice(0, 1))); // short prefix key miss
		assertSame(m, m.dissoc(Blob.fromHex("caef"))); // partial prefix key miss

		MapEntry<ABlob, Long> me = m.entryAt(0);
		assertEquals(k, me.getKey());
		assertEquals(17L, (long) me.getValue());

		doBlobMapTests(m);
	}

	@Test
	public void testPrefixEntryTwo() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		BlobMap<Blob, Long> m = BlobMaps.of(k1, 17L, k2, 23L);
		BlobMap<Blob, Long> m1 = BlobMaps.of(k1, 17L);
		BlobMap<Blob, Long> m2 = BlobMaps.of(k2, 23L);
		assertSame(m, m.dissoc(k1.slice(0, 1)));
		assertEquals(m1, m.dissoc(k2));
		assertEquals(m2, m.dissoc(k1));

		doBlobMapTests(m);
	}

	@Test
	public void testInitialPeersBlobMap() {
		BlobMap<AccountKey, PeerStatus> bm = Init.STATE.getPeers();
		doBlobMapTests(bm);
	}

	@Test
	public void testPrefixEntryThree() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Blob k3 = Blob.fromHex("cafefeed");
		BlobMap<Blob, Long> m = BlobMaps.of(k1, 17L, k2, 23L, k3, 47L);
		m.validate();
		assertEquals(2L, m.dissoc(k1).count());

		assertSame(m, m.assocEntry(m.getEntry(k1)));
		assertEquals(m, m.assoc(k1, 17L));
		assertNotEquals(m, m.assoc(k1, 27L));

		assertEquals(m, BlobMaps.of(k2, 23L, k3, 47L).assoc(k1, 17L));

		Blob k0 = Blob.fromHex("ca");
		BlobMap<Blob, Long> m4 = m.assoc(k0, 7L);
		m4.validate();
		BlobMap<Blob, Long> m4b = BlobMaps.of(k0, 7L, k1, 17L, k2, 23L, k3, 47L);
		assertEquals(m4, m4b);
		doBlobMapTests(m4);

		doBlobMapTests(m);
	}

	@Test
	public void testRemoveEntries() {
		BlobMap<Blob, Long> m = Samples.INT_BLOBMAP_7;

		assertSame(m, m.removeLeadingEntries(0));
		assertSame(BlobMaps.empty(), m.removeLeadingEntries(7));
	}

	@Test
	public void testSmallIntBlobMap() {
		BlobMap<Blob, Long> m = Samples.INT_BLOBMAP_7;

		for (int i = 0; i < 7; i++) {
			MapEntry<Blob, Long> me = m.entryAt(i);
			assertEquals(i, me.getValue());
			assertEquals(me, m.getEntry(me.getKey()));
		}
		doBlobMapTests(m);
	}

	private <K extends ABlob, V> void doBlobMapTests(BlobMap<K, V> m) {
		long n = m.count();

		if (n >= 2) {
			MapEntry<K, V> e1 = m.entryAt(0);
			MapEntry<K, V> e2 = m.entryAt(n - 1);
			assertTrue(e1.getKey().compareTo(e2.getKey()) < 0);
		}

		CollectionsTest.doMapTests(m);
	}
}
