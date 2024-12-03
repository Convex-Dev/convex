package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.Refs;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * Tests for various CVM data structure operations.
 *
 */
public class DataStructuresTest extends ACVMTest {

	@Test
	public void testSetRoundTripRegression() {
		ASet<CVMLong> a = eval("#{1,8,0,4,9,5,2,3,7,6}");
		assertEquals(10, a.count());
		ASequence<CVMLong> b = RT.sequence(a);
		assertEquals(10, b.count());
		ASet<CVMLong> c = RT.castSet(b);
		assertEquals(a, c);
	}

	@Test
	public void testRefCounts() {
		assertEquals(1, Refs.totalRefCount(Vectors.empty()));
		assertEquals(3, Refs.totalRefCount(Vectors.of(1, 2)));
		assertEquals(6, Refs.totalRefCount(eval("(fn [a] a)"))); // 4 Ref in fn 1 in param
		assertEquals(7, Refs.totalRefCount(eval("[[1 2] [3 4]]"))); // 6 vector element Refs plus root

		assertEquals(7, Refs.uniqueRefCount(eval("[[1 2] [3 4]]"))); // 6 vector element Refs plus root
		assertEquals(3, Refs.uniqueRefCount(eval("[[1 1] [1 1]]"))); // Just 3 levels
	}

}
