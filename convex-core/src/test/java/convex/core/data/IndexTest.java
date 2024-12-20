package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.Symbols;
import convex.core.data.impl.LongBlob;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.test.Samples;

public class IndexTest {

	@Test
	public void testEmpty() throws InvalidDataException {
		Index<ABlob, ACell> m = Index.none();

		assertFalse(m.containsKey(Blob.EMPTY));
		assertFalse(m.containsKey(null));
		assertFalse(m.containsValue(RT.cvm(1L)));
		assertFalse(m.containsValue(null));

		assertEquals(0L, m.count());
		assertEquals(0L, m.getDepth());
		assertSame(m, m.dissoc(Blob.fromHex("cafe")));
		assertSame(m, m.dissoc(Blob.fromHex("")));

		// checks vs regular map
		assertFalse(m.equals(Maps.empty()));
		assertFalse(Maps.empty().equals(m));
		
		doIndexTests(m);
	}

	@Test
	public void testBadAssoc() throws InvalidDataException {
		Index<Address, CVMLong> m =Index.create(InitTest.HERO, RT.cvm(1L));
		m=m.assoc(InitTest.VILLAIN, RT.cvm(2L));
		assertEquals(2L,m.count());

		assertNull(m.assoc(null, null));
	}

	@Test
	public void testAssoc() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Blob k3 = Blob.fromHex("ccca");
		Index<ABlob, CVMLong> m = Index.create(k1, RT.cvm(17L));

		doIndexTests(m);

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

		doIndexTests(m);

		// add third entry
		m = m.assoc(k3, RT.cvm(34L));
		assertNotNull(m.toString());
		assertEquals(3L, m.count());
		MapEntry<ABlob, CVMLong> e3 = m.entryAt(2);
		assertEquals(e3, m.getEntry(k3));
		assertEquals(RT.cvm(34L), e3.getValue());

		doIndexTests(m);

		assertEquals(Vectors.of(17L,23L,34L),m.values());
	}

	@Test
	public void testGet() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		ACell v1 = CVMLong.create(17);
		Index<ABlob, CVMLong> m = Index.of(k1, v1);
		assertNull(m.get(Samples.MAX_EMBEDDED_STRING)); // needs a blob. String counts as non-existent key
		assertCVMEquals(17L,m.get(k1));

		// Null counts as non-existent key when used as an Object arg
		assertNull(m.get((Object)null)); 
		assertNull(m.get((ACell)null)); 
	}


	@Test
	public void testIndexConstruction() throws InvalidDataException {
		Index<ABlob, CVMLong> m = Index.none();
		for (int i = 0; i < 100; i++) {
			Long l = (long) Integer.hashCode(i);
			CVMLong cl = RT.cvm(l);
			LongBlob lb = LongBlob.create(l);
			m = m.assoc(lb, cl);
			assertEquals(cl, m.get(lb));
		}
		assertEquals(100L, m.count());
		m.validate();

		doIndexTests(m);

		for (int i = 0; i < 100; i++) {
			Long l = (long) Integer.hashCode(i);
			LongBlob lb = LongBlob.create(l);
			m = m.dissoc(lb);
			assertFalse(m.containsKey(lb), "Index: " + lb.toHexString());
		}
		assertSame(Index.none(), m);
	}
	
	@Test public void testIndexEncode() throws BadFormatException {
		Index<ABlob, CVMLong> m = Index.of(Address.ZERO,Samples.IPSUM);
		
		Blob enc=m.getEncoding();
		assertEquals(m,Format.read(enc));
	}

	@Test
	public void testIndexRandomConstruction() throws InvalidDataException {
		Index<ABlob, CVMLong> m = Index.none();
		for (int i = 0; i < 100; i++) {
			Long l = (Long.MAX_VALUE / 91 * i * 18);
			CVMLong cl=RT.cvm(l);
			LongBlob lb = LongBlob.create(l);
			m = m.assoc(lb, cl);
			assertEquals(cl, m.get(lb));
		}
		assertEquals(100L, m.count());
		m.validate();

		doIndexTests(m);

		for (int i = 0; i < 100; i++) {
			Long l = (Long.MAX_VALUE / 91 * i * 18);
			LongBlob lb = LongBlob.create(l);
			m = m.dissoc(lb);
			assertFalse(m.containsKey(lb), "Index: " + lb.toHexString());
		}
		assertSame(Index.none(), m);
	}
	
	@Test
	public void testStringKeys() throws InvalidDataException {
		AString k=Samples.NON_EMBEDDED_STRING;
		Address v=Address.ZERO;
		Index<AString,Address> bm=Index.create(k,v);
		bm.validate();
		doIndexTests(bm);
		
		assertSame(Index.none(),bm.dissoc(k));
	}
	
	@Test public void testDissocCases() {
		// Dissocs on a split Index with no entry
		Index <Blob,CVMLong> m=Index.none();
		Blob k1=Blob.fromHex("65021337");
		Blob k2=Blob.fromHex("6502c001");
		Blob kiss=Blob.fromHex("6502");
		
		m=m.assoc(k1, CVMLong.ONE);
		m=m.assoc(k2, CVMLong.ZERO);
		
		assertEquals(m,m.dissoc(kiss));
		assertEquals(Index.of(k1,1),m.dissoc(k2));
		assertEquals(Index.of(k2,0),m.dissoc(k1));
	
		// Remove entry at split position
		Index<Blob, CVMLong> ms=m.assoc(kiss,CVMLong.MAX_VALUE);
		assertEquals(m,ms.dissoc(kiss));
		
		// remove branches below an entry
		assertEquals(Index.of(kiss,Long.MAX_VALUE),ms.dissoc(k1).dissoc(k2));
	}
	
	@Test
	public void testSymbolicKeys() throws InvalidDataException {
		Index<?,?> bm=Index.none();
		bm=bm.assoc(Symbols.FOO, CVMLong.ONE);
		bm=bm.assoc(Keywords.FOO, CVMLong.ZERO);
		
		// Equal symbolic name should overwrite
		assertEquals(Index.of(Keywords.FOO,CVMLong.ZERO),bm);
		
		// Should be regarded as different Index, even if keys collide and values are identical
		assertNotEquals(Index.of(Symbols.FOO,CVMLong.ZERO),bm);
		
		doIndexTests(bm);
	}
	
	@Test public void testCreate() {
		Index<ABlobLike<?>,ACell> bm=Index.of(Symbols.FOO, 2,Keywords.BAR,3);
		assertEquals(2,bm.count());
		
		// hashmap round trip
		HashMap<ABlobLike<?>,ACell> hm=new HashMap<>(bm);
		
		Index<ABlobLike<?>,ACell> rm=Index.create(hm);
		assertEquals(bm,rm);
		
		doIndexTests(rm);
	}
	
	@Test public void testContains() {
		Index<ABlob, CVMLong> bm=Samples.INT_INDEX_256;
		long n=bm.count;
		
		assertTrue(bm.containsKey(bm.entryAt(n/2).getKey()));
		assertFalse(bm.containsKey(LongBlob.create(1000)));
		
		assertFalse(bm.containsValue(LongBlob.create(1)));
		assertTrue(bm.containsValue(CVMLong.ONE));
	}

	@Test
	public void testIdentity() {
		Blob bb = Blob.fromHex("000000000000cafe");
		LongBlob bl = LongBlob.create(0xcafe);
		Address ba=Address.create(0xcafe);
		assertNotEquals(Index.create(bb, bl), Index.create(ba,bl)); // different entry key types
		assertEquals(Index.create(bb, bl), Index.create(bl,bl)); // same entry key types
	}
	
	@Test 
	public void testPrint() {
		assertEquals("#Index {}",Index.EMPTY.toString());
	}

	@Test
	public void testSingleEntry() throws InvalidDataException {
		Blob k = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		CVMLong val=RT.cvm(177777L);
		Index<ABlob, CVMLong> m = Index.create(k, val);
		assertEquals(1L, m.count());
		assertEquals(4, m.getDepth());

		assertEquals(val, m.get(k));

		assertNull(m.get(Blob.EMPTY));
		assertNull(m.get(k2));

		assertSame(Index.none(), m.dissoc(k));
		assertSame(m, m.dissoc(k2)); // long key miss
		assertSame(m, m.dissoc(k.slice(0, 1))); // short prefix key miss
		assertSame(m, m.dissoc(Blob.fromHex("caef"))); // partial prefix key miss

		MapEntry<ABlob, CVMLong> me = m.entryAt(0);
		assertEquals(k, me.getKey());
		assertEquals(val, me.getValue());

		doIndexTests(m);
	}

	@Test
	public void testPrefixEntryTwo() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Index<Blob, CVMLong> m = Index.of(k1, 17L, k2, 23L);
		Index<Blob, CVMLong> m1 = Index.of(k1, 17L);
		Index<Blob, CVMLong> m2 = Index.of(k2, 23L);
		assertSame(m, m.dissoc(k1.slice(0, 1)));
		assertEquals(m1, m.dissoc(k2));
		assertEquals(m2, m.dissoc(k1));

		doIndexTests(m);
	}

	@Test
	public void testInitialPeersIndex() {
		Index<AccountKey, PeerStatus> bm = InitTest.STATE.getPeers();
		doIndexTests(bm);

		Index<AccountKey, PeerStatus> fm =bm.filterValues(ps -> ps==bm.get(InitTest.FIRST_PEER_KEY));
		assertEquals(1L,fm.count());
		
		bm.isCompletelyEncoded();
	}

	@Test
	public void testPrefixEntryThree() throws InvalidDataException {
		Blob k1 = Blob.fromHex("cafe");
		Blob k2 = Blob.fromHex("cafebabe");
		Blob k3 = Blob.fromHex("cafefeed");
		Index<Blob, CVMLong> m = Index.of(k1, 17L, k2, 23L, k3, 47L);
		m.validate();
		assertEquals(2L, m.dissoc(k1).count());

		assertSame(m, m.assocEntry(m.getEntry(k1)));
		assertEquals(m, m.assoc(k1, RT.cvm(17L)));
		assertNotEquals(m, m.assoc(k1,  RT.cvm(27L)));

		assertEquals(m, Index.of(k2, 23L, k3, 47L).assoc(k1,  RT.cvm(17L)));

		Blob k0 = Blob.fromHex("ca");
		Index<Blob, CVMLong> m4 = m.assoc(k0,  RT.cvm(7L));
		m4.validate();
		Index<Blob, CVMLong> m4b = Index.of(k0, 7L, k1, 17L, k2, 23L, k3, 47L);
		assertEquals(m4, m4b);
		doIndexTests(m4);

		doIndexTests(m);
	}

	@Test
	public void testDissocEntries() throws InvalidDataException {
		Index<ABlobLike<?>, CVMLong> m = Samples.INT_INDEX_7;
		long n=m.count();

		for (int i=0; i<n; i++) {
			MapEntry<ABlobLike<?>,CVMLong> me=m.entryAt(i);
			Index<ABlobLike<?>, CVMLong> dm= (Index<ABlobLike<?>, CVMLong>)m.dissoc(me.getKey());
			dm.validate();
			assertEquals(n-1,dm.count());
			Index<ABlobLike<?>, CVMLong> m2=dm.assocEntry(me);
			assertEquals(m,m2);
		}
	}
	
	/**
	 * Test for some keys that exceed max effective key length
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test 
	public void testBigKeys() {
		Blob ks=Blob.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef");
		Blob k=Blob.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
		Blob k2=Blob.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef22");
		Blob k3=Blob.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef3333");
		
		Index m=Index.of(k, CVMLong.ONE);
		assertEquals(k.hexLength(),m.getDepth());
		
		assertNull(m.get(ks)); // short fetch
		assertEquals(CVMLong.ONE,m.get(k)); // exact full length match
		assertEquals(CVMLong.ONE,m.get(k2)); // matching up to max depth
		assertEquals(CVMLong.ONE,m.get(k3)); // matching up to max depth
		
		m=m.assoc(k2, CVMLong.ZERO);
		assertEquals(k.hexLength(),m.getDepth());
		
		assertEquals(CVMLong.ZERO,m.get(k2)); // should match up to max depth
		assertEquals(CVMLong.ZERO,m.get(k)); // should match up to max depth
		assertEquals(k2,m.getEntry(k3).getKey()); // should match up to max depth
		
		// Add and remove a short key
		assertNull(m.get(ks)); // short fetch
		m=m.assoc(ks, CVMDouble.ZERO);
		assertEquals(ks.hexLength(),m.getDepth());
		assertEquals(CVMDouble.ZERO,m.get(ks)); // short fetch now works		
		m=m.dissoc(ks);
		
		// dissoc should happen on keys equal up to max depth
		assertSame(m,m.dissoc(ks));
		assertSame(m.empty(),m.dissoc(k));
		assertSame(m.empty(),m.dissoc(k2));
		assertSame(m.empty(),m.dissoc(k3));
		
		doIndexTests(m);
		
		Index m2=Index.of(ks, 0,k,1,k2,2,k3,3);
		assertEquals(2,m2.count());
		assertEquals(ks.hexLength(),m2.getDepth());
		
		// Last colliding slice should be there
		assertEquals(m2.slice(1,2),Index.of(k3,3));
		
		doIndexTests(m2);
	}

	@Test
	public void testDissocAll() throws InvalidDataException {
		Index<Address, CVMLong> m=Index.none();
		long n=100;

		for (long i=0; i<n; i++) {
			m=m.assoc(Address.create(Math.abs(i*546546565954464911L)), CVMLong.create(i));
		}

		assertEquals(n,m.count());

		for (long i=0; i<n; i++) {
			m=m.dissoc(Address.create(Math.abs(i*546546565954464911L)));
			m.validate();
		}
		assertSame(Index.none(),m);
	}
	
	@Test
	public void testSliceSmallIndex() {
		Index<ABlobLike<?>, CVMLong> m=Samples.INT_INDEX_7;
		Index<ABlobLike<?>, CVMLong> ms=m.slice(3,4);
		assertEquals(1,ms.count());
		
		// Slice should be equal to a 1-entry Index with same key / value 
		MapEntry<ABlobLike<?>, CVMLong> me=m.entryAt(3);
		assertEquals(Index.create(me.getKey(), me.getValue()),ms);
		
		doIndexTests(ms);
		
		assertEquals(me,ms.entryAt(0));
		
		// Invalid slices
		assertNull(m.slice(-1));
		assertNull(m.slice(0,9));
		
		assertSame(m, m.slice(0));
		assertSame(Index.none(), m.slice(7));
	}

	@Test
	public void testSmallIntIndex() {
		Index<ABlobLike<?>, CVMLong> m = Samples.INT_INDEX_7;

		for (int i = 0; i < 7; i++) {
			MapEntry<ABlobLike<?>, CVMLong> me = m.entryAt(i);
			assertEquals(i, me.getValue().longValue());
			assertEquals(me, m.getEntry(me.getKey()));
		}
		doIndexTests(m);
	}

	private <K extends ABlobLike<?>, V extends ACell> void doIndexTests(Index<K, V> m) {
		long n = m.count();
		
		Index<K,V> secondHalf=m.slice(n/2,n);
		Index<K,V> firstHalf=m.slice(0,n/2);
		assertNotNull(secondHalf);
		assertEquals(m,firstHalf.merge(secondHalf));

		if (n >= 2) {
			MapEntry<K, V> e1 = m.entryAt(0);
			MapEntry<K, V> e2 = m.entryAt(n - 1);
			assertTrue(e1.getKey().compareTo(e2.getKey().toBlob()) < 0);
		}
		
		// TODO: should round trip when all child types do
		// assertEquals(m,Reader.read(RT.print(m).toString()));
		
		assertEquals(Types.INDEX,m.getType());

		CollectionsTest.doMapTests(m);
	}
}
