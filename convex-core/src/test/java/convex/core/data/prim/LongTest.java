package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ObjectsTest;

public class LongTest {

	@Test
	public void testEquality() {
		long v=666666;
		assertEquals(CVMLong.create(v),CVMLong.create(v));
	}
	
	@Test 
	public void cacheTest() {
		assertSame(CVMLong.create(255),CVMLong.create(255));
		assertNotSame(CVMLong.create(666),CVMLong.create(666));
	}
	
	@Test public void testLongSamples() {
		doLongTest(CVMLong.ZERO);
		doLongTest(CVMLong.MIN_VALUE);
		doLongTest(CVMLong.MINUS_ONE);
		doLongTest(CVMLong.MAX_VALUE);
		doLongTest(CVMLong.create(666));
		doLongTest(CVMLong.create(Integer.MAX_VALUE));
		doLongTest(CVMLong.create(1L+Integer.MAX_VALUE));
	}
	
	public void doLongTest(CVMLong a) {
		long val=a.longValue();
		
		assertEquals(CVMLong.create(val),a);
		assertTrue(a.isCanonical());
		assertTrue(a.isEmbedded());
		
		
		ObjectsTest.doAnyValueTests(a);
	}
}
