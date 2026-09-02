package convex.core.data;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
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
import convex.core.store.NullStore;
import convex.etch.EtchStore;
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
		assertEquals(m,Samples.TEST_STORE.decode(enc));
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
		
		final Index<ABlob, CVMLong>sm=m;
		assertEquals(m.getEncodingLength(),m.getEncoding().size());

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
	public void testMediumKeys() throws InvalidDataException {
		doIndexTests(Index.of(Blobs.createRandom(25),1)); // < MAX_DEPTH hex digits
		doIndexTests(Index.of(Blobs.createRandom(50),1)); // > MAX_DEPTH digits, < MAX_DEPTH bytes
		doIndexTests(Index.of(Blobs.createRandom(100),1)); // > MAX_DEPTH digits, > MAX_DEPTH bytes
		doIndexTests(Index.of(Blobs.createRandom(200),1)); // > MAX_DEPTH bytes, non-embedded
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
	
	/**
	 * entrySet() must preserve the Index's sorted key order (#651) — the class
	 * is documented as "a sorted radix-tree map... provide sorted orderings for
	 * indexes", and callers commonly iterate entrySet() (e.g. `for (var e :
	 * index.entrySet())`) expecting that guarantee to hold, not just entryAt(i).
	 */
	@Test
	public void testEntrySetOrder() throws InvalidDataException {
		Index<ABlob, CVMLong> m = Index.none();
		for (int i = 0; i < 50; i++) {
			m = m.assoc(LongBlob.create(i), RT.cvm((long) i));
		}
		assertEquals(50L, m.count());

		java.util.Iterator<java.util.Map.Entry<ABlob, CVMLong>> it = m.entrySet().iterator();
		for (long i = 0; i < m.count(); i++) {
			assertEquals(m.entryAt(i), it.next(), "entrySet() order diverged from entryAt() sorted order");
		}
		assertFalse(it.hasNext());
		assertThrows(NoSuchElementException.class, it::next);
		assertTrue(m.entrySet().spliterator().hasCharacteristics(Spliterator.ORDERED));
	}

	@Test
	public void testEntrySetContainsJavaEntry() {
		Index<ABlob, CVMLong> m = Samples.INT_INDEX_256;
		Set<Map.Entry<ABlob, CVMLong>> entries = m.entrySet();
		MapEntry<ABlob, CVMLong> entry = m.entryAt(m.count() / 2);

		assertTrue(entries.contains(Map.entry(entry.getKey(), entry.getValue())));
		assertFalse(entries.contains(Map.entry(entry.getKey(), CVMLong.MINUS_ONE)));

		ABlob nilKey = Blob.fromHex("cafebabe");
		Index<ABlob, ACell> nilIndex = Index.of(nilKey, null);
		assertTrue(nilIndex.entrySet().contains(new AbstractMap.SimpleImmutableEntry<>(nilKey, null)));
	}

	@Test
	public void testEntrySetIsImmutable() {
		Index<ABlob, CVMLong> m = Samples.INT_INDEX_256;
		Set<Map.Entry<ABlob, CVMLong>> entries = m.entrySet();
		MapEntry<ABlob, CVMLong> entry = m.entryAt(0);

		assertAll(
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.add(entry)),
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.remove(entry)),
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.addAll(Set.of())),
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.removeAll(Set.of())),
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.retainAll(Set.of())),
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.removeIf(e -> false)),
				() -> assertThrows(UnsupportedOperationException.class, entries::clear),
				() -> assertThrows(UnsupportedOperationException.class, () -> entries.iterator().remove()));
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
		Index<AArrayBlob, PeerStatus> bm = InitTest.STATE.getPeers();
		doIndexTests(bm);

		Index<AArrayBlob, PeerStatus> fm =bm.filterValues(ps -> ps==bm.get(InitTest.FIRST_PEER_KEY));
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
		byte[] keyBytes=new byte[255];
		Blob ks=Blob.create(Arrays.copyOf(keyBytes,254));
		Blob k=Blob.create(keyBytes);
		byte[] longer=Arrays.copyOf(keyBytes,256);
		longer[255]=0x22;
		Blob k2=Blob.create(longer);
		longer=Arrays.copyOf(keyBytes,257);
		longer[255]=0x33;
		longer[256]=0x33;
		Blob k3=Blob.create(longer);
		
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
	public void testExtendedKeyDistinction() throws InvalidDataException, BadFormatException {
		byte[] aBytes=new byte[255];
		byte[] bBytes=aBytes.clone();
		bBytes[254]=1;
		Blob a=Blob.create(aBytes);
		Blob b=Blob.create(bBytes);
		Index<Blob,CVMLong> m=Index.of(a,1,b,2);

		assertEquals(2,m.count());
		assertEquals(509,m.getDepth());
		assertEquals(CVMLong.ONE,m.get(a));
		assertEquals(CVMLong.create(2),m.get(b));
		m.validate();

		Blob encoding=Cells.encode(m);
		assertEquals((byte)0x83,encoding.byteAt(3));
		assertEquals((byte)0x7D,encoding.byteAt(4));
		CAD3Encoder decoder=new CAD3Encoder(NullStore.INSTANCE);
		Index<?,?> decoded=(Index<?,?>)decoder.decode(encoding);
		assertEquals(509,decoded.getDepth());
		assertEquals(encoding,Cells.encode(decoded));

		byte[] nonCanonical=encoding.getBytes();
		nonCanonical[3]=(byte)0x80;
		nonCanonical[4]=0;
		assertThrows(BadFormatException.class,() -> decoder.decode(Blob.create(nonCanonical)));
		byte[] excessive=encoding.getBytes();
		excessive[3]=(byte)0x83;
		excessive[4]=(byte)0x7E; // MAX_DEPTH is invalid for a multi-entry node
		assertThrows(BadFormatException.class,() -> decoder.decode(Blob.create(excessive)));

		Index<Blob,CVMLong> shallow=Index.of(Blob.fromHex("10"),1,Blob.fromHex("11"),2);
		assertEquals((byte)1,Cells.encode(shallow).byteAt(3)); // historical one-byte form
	}

	@Test
	public void testMaximumDepthIndexStackSafety() throws InterruptedException, InvalidDataException {
		byte[] zeroBytes=new byte[255];
		Blob zero=Blob.create(zeroBytes);
		Index<Blob,CVMLong> built=Index.of(zero,-1);
		// One divergent key at every nibble makes a 510-level comb trie. Insert
		// deepest-first so constructing the hostile fixture itself is stack-safe.
		for (int digit=Index.MAX_DEPTH-1;digit>=0;digit--) {
			byte[] keyBytes=zeroBytes.clone();
			int byteIndex=digit>>>1;
			keyBytes[byteIndex]=(byte)(((digit&1)==0) ? 0x10 : 0x01);
			built=built.assoc(Blob.create(keyBytes),CVMLong.create(digit));
		}
		assertEquals(Index.MAX_DEPTH+1,built.count());
		built.validate();

		Index<Blob,CVMLong> index=built;
		AtomicReference<Throwable> failure=new AtomicReference<>();
		Thread worker=new Thread(null,() -> {
			try {
				assertEquals(CVMLong.create(-1),index.get(zero));
				assertEquals(zero,index.entryAt(0).getKey());

				Index<Blob,CVMLong> updated=index.assoc(zero,CVMLong.create(999));
				assertEquals(CVMLong.create(999),updated.get(zero));
				assertEquals(Index.MAX_DEPTH,updated.dissoc(zero).count());

				long[] visited=new long[1];
				index.forEach((k,v) -> visited[0]++);
				assertEquals(index.count(),visited[0]);
				assertEquals(index.count(),index.reduceEntries((n,e) -> n+1,0L));
				assertTrue(index.containsValue(CVMLong.create(Index.MAX_DEPTH-1)));
				assertEquals(Index.MAX_DEPTH,index.filterValues(v -> v.longValue()>=0).count());

				Index<Blob,CVMLong> merged=index.mergeDifferences(updated,(a,b) -> b);
				assertEquals(updated,merged);
			} catch (Throwable t) {
				failure.set(t);
			}
		},"index-max-depth",512*1024);
		worker.start();
		worker.join();
		if (failure.get()!=null) throw new AssertionError("Maximum-depth Index operation failed",failure.get());
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

	@Test
	public void testDeepCombIndex() throws InvalidDataException {
		// "Comb" structure: entry-less nodes all the way down the zero spine, each
		// branching to a divergent leaf. Worst case for prefix computation and
		// incremental prefix matching during assoc descent.
		HashMap<ABlob, CVMLong> ref = new HashMap<>();
		Index<ABlob, CVMLong> m = Index.none();
		for (int i = 0; i < 32; i++) {
			byte[] bs = new byte[i + 1];
			bs[i] = (byte) 0xFF; // i zero bytes then a divergent byte
			Blob k = Blob.create(bs);
			m = m.assoc(k, CVMLong.create(i));
			ref.put(k, CVMLong.create(i));
		}
		assertEquals(ref.size(), m.size());
		m.validate();

		// deep assoc descends the whole entry-less spine (max key length, all zeros)
		Blob spine = Blob.create(new byte[32]);
		m = m.assoc(spine, CVMLong.create(1000));
		ref.put(spine, CVMLong.create(1000));
		m.validate();

		// add entries at every level of the spine itself
		for (int i = 1; i < 32; i++) {
			Blob k = Blob.create(new byte[i]);
			m = m.assoc(k, CVMLong.create(2000 + i));
			ref.put(k, CVMLong.create(2000 + i));
		}
		m.validate();
		assertEquals(ref.size(), m.size());
		for (java.util.Map.Entry<ABlob, CVMLong> me : ref.entrySet()) {
			assertEquals(me.getValue(), m.get(me.getKey()));
		}

		// dissoc everything back out again
		Index<ABlob, CVMLong> d = m;
		for (ABlob k : ref.keySet()) {
			d = d.dissoc(k);
		}
		assertSame(Index.none(), d);

		doIndexTests(m);
	}

	/**
	 * Malformed Index structures can reach operations without deep validation:
	 * CAD3 decoding cannot check entry key types, child types or child depths,
	 * because entry and child refs are lazy. Operations must behave
	 * deterministically with no out-of-range reads, no unbounded recursion and
	 * no unchecked exceptions; assoc may return null (as for invalid key types).
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testMalformedDepthLongerThanPrefix() {
		// Node declaring depth 40 whose entry key has only 4 hex digits
		MapEntry shortEntry = MapEntry.create(Blob.fromHex("1234"), CVMLong.ONE);
		Index child = Index.create(Blob.fromHex("00112233445566778899aabbccddeeff0011223344"), CVMLong.create(2));
		Index bad = Index.unsafeCreate(40, shortEntry, new Ref[] { child.getRef() }, 0x0001, 2);
		assertThrows(InvalidDataException.class, bad::validate);

		// key diverging beyond the physical prefix: malformed detected, null result
		Blob k1 = Blob.fromHex("12345678901234567890");
		assertNull(bad.assoc(k1, CVMLong.ZERO));
		assertNull(bad.assoc(k1, CVMLong.ZERO)); // deterministic

		// key that is a strict subset of the physical prefix: split still works
		Blob k2 = Blob.fromHex("12");
		ACell r1 = bad.assoc(k2, CVMLong.ZERO);
		ACell r2 = bad.assoc(k2, CVMLong.ZERO);
		assertNotNull(r1);
		assertEquals(r1, r2); // deterministic

		// lookups miss cleanly, including key lengths straddling the declared depth
		assertNull(bad.get(Blob.fromHex("12345678901234567890123456789012345678901234")));
		assertNull(bad.get(Blob.fromHex("1234567890123456789012345678901234567890")));
		assertNull(bad.get(Blob.fromHex("1234")));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testMalformedChildDepthNotIncreasing() {
		// child depth (2) <= parent depth (4): descent must terminate deterministically
		Index shallowChild = Index.create(Blob.fromHex("12"), CVMLong.create(3));
		Index bad = Index.unsafeCreate(4, MapEntry.create(Blob.fromHex("1234"), CVMLong.ONE),
				new Ref[] { shallowChild.getRef() }, 0x0002, 2);
		assertThrows(InvalidDataException.class, bad::validate);

		Blob k = Blob.fromHex("123415"); // descends into digit 1 child
		assertNull(bad.assoc(k, CVMLong.ZERO));
		assertNull(bad.get(k));
		assertSame(bad, bad.dissoc(k));

		// same guard covers an EMPTY child (depth 0)
		Index bad2 = Index.unsafeCreate(4, MapEntry.create(Blob.fromHex("1234"), CVMLong.ONE),
				new Ref[] { Index.EMPTY.getRef() }, 0x0002, 2);
		assertNull(bad2.assoc(k, CVMLong.ZERO));
		assertNull(bad2.get(k));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testMalformedNonIndexChild() {
		// child ref resolving to a non-Index cell
		Index bad = Index.unsafeCreate(4, MapEntry.create(Blob.fromHex("1234"), CVMLong.ONE),
				new Ref[] { CVMLong.create(666).getRef() }, 0x0002, 2);
		assertThrows(InvalidDataException.class, bad::validate);

		Blob k = Blob.fromHex("123415");
		assertNull(bad.get(k));
		assertNull(bad.assoc(k, CVMLong.ZERO));
		assertSame(bad, bad.dissoc(k));

		// entryAt skips the malformed child, then reports bounds
		assertEquals(Blob.fromHex("1234"), bad.entryAt(0).getKey());
		assertThrows(IndexOutOfBoundsException.class, () -> bad.entryAt(1));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testMalformedNonBlobEntryKey() {
		// entry key that is not blob-like
		MapEntry badEntry = MapEntry.create(CVMLong.create(7), CVMLong.ONE);
		Index child = Index.create(Blob.fromHex("1234"), CVMLong.create(2));
		Index bad = Index.unsafeCreate(2, badEntry, new Ref[] { child.getRef() }, 0x0002, 2);

		Blob k = Blob.fromHex("12");
		assertNull(bad.get(k));
		assertNull(bad.assoc(Blob.fromHex("1299"), CVMLong.ZERO));
		assertSame(bad, bad.dissoc(k));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testMalformedDeepChainNoStackOverflow() {
		// A chain of entry-less single-child nodes with non-increasing depth is
		// invalid, but decodable: its length is bounded only by encoding size.
		// Prefix computation must not recurse and descent must be depth-bounded.
		Index leaf = Index.create(Blob.fromHex("12345678"), CVMLong.ONE);
		Index node = leaf;
		for (int i = 0; i < 50000; i++) {
			node = Index.unsafeCreate(2, null, new Ref[] { node.getRef() }, 0x0002, 2 + i);
		}
		final Index chain = node;

		// assoc triggers getPrefix at the root: must walk iteratively
		ACell r1 = chain.assoc(Blob.fromHex("1234"), CVMLong.ZERO);
		ACell r2 = chain.assoc(Blob.fromHex("1234"), CVMLong.ZERO);
		assertNotNull(r1);
		assertEquals(((Index) r1).count(), ((Index) r2).count()); // deterministic

		// getEntry and dissoc descend into the digit 1 child: bounded by the depth guard
		assertNull(chain.getEntry(Blob.fromHex("12145678")));
		assertSame(chain, chain.dissoc(Blob.fromHex("12145678")));
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

	/**
	 * A single-entry node does not encode its depth, and a key over the embedded
	 * limit is not loaded when the node is decoded. The depth must then be derived
	 * from the key on first use: assuming MAX_DEPTH made every such entry invisible
	 * to lookup after a store reload while iteration still listed it (covia#469).
	 */
	@Test
	public void testNonEmbeddedSingleEntryKeySurvivesStoreReload() throws Exception {
		for (int len : new int[] {150, 180, Index.MAX_DEPTH/2-1}) {
			byte[] bytes=new byte[len];
			Arrays.fill(bytes,(byte)0x42);
			Blob longKey=Blob.wrap(bytes);
			Blob shortKey=Blob.fromHex("ff");
			assertFalse(longKey.isEmbedded());
			Index<ABlob,CVMLong> index=Index.of(longKey,CVMLong.ONE,shortKey,CVMLong.ZERO);

			EtchStore store=EtchStore.createTemp("index-reload");
			File file=store.getFile();
			Hash hash=Cells.persist(index,store).getHash();
			store.close();
			EtchStore reopened=EtchStore.create(file);
			try {
				Index<ABlob,CVMLong> reloaded=reopened.<Index<ABlob,CVMLong>>refForHash(hash).getValue();
				assertEquals(2,reloaded.count());
				assertEquals(longKey,reloaded.entryAt(0).getKey());
				assertEquals(CVMLong.ONE,reloaded.get(longKey),"key length "+len);
				assertEquals(CVMLong.ZERO,reloaded.get(shortKey));
				reloaded.validate();
				assertEquals(index,reloaded);
				assertEquals(index.dissoc(longKey),reloaded.dissoc(longKey));

				// A longer key extending the reloaded key must still branch correctly
				Blob extended=longKey.append(Blob.fromHex("01")).toFlatBlob();
				Index<ABlob,CVMLong> grown=reloaded.assoc(extended,CVMLong.create(2));
				assertNotNull(grown);
				assertEquals(3,grown.count());
				assertEquals(CVMLong.create(2),grown.get(extended));
				assertEquals(CVMLong.ONE,grown.get(longKey));
			} finally {
				reopened.close();
			}
		}
	}

	/**
	 * Keys beyond MAX_KEY_BYTES are clamped to MAX_DEPTH and alias on their first
	 * 255 bytes. The clamp must also apply when a non-embedded oversized key is
	 * resolved lazily after a store reload, and a depth outside the range must be
	 * rejected at construction rather than truncated.
	 */
	@Test
	public void testOversizedKeysClampDepthAcrossStoreReload() throws Exception {
		byte[] base=new byte[300];
		Arrays.fill(base,(byte)0x51);
		Blob huge=Blob.wrap(base); // non-embedded and beyond MAX_KEY_BYTES
		byte[] aliasBytes=Arrays.copyOf(base,1000);
		aliasBytes[999]=0x77;
		Blob hugeAlias=Blob.wrap(aliasBytes); // same first 255 bytes, different tail
		Blob sibling=Blob.wrap(Arrays.copyOf(base,254)); // shorter key on the same prefix
		Blob other=Blob.fromHex("ff");

		Index<ABlob,CVMLong> single=Index.of(huge,CVMLong.ONE);
		assertEquals(Index.MAX_DEPTH,single.getDepth());
		Index<ABlob,CVMLong> index=Index.of(huge,CVMLong.ONE,sibling,CVMLong.create(2),other,CVMLong.ZERO);
		assertEquals(3,index.count());

		for (Index<ABlob,CVMLong> original : java.util.List.of(single,index)) {
			EtchStore store=EtchStore.createTemp("index-oversized");
			File file=store.getFile();
			Hash hash=Cells.persist(original,store).getHash();
			store.close();
			EtchStore reopened=EtchStore.create(file);
			try {
				Index<ABlob,CVMLong> reloaded=reopened.<Index<ABlob,CVMLong>>refForHash(hash).getValue();
				assertEquals(original.count(),reloaded.count());
				assertEquals(CVMLong.ONE,reloaded.get(huge));
				assertEquals(CVMLong.ONE,reloaded.get(hugeAlias),"oversized keys alias on the first 255 bytes");
				assertEquals(huge,reloaded.getEntry(hugeAlias).getKey());
				reloaded.validate();
				assertEquals(original,reloaded);

				// The reloaded oversized entry resolves to exactly MAX_DEPTH
				Index<ABlob,CVMLong> only=reloaded.dissoc(other).dissoc(sibling);
				assertEquals(1,only.count());
				assertEquals(Index.MAX_DEPTH,only.getDepth());

				// Aliasing keys replace and remove the same entry
				Index<ABlob,CVMLong> replaced=reloaded.assoc(hugeAlias,CVMLong.create(9));
				assertEquals(reloaded.count(),replaced.count());
				assertEquals(CVMLong.create(9),replaced.get(huge));
				Index<ABlob,CVMLong> removed=reloaded.dissoc(hugeAlias);
				assertEquals(reloaded.count()-1,removed.count());
				assertNull(removed.get(huge));
			} finally {
				reopened.close();
			}
		}

		// Out-of-range depths are rejected, never silently truncated to int
		assertThrows(IllegalArgumentException.class,
			() -> Index.unsafeCreate(Index.MAX_DEPTH+1,null,Index.EMPTY_CHILDREN,0,0));
		assertThrows(IllegalArgumentException.class,
			() -> Index.unsafeCreate(Long.MAX_VALUE,null,Index.EMPTY_CHILDREN,0,0));
		assertThrows(IllegalArgumentException.class,
			() -> Index.unsafeCreate(-2,null,Index.EMPTY_CHILDREN,0,0));
	}

	/**
	 * A decoded single-entry node with a non-embedded key holds UNRESOLVED_DEPTH
	 * until first use. The sentinel must never reach an encoding: a single-entry
	 * node encodes no depth, every multi-entry construction resolves it first, and
	 * the constructor refuses it for anything but a single-entry node.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testUnresolvedDepthIsNeverEncoded() throws Exception {
		byte[] bytes=new byte[200];
		Arrays.fill(bytes,(byte)0x37);
		Blob longKey=Blob.wrap(bytes);
		Blob shortKey=Blob.fromHex("ff");
		Index<ABlob,CVMLong> single=Index.of(longKey,CVMLong.ONE);
		Index<ABlob,CVMLong> pair=Index.of(longKey,CVMLong.ONE,shortKey,CVMLong.ZERO);

		// Encode a sentinel node directly: no cached encoding, so encodeRaw runs
		MapEntry<ABlob,CVMLong> entry=MapEntry.create(longKey,CVMLong.ONE);
		Index<ABlob,CVMLong> unresolved=Index.unsafeCreate(Index.UNRESOLVED_DEPTH,entry,Index.EMPTY_CHILDREN,0,1);
		assertEquals(single.getEncoding(),unresolved.getEncoding());
		assertEquals(single.getEncodingLength(),unresolved.getEncodingLength());
		assertEquals(single.getHash(),unresolved.getHash());
		assertEquals(single.isEmbedded(),unresolved.isEmbedded());
		assertEquals(CVMLong.ONE,unresolved.get(longKey));
		assertEquals(longKey.hexLength(),unresolved.getDepth());
		unresolved.validate();

		// Growing a sentinel node resolves the depth before any multi-entry node is built
		Index<ABlob,CVMLong> fresh=Index.unsafeCreate(Index.UNRESOLVED_DEPTH,entry,Index.EMPTY_CHILDREN,0,1);
		Index<ABlob,CVMLong> grown=fresh.assoc(shortKey,CVMLong.ZERO);
		assertEquals(pair.getEncoding(),grown.getEncoding());
		assertEquals(pair,grown);
		grown.validate();

		// The sentinel is only legal on a single-entry node
		assertThrows(IllegalArgumentException.class,
			() -> Index.unsafeCreate(Index.UNRESOLVED_DEPTH,null,Index.EMPTY_CHILDREN,0,0));
		assertThrows(IllegalArgumentException.class,
			() -> Index.unsafeCreate(Index.UNRESOLVED_DEPTH,null,Index.EMPTY_CHILDREN,0,1));
		assertThrows(IllegalArgumentException.class,
			() -> Index.unsafeCreate(Index.UNRESOLVED_DEPTH,entry,Index.EMPTY_CHILDREN,0,2));

		// Reload from a store, then persist again into a second store before any lookup
		for (Index<ABlob,CVMLong> original : java.util.List.of(single,pair)) {
			Blob expected=original.getEncoding();
			EtchStore store=EtchStore.createTemp("index-unresolved");
			File file=store.getFile();
			Hash hash=Cells.persist(original,store).getHash();
			store.close();
			EtchStore reopened=EtchStore.create(file);
			EtchStore second=EtchStore.createTemp("index-unresolved-2");
			File secondFile=second.getFile();
			try {
				Index<ABlob,CVMLong> reloaded=reopened.<Index<ABlob,CVMLong>>refForHash(hash).getValue();
				Hash again=Cells.persist(reloaded,second).getHash();
				assertEquals(hash,again);
				second.close();
				second=EtchStore.create(secondFile);
				Index<ABlob,CVMLong> twice=second.<Index<ABlob,CVMLong>>refForHash(again).getValue();
				assertEquals(expected,twice.getEncoding());
				assertEquals(CVMLong.ONE,twice.get(longKey));
				assertEquals(original,twice);
				twice.validate();
				assertEquals(expected,reopened.decode(expected).getEncoding());
			} finally {
				reopened.close();
				second.close();
			}
		}
	}

	/**
	 * Regression for Convex-Dev/convex#723. A node beyond depth 64 whose children
	 * are hashed must decode from a multi-cell message without touching any store:
	 * its embedded status follows from the ref flags and the inline lengths alone.
	 * Covers the inline shape (an embedded node inside a vector) and the trailing
	 * shape (a non-embedded node as its own message cell). The embedding check used
	 * to dereference the hashed children and fail on a receiver that lacked them.
	 */
	@Test
	public void testDeepIndexDecodesFromMultiCellMessage() throws IOException, BadFormatException, InvalidDataException {
		Index<Blob,CVMLong> inline=deepIndex(2);
		Index<Blob,CVMLong> trailing=deepIndex(5);
		assertEquals(200L,inline.getDepth());
		assertTrue(inline.isEmbedded(),"two hashed children fit within the embedded limit");
		assertFalse(trailing.isEmbedded(),"five hashed children exceed the embedded limit");

		for (Index<Blob,CVMLong> deep : java.util.List.of(inline,trailing)) {
			for (int i=0;i<deep.getRefCount();i++) assertFalse(deep.getRef(i).isEmbedded());
			AVector<ACell> value=Vectors.of(deep,CVMLong.ONE);
			Blob message=Format.encodeMultiCell(value,true);

			// Storeless decode: any store access would throw MissingDataException
			ACell decoded=CVMEncoder.INSTANCE.decodeMultiCell(message);
			assertEquals(value,decoded);
			checkDecodedDeepIndex(deep,decoded);

			// Store-backed decode against a store holding none of the cells
			try (EtchStore empty=EtchStore.createTemp("index-deep-decode")) {
				ACell viaStore=empty.decodeMultiCell(message);
				assertEquals(value,viaStore);
				checkDecodedDeepIndex(deep,viaStore);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void checkDecodedDeepIndex(Index<Blob,CVMLong> expected, ACell decoded) throws InvalidDataException {
		Index<Blob,CVMLong> index=(Index<Blob,CVMLong>)((AVector<ACell>)decoded).get(0);
		assertEquals(expected.count(),index.count());
		for (long i=0;i<expected.count();i++) assertEquals(CVMLong.create(i),index.get(deepKey((int)i)));
		assertEquals(expected.getEncodingLength(),index.getEncodingLength());
		index.validate();
	}

	/**
	 * Builds an entry-less node at depth 200 whose children are all hashed: a
	 * 100-byte shared prefix and 137-byte keys, so each single-entry child exceeds
	 * the embedded limit while its key stays embedded.
	 */
	private static Index<Blob,CVMLong> deepIndex(int children) {
		Index<Blob,CVMLong> index=Index.none();
		for (int i=0;i<children;i++) index=index.assoc(deepKey(i),CVMLong.create(i));
		return index;
	}

	private static Blob deepKey(int i) {
		byte[] bs=new byte[137];
		Arrays.fill(bs,0,100,(byte)0xAB);
		Arrays.fill(bs,100,137,(byte)(0x11*i));
		return Blob.wrap(bs);
	}

	/**
	 * Index computes its encoding length arithmetically from its refs rather than
	 * by building the encoding, so the embedding check never dereferences a hashed
	 * child. Every node of a fresh structure must report the exact size of the
	 * encoding it later produces, and agree on embedding, before any encoding
	 * exists. Covers the shapes the arithmetic distinguishes: empty, single entries
	 * with embedded and hashed keys and values, entry-bearing branch nodes, one- and
	 * two-byte counts and depths, and embedded and hashed children.
	 */
	@Test
	public void testEncodingLengthWithoutEncoding() throws BadFormatException {
		Index<ABlob,ACell> mixed=Index.none();
		for (int i=0;i<130;i++) mixed=mixed.assoc(patternKey(i,1+(i*37)%255),patternValue(i));
		Index<ABlob,ACell> longKeys=Index.none();
		for (int i=0;i<300;i++) longKeys=longKeys.assoc(patternKey(i,64+(i*37)%192),patternValue(i));
		Index<ABlob,ACell> prefixChain=Index.none();
		for (int i=1;i<=4;i++) prefixChain=prefixChain.assoc(patternKey(7,i),CVMLong.create(i));

		java.util.List<ACell> shapes=java.util.List.of(
			Index.none(),
			Index.of(patternKey(1,3),CVMLong.ONE),
			Index.of(patternKey(2,150),CVMLong.ONE), // hashed key
			Index.of(patternKey(3,255),Blobs.createFilled(1,500)), // hashed key and value
			prefixChain,mixed,longKeys,deepIndex(2),deepIndex(5),comb(64));

		for (ACell shape : shapes) {
			checkEncodingLengths(shape);
			checkEncodingLengths(CVMEncoder.INSTANCE.decodeMultiCell(Format.encodeMultiCell(shape,true)));
		}
	}

	private static void checkEncodingLengths(ACell root) {
		ArrayList<Index<?,?>> nodes=new ArrayList<>();
		collectIndexNodes(root,nodes,new IdentityHashMap<>());
		assertFalse(nodes.isEmpty());
		int n=nodes.size();
		int[] lengths=new int[n];
		boolean[] embedded=new boolean[n];
		// Record every node before any encoding exists
		for (int i=0;i<n;i++) {
			lengths[i]=nodes.get(i).getEncodingLength();
			embedded[i]=nodes.get(i).isEmbedded();
		}
		for (int i=0;i<n;i++) {
			Index<?,?> node=nodes.get(i);
			int actual=node.getEncoding().size();
			assertEquals(actual,lengths[i],"encoding length of node with count "+node.count());
			assertEquals(actual<=Format.MAX_EMBEDDED_LENGTH,embedded[i],"embedding of node with count "+node.count());
		}
	}

	private static void collectIndexNodes(ACell cell, ArrayList<Index<?,?>> out, IdentityHashMap<ACell,Boolean> seen) {
		if (cell==null||seen.put(cell,Boolean.TRUE)!=null) return;
		if (cell instanceof Index) out.add((Index<?,?>)cell);
		int n=cell.getRefCount();
		for (int i=0;i<n;i++) collectIndexNodes(cell.getRef(i).getValue(),out,seen);
	}

	private static Blob patternKey(int i, int len) {
		byte[] bs=new byte[len];
		for (int j=0;j<len;j++) bs[j]=(byte)(i*31+j*17);
		return Blob.wrap(bs);
	}

	private static ACell patternValue(int i) {
		switch (i%4) {
			case 0: return CVMLong.create(i);
			case 1: return Blobs.createFilled(i&0xff,200); // hashed value
			case 2: return Blobs.createFilled(i&0xff,100); // large embedded value
			default: return Vectors.of(CVMLong.create(i),Strings.create("v"+i));
		}
	}

	/** One divergent key at every nibble of a zero prefix: a spine of nested branch nodes. */
	private static Index<Blob,CVMLong> comb(int prefixBytes) {
		byte[] zero=new byte[prefixBytes];
		Index<Blob,CVMLong> built=Index.of(Blob.create(zero),-1);
		for (int digit=prefixBytes*2-1;digit>=0;digit--) {
			byte[] kb=zero.clone();
			kb[digit>>>1]=(byte)(((digit&1)==0)?0x10:0x01);
			built=built.assoc(Blob.create(kb),CVMLong.create(digit));
		}
		return built;
	}
}
