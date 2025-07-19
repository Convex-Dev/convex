package convex.lattice;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

public class CursorTest {

	@Test public void testRoot() {
		Root<AInteger> root=new Root<>();
		CVMLong TWO=CVMLong.create(2);
		
		root.set(1); // value is now 1
		assertCVMEquals(1,root.get());
		
		assertFalse(root.compareAndSet(CVMLong.ZERO, CVMLong.ZERO));
		assertTrue(root.compareAndSet(CVMLong.ONE, CVMLong.ZERO)); // value is now 0
		assertCVMEquals(0,root.get());
		
		assertCVMEquals(0,root.getAndSet(TWO)); // value is now 2
		assertSame(TWO,root.get());
		
		assertEquals(TWO,root.getAndUpdate(a->a.inc())); // value is now 3
		assertCVMEquals(4,root.updateAndGet(a->a.inc())); // value is now 4;
		
	}
}
