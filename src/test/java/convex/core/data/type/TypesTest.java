package convex.core.data.type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

public class TypesTest {

	
	@Test
	public void testNil() {
		AType t=Types.NIL;
		assertTrue(t.check(null));
		assertFalse(t.check(CVMLong.ONE));
	}
	
	@Test
	public void testLong() {
		AType t=Types.LONG;
		assertFalse(t.check(null));
		assertTrue(t.check(CVMLong.ONE));
		assertFalse(t.check(CVMDouble.ONE));
	}
	
	@Test
	public void testAny() {
		AType t=Types.ANY;
		assertTrue(t.check(null));
		assertTrue(t.check(CVMLong.ONE));
		assertTrue(t.check(CVMDouble.ONE));
	}
}
