package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ObjectsTest;

public class LongTest {

	@Test
	public void testEquality() {
		long v=666666;
		ObjectsTest.doEqualityTests(CVMLong.create(v),CVMLong.create(v));
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
		doLongTest(CVMLong.create(Integer.MIN_VALUE));
		doLongTest(CVMLong.create(Integer.MIN_VALUE-1L));
	}
	
	@Test public void testCompares() {
		assertEquals(0,CVMLong.ZERO.compareTo(CVMLong.ZERO));
		assertEquals(-1,CVMLong.ZERO.compareTo(CVMLong.ONE));
		assertEquals(1,CVMLong.ONE.compareTo(CVMLong.ZERO));
		
		assertTrue(CVMLong.MAX_VALUE.compareTo(CVMLong.ZERO)>0);
		assertTrue(CVMLong.MIN_VALUE.compareTo(CVMLong.ZERO)<0);
		
		assertEquals(0,CVMLong.ONE.compareTo(CVMLong.create(1)));
	}
	
	public void doLongTest(CVMLong a) {
		long val=a.longValue();
		
		assertEquals(CVMLong.create(val),a);
		assertTrue(a.isCanonical());
		assertTrue(a.isEmbedded());
		assertTrue(a.byteLength()<=8);
		
		if (val!=0) {
			assertEquals(BigInteger.valueOf(val).toByteArray().length,a.byteLength());
			assertNotEquals(0,a.signum().longValue());
		}
		
		ObjectsTest.doAnyValueTests(a);
	}
}
