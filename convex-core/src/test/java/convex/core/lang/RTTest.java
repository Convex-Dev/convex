package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Symbols;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

import static convex.test.Assertions.*;

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
	public void testJVMCasts() {
		assertEquals((Long)1L, RT.jvm(CVMLong.ONE));
		assertEquals((Double)1.0, RT.jvm(CVMDouble.ONE));
		assertEquals("foo", RT.jvm(Symbols.FOO));
		assertEquals("foo", RT.jvm(Keywords.FOO));
		
		{
			List<Object> e=RT.jvm(Vectors.empty());
			assertTrue(e.isEmpty());
		}
		
		{
			List<Object> e=RT.jvm(Sets.empty());
			assertTrue(e.isEmpty());
		}
		
		{
			List<Object> e=RT.jvm(Lists.empty());
			assertTrue(e.isEmpty());
		}


	}
	
	@Test
	public void testGetIn() {
		assertNull(RT.getIn(null, 1));
		assertCVMEquals(3,RT.getIn(Vectors.of(2,3), 1));
		assertCVMEquals(3,Vectors.of(2,3).getIn(1));
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
