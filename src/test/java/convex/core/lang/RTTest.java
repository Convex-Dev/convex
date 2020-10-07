package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Symbol;
import convex.core.data.Vectors;

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
		assertEquals("foo", RT.name("foo").toString());

		assertNull(RT.name(null));
		assertNull(RT.name(1));
		assertNull(RT.name(Amount.create(10)));
	}

	@Test
	public void testAddress() {
		Address za = Address.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20 ,21,22,23,24,25,26,27,28,29,30,31,32},0);
		assertEquals(za, RT.address(za.toBlob()));
		assertSame(za, RT.address(za));

		// Check null return values for invalid addresses
		assertNull(RT.address(null)); // null not allowed
		assertNull(RT.address(1)); // ints not allowed
		assertNull(RT.address("xyz2030405060708090a0b0c0d0e0f1011121314")); // bad format
		assertNull(RT.address("0012")); // too short
	}

	@Test
	public void testSequence() {
		AVector<Long> v = Vectors.of(1L, 2L, 3L);
		AList<Long> l = Lists.of(1L, 2L, 3L);
		assertEquals(Vectors.of(1L, 2L), RT.sequence(new Long[] { 1L, 2L }));
		assertEquals(v, RT.sequence(new java.util.ArrayList<>(v)));
		assertSame(v, RT.sequence(v));
		assertSame(l, RT.sequence(l));
		assertSame(Vectors.empty(), RT.sequence(null));

		// null return values if cast fails
		assertNull(RT.sequence(1)); // ints not allowed
		assertNull(RT.sequence(Keywords.FOO)); // keywords not allowed
	}
}
