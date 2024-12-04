package convex.core.cpos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

import convex.core.data.ObjectsTest;

public class OrderTest {

	@Test public void testEmptyOrder() {
		Order o=Order.create();
		assertEquals(0,o.getTimestamp());
		assertEquals(0,o.getBlockCount());
		
		ObjectsTest.doAnyValueTests(o);
	}
}
