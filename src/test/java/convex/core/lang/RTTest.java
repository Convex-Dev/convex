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
		assertEquals("foo", RT.name("foo").toString());

		assertNull(RT.name(null));
		assertNull(RT.name(1));
		assertNull(RT.name(10L));
	}

	@Test
	public void testAddress() {
		Address za = Address.create(0x7777777);
		assertEquals(za, RT.address(za.toBlob()));
		assertSame(za, RT.address(za));

		// reading a hex address
		assertEquals(Address.create(18),RT.address("0000000000000012")); // OK, hex string
		assertNull(RT.address("0012")); // too short
		
		// Check null return values for invalid addresses
		assertNull(RT.address(null)); // null not allowed
		assertNull(RT.address(-1)); // negative ints not allowed
		assertNull(RT.address("xyz2030405060708090a0b0c0d0e0f1011121314")); // bad format
	}

	@Test
	public void testSequence() {
		AVector<CVMLong> v = Vectors.of(1L, 2L, 3L);
		AList<CVMLong> l = Lists.of(1L, 2L, 3L);
		assertEquals(Vectors.of(1L, 2L,3L), RT.sequence(l.toCellArray()));
		assertEquals(v, RT.sequence(new java.util.ArrayList<>(v)));
		assertSame(v, RT.sequence(v));
		assertSame(l, RT.sequence(l));
		assertSame(Vectors.empty(), RT.sequence(null));

		// null return values if cast fails
		assertNull(RT.sequence(1)); // ints not allowed
		assertNull(RT.sequence(Keywords.FOO)); // keywords not allowed
	}
	
	@Test 
	public void testCVMCasts() {
		assertEquals(CVMLong.create(1L),RT.cvm(1L));
		assertEquals(CVMDouble.create(0.17),RT.cvm(0.17));
		assertEquals(Strings.create("foo"),RT.cvm("foo"));
		
		// CVM objects shouldn't change
		Keyword k=Keyword.create("test-key");
		assertSame(k,RT.cvm(k));

	}
}
