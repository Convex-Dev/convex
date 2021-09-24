package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
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
		assertEquals(za, RT.castAddress(za.toBlob()));
		assertSame(za, RT.castAddress(za));

		// reading a hex address
		assertEquals(Address.create(18), RT.castAddress(Strings.create("0000000000000012"))); // OK, hex string
		assertNull(RT.castAddress(Strings.create("0012"))); // too short

		// Check null return values for invalid addresses
		assertNull(RT.castAddress(null)); // null not allowed
		assertNull(RT.castAddress(CVMLong.create(-1))); // negative ints not allowed
		assertNull(RT.castAddress(Strings.create("xyz2030405060708090a0b0c0d0e0f1011121314"))); // bad format
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
	public void testCVMCasts() {
		assertEquals(CVMLong.create(1L), RT.cvm(1L));
		assertEquals(CVMDouble.create(0.17), RT.cvm(0.17));
		assertEquals(Strings.create("foo"), RT.cvm("foo"));

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
