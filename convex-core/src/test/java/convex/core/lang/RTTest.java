package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Tests for RT functions.
 * 
 * Normally should prefer testing via executing core environment functions, but
 * these are useful for testing utility functions, edge cases and internal
 * behaviour of the RT class itself.
 */
public class RTTest {

	@Test
	public void testName() {
		assertEquals("foo", RT.name(Symbol.create("foo")).toString());
		assertEquals("foo", RT.name(Keyword.create("foo")).toString());
		assertEquals("foo", RT.name(Strings.create("foo")).toString());

		assertNull(RT.name(null));
	}

	@Test
	public void testAddress() {
		Address za = Address.create(0x7777777);
		assertEquals(za, RT.castAddress(za.toFlatBlob()));
		assertSame(za, RT.castAddress(za));

		// Check null return values for invalid addresses
		assertNull(RT.castAddress(null)); // null not allowed
		assertNull(RT.castAddress(CVMLong.create(-1))); // negative ints not allowed
		assertNull(RT.castAddress(Strings.create("xyz2030405060708090a0b0c0d0e0f1011121314"))); // bad format
	}
	
	@Test
	public void testPrint() {
		assertEquals(Strings.create("\"'\""),RT.print(Strings.create("'"))); // See #407
	}

	@Test
	public void testSequence() {
		AVector<CVMLong> v = Vectors.of(1L, 2L, 3L);
		AList<CVMLong> l = Lists.of(1L, 2L, 3L);
		assertSame(v, RT.sequence(v));
		assertSame(l, RT.sequence(l));
		assertSame(Vectors.empty(), RT.sequence(null));

		// null return values if cast fails
		assertNull(RT.sequence(Keywords.FOO)); // keywords not allowed
	}
	
	@Test
	public void testJSON() {
		assertNull(RT.json(null));
		
		assertEquals((Long)13L,RT.json(Address.create(13)));
		assertEquals("0xcafebabe",RT.json(Blob.fromHex("cafebabe")));
		assertEquals("0x",RT.json(Blobs.empty()));
		assertEquals("{}",RT.json(Index.none()).toString());
		assertEquals("{}",RT.json(Maps.empty()).toString());
		assertEquals("[1, 2]",RT.json(Vectors.of(1,2)).toString());
		assertEquals("[1, 2]",RT.json(Lists.of(1,2)).toString());
		assertEquals("c",RT.json(CVMChar.create('c')));

		assertEquals("foo",RT.json(Symbols.FOO));
		assertEquals(":foo",RT.json(Keywords.FOO));
		
		// Note keywords get colon removed when used as JSON key
		assertEquals(":bar",RT.jsonMap(Maps.of(Keywords.FOO, Keywords.BAR)).get("foo"));

		
		// JSON should convert keys to strings
		assertEquals(Maps.of("1",2), RT.cvm(RT.json(Maps.of(1,2))));
		assertEquals(Maps.of("[]",3), RT.cvm(RT.json(Maps.of(Vectors.empty(),3))));
		assertEquals(Maps.of("[\"\" 3]",4), RT.cvm(RT.json(Maps.of(Vectors.of("",3),4))));
	}
	
	@Test
	public void testJSONRoundTrips() {
		
		doJSONRoundTrip(1L,CVMLong.ONE);
		doJSONRoundTrip(1.0,CVMDouble.ONE);
		doJSONRoundTrip(null,null);
		
		doJSONRoundTrip(new ArrayList<Object>(),Vectors.empty());
		doJSONRoundTrip(List.of(1,2),Vectors.of(1,2));
		doJSONRoundTrip("hello",Strings.create("hello"));
		doJSONRoundTrip("",Strings.EMPTY);
		doJSONRoundTrip(true,CVMBool.TRUE);
		
		doJSONRoundTrip(new HashMap<String,Object>(),Maps.empty());
		doJSONRoundTrip(Maps.hashMapOf("1",2,"3",4),Maps.of("1",2,"3",4));
	}

	private void doJSONRoundTrip(Object o, ACell c) {
		// o should convert to c
		assertEquals(c,RT.cvm(o)); 
		
		// c should round trip via JSON back to c, since JSON is a subset of CVM types
		ACell roundTrip=RT.cvm(RT.json(c));
		assertEquals(c,roundTrip); 
		
		// c should also round trip via JVM equivalent, since we are using JSON subset
		ACell roundTrip2=RT.cvm(RT.jvm(c));
		assertEquals(c,roundTrip2); 
	}

	@Test
	public void testVec() {
		AVector<CVMLong> v = Vectors.of(1L, 2L, 3L);
		AList<CVMLong> l = Lists.of(1L, 2L, 3L);
		assertEquals(Vectors.of(1L, 2L, 3L), RT.vec(l.toCellArray()));
		assertEquals(v, RT.vec(new java.util.ArrayList<>(v)));

		assertNull(RT.vec(1)); // ints not allowed
	}
	
	@Test 
	public void testCompare() {
		assertEquals(0,RT.compare(CVMDouble.NEGATIVE_ZERO, CVMDouble.ZERO,null));
		assertEquals(13,RT.compare(CVMDouble.NaN, CVMDouble.ZERO,(long) 13));
		assertEquals(13,RT.compare(CVMDouble.ZERO, CVMDouble.NaN,(long) 13));
		assertEquals(13,RT.compare(CVMDouble.NaN, CVMDouble.NaN,(long) 13));
	}

	@Test
	public void testCVMCasts() {
		assertEquals(CVMLong.create(1L), RT.cvm(1L));
		assertEquals(CVMDouble.create(0.17), RT.cvm(0.17));
		assertEquals(Strings.create("foo"), RT.cvm("foo"));
		assertEquals(Vectors.empty(), RT.cvm(new ArrayList<Object>()));

		// CVM objects shouldn't change
		Keyword k = Keyword.create("test-key");
		assertSame(k, RT.cvm(k));

	}

	@Test
	public void testSplitMix() {
		// test examples from :
		// https://rosettacode.org/wiki/Pseudo-random_numbers/Splitmix64
		long seed = 1234567l;
		long x;
		seed = RT.splitmix64Update(seed);
		x=RT.splitmix64Calc(seed);
		assertEquals(6457827717110365317l, x);
		seed = RT.splitmix64Update(seed);
		x=RT.splitmix64Calc(seed);
		assertEquals(3203168211198807973l, x);
		seed = RT.splitmix64Update(seed);
		x=RT.splitmix64Calc(seed);
		assertEquals(0x883EBCE5A3F27C77l, x);
		seed = RT.splitmix64Update(seed);
		x=RT.splitmix64Calc(seed);
		assertEquals(4593380528125082431l, x);
		seed = RT.splitmix64Update(seed);
		x=RT.splitmix64Calc(seed);
		assertEquals(0xE3B8346708CB5ECDl,x);
	}
}
