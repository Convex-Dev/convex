package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.test.Samples;

public class BlobMapsTest {

	@Test
	public void testEmpty() throws InvalidDataException {
		BlobMap<ABlob, ACell> m = BlobMaps.empty();

		assertFalse(m.containsKey(Blob.EMPTY));
		assertFalse(m.containsKey(null));
		assertFalse(m.containsValue(RT.cvm(1L)));
		assertFalse(m.containsValue(null));

		assertEquals(0L, m.count());
		assertSame(m, m.dissoc(Blob.fromHex("cafe")));
		assertSame(m, m.dissoc(Blob.fromHex("")));

		// checks vs regular map
		assertFalse(m.equals(Maps.empty()));
		assertFalse(Maps.empty().equals(m));
		
		doBlobMapTests(m);
	}

	@Test
	public void testBadAssoc() throws InvalidDataException {
		BlobMap<ABlob, CVMLong> m =BlobMaps.create(InitTest.HERO, RT.cvm(1L));
		m=m.assoc(InitTest.VILLAIN, RT.cvm(2L));
		assertEquals(2L,m.count());

		assertNull(m.assoc(null, null));
	}


	@Test
	public void testAssoc() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Blob k3 = Blob.fromHex("ccca");
		BlobMap<ABlob, CVMLong> m = BlobMaps.create(k1, RT.cvm(17L));

		doBlobMapTests(m);

		assertTrue(m.containsKey(k1));
		assertTrue(m.containsValue(RT.cvm(17L)));
		assertFalse(m.containsKey(k2));
		assertFalse(m.containsKey(Blob.EMPTY));
		assertFalse(m.containsKey(null));

		// add second entry
		m = m.assoc(k2, RT.cvm(23L));
		assertEquals(2L, m.count());
		MapEntry<ABlob, CVMLong> e2 = m.entryAt(1);
		assertSame(k2, e2.getKey());
		assertEquals(RT.cvm(23L), e2.getValue());

		doBlobMapTests(m);

		// add third entry
		m = m.assoc(k3, RT.cvm(34L));
		assertNotNull(m.toString());
		assertEquals(3L, m.count());
		MapEntry<ABlob, CVMLong> e3 = m.entryAt(2);
		assertEquals(e3, m.getEntry(k3));
		assertEquals(RT.cvm(34L), e3.getValue());

		doBlobMapTests(m);

		assertEquals(Vectors.of(17L,23L,34L),m.values());
	}

	@Test
	public void testGet() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		BlobMap<ABlob, CVMLong> m = BlobMaps.of(k1, 17L);
		assertNull(m.get(Samples.MAX_EMBEDDED_STRING)); // needs a blob. String counts as non-existent key
		assertCVMEquals(17L,m.get(k1));

		assertNull(m.get((Object)null)); // Null counts as non-existent key when used as an Object arg
	}


	@Test
	public void testBlobMapConstruction() throws InvalidDataException {
		BlobMap<ABlob, CVMLong> m = BlobMaps.empty();
		for (int i = 0; i < 100; i++) {
			Long l = (long) Integer.hashCode(i);
			CVMLong cl = RT.cvm(l);
			LongBlob lb = LongBlob.create(l);
			m = m.assoc(lb, cl);
			assertEquals(cl, m.get(lb));
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
		assertSame(BlobMaps.empty(), m);
	}

	@Test
	public void testBlobMapRandomConstruction() throws InvalidDataException {
		BlobMap<ABlob, CVMLong> m = BlobMaps.empty();
		for (int i = 0; i < 100; i++) {
			Long l = (Long.MAX_VALUE / 91 * i * 18);
			CVMLong cl=RT.cvm(l);
			LongBlob lb = LongBlob.create(l);
			m = m.assoc(lb, cl);
			assertEquals(cl, m.get(lb));
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
		assertSame(BlobMaps.empty(), m);
	}

	@Test
	public void testIdentity() {
		Blob bb = Blob.fromHex("000000000000cafe");
		LongBlob bl = LongBlob.create(0xcafe);
		Address ba=Address.create(0xcafe);
		assertNotEquals(BlobMap.create(bb, bl), BlobMap.create(ba,bl)); // different entry key types
		assertEquals(BlobMap.create(bb, bl), BlobMap.create(bl,bl)); // same entry key types
	}

	@Test
	public void testSingleEntry() throws InvalidDataException {
		Blob k = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		CVMLong val=RT.cvm(177777L);
		BlobMap<ABlob, CVMLong> m = BlobMaps.create(k, val);
		assertEquals(1L, m.count());

		assertEquals(val, m.get(k));

		assertNull(m.get(Blob.EMPTY));
		assertNull(m.get(k2));

		assertSame(BlobMaps.empty(), m.dissoc(k));
		assertSame(m, m.dissoc(k2)); // long key miss
		assertSame(m, m.dissoc(k.slice(0, 1))); // short prefix key miss
		assertSame(m, m.dissoc(Blob.fromHex("caef"))); // partial prefix key miss

		MapEntry<ABlob, CVMLong> me = m.entryAt(0);
		assertEquals(k, me.getKey());
		assertEquals(val, me.getValue());

		doBlobMapTests(m);
	}

	@Test
	public void testPrefixEntryTwo() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		BlobMap<Blob, CVMLong> m = BlobMaps.of(k1, 17L, k2, 23L);
		BlobMap<Blob, CVMLong> m1 = BlobMaps.of(k1, 17L);
		BlobMap<Blob, CVMLong> m2 = BlobMaps.of(k2, 23L);
		assertSame(m, m.dissoc(k1.slice(0, 1)));
		assertEquals(m1, m.dissoc(k2));
		assertEquals(m2, m.dissoc(k1));

		doBlobMapTests(m);
	}

	@Test
	public void testInitialPeersBlobMap() {
		BlobMap<AccountKey, PeerStatus> bm = InitTest.STATE.getPeers();
		doBlobMapTests(bm);

		BlobMap<AccountKey, PeerStatus> fm =bm.filterValues(ps -> ps==bm.get(InitTest.FIRST_PEER_KEY));
		assertEquals(1L,fm.count());
		
		bm.isCompletelyEncoded();
	}

	@Test
	public void testPrefixEntryThree() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Blob k3 = Blob.fromHex("cafefeed");
		BlobMap<Blob, CVMLong> m = BlobMaps.of(k1, 17L, k2, 23L, k3, 47L);
		m.validate();
		assertEquals(2L, m.dissoc(k1).count());

		assertSame(m, m.assocEntry(m.getEntry(k1)));
		assertEquals(m, m.assoc(k1, RT.cvm(17L)));
		assertNotEquals(m, m.assoc(k1,  RT.cvm(27L)));

		assertEquals(m, BlobMaps.of(k2, 23L, k3, 47L).assoc(k1,  RT.cvm(17L)));

		Blob k0 = Blob.fromHex("ca");
		BlobMap<Blob, CVMLong> m4 = m.assoc(k0,  RT.cvm(7L));
		m4.validate();
		BlobMap<Blob, CVMLong> m4b = BlobMaps.of(k0, 7L, k1, 17L, k2, 23L, k3, 47L);
		assertEquals(m4, m4b);
		doBlobMapTests(m4);

		doBlobMapTests(m);
	}

	@Test
	public void testDissocEntries() throws InvalidDataException {
		BlobMap<Blob, CVMLong> m = Samples.INT_BLOBMAP_7;
		long n=m.count();

		for (int i=0; i<n; i++) {
			MapEntry<Blob,CVMLong> me=m.entryAt(i);
			BlobMap<Blob, CVMLong> dm= (BlobMap<Blob, CVMLong>)m.dissoc(me.getKey());
			dm.validate();
			assertEquals(n-1,dm.count());
			BlobMap<Blob, CVMLong> m2=dm.assocEntry(me);
			assertEquals(m,m2);
		}
	}

	@Test
	public void testDissocAll() throws InvalidDataException {
		BlobMap<Blob, CVMLong> m=BlobMaps.empty();
		long n=100;

		for (long i=0; i<n; i++) {
			m=m.assoc(Address.create(Math.abs(i*546546565954464911L)), CVMLong.create(i));
		}

		assertEquals(n,m.count());

		for (long i=0; i<n; i++) {
			m=m.dissoc(Address.create(Math.abs(i*546546565954464911L)));
			m.validate();
		}
		assertSame(BlobMaps.empty(),m);
	}

	@Test
	public void testRemoveEntries() {
		BlobMap<Blob, CVMLong> m = Samples.INT_BLOBMAP_7;

		assertSame(m, m.removeLeadingEntries(0));
		assertSame(BlobMaps.empty(), m.removeLeadingEntries(7));
	}

	@Test
	public void testSmallIntBlobMap() {
		BlobMap<Blob, CVMLong> m = Samples.INT_BLOBMAP_7;

		for (int i = 0; i < 7; i++) {
			MapEntry<Blob, CVMLong> me = m.entryAt(i);
			assertEquals(i, me.getValue().longValue());
			assertEquals(me, m.getEntry(me.getKey()));
		}
		doBlobMapTests(m);
	}

	private <K extends ABlob, V extends ACell> void doBlobMapTests(BlobMap<K, V> m) {
		long n = m.count();

		if (n >= 2) {
			MapEntry<K, V> e1 = m.entryAt(0);
			MapEntry<K, V> e2 = m.entryAt(n - 1);
			assertTrue(e1.getKey().compareTo(e2.getKey()) < 0);
		}
		
		assertEquals(Types.BLOBMAP,m.getType());

		CollectionsTest.doMapTests(m);
	}
}
