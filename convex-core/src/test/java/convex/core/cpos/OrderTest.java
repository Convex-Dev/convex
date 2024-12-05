package convex.core.cpos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

import convex.core.data.ObjectsTest;
import convex.core.data.Refs;

public class OrderTest {

	@Test public void testEmptyOrder() {
		Order o=Order.create();
		assertEquals(0,o.getTimestamp());
		assertEquals(0,o.getBlockCount());
		
		// Consensus cells (1+4) + timestamp (1) + empty vector (1)+Top leevl
		assertEquals(4+CPoSConstants.CONSENSUS_LEVELS,Refs.totalRefCount(o));
		ObjectsTest.doAnyValueTests(o);
	}
}
