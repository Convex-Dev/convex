package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.ObjectsTest;
import convex.core.exceptions.BadFormatException;

public class DoubleTest {

	@Test public void testNanEncoding() {
		CVMDouble nan=CVMDouble.NaN;
		
		assertSame(CVMDouble.NaN,CVMDouble.create(Double.NaN));

		// Canonical NaN encoding has just high bit set
		assertEquals(Blob.fromHex("1d7ff8000000000000"),nan.getEncoding());
		
		// create coerces to correct NaN
		assertSame(CVMDouble.NaN,CVMDouble.create(Double.longBitsToDouble(0x7ff8000000ffffffL)));
		
		Blob BAD_NAN=Blob.fromHex("1d7ff8000000ffffff");
		assertThrows(BadFormatException.class,()->Format.read(BAD_NAN));
	}
	
	@Test public void testCompares() {
		assertTrue(CVMDouble.ZERO.compareTo(CVMDouble.ZERO)==0);
		assertTrue(CVMDouble.ZERO.compareTo(CVMDouble.ONE)==-1);
		assertTrue(CVMDouble.ONE.compareTo(CVMDouble.ZERO)==1);
		assertTrue(CVMDouble.POSITIVE_INFINITY.compareTo(CVMDouble.ZERO)>0);
		assertTrue(CVMDouble.NEGATIVE_INFINITY.compareTo(CVMDouble.ZERO)<0);
		
		// NaN behaves like the biggest possible number in comparison orderings
		assertEquals(-1,CVMDouble.ZERO.compareTo(CVMDouble.NaN));
		assertEquals(-1,CVMDouble.POSITIVE_INFINITY.compareTo(CVMDouble.NaN));
		assertEquals(0,CVMDouble.NaN.compareTo(CVMDouble.NaN));
	}
	
	@Test public void testEquality() {
		ObjectsTest.doEqualityTests(CVMDouble.ONE, CVMDouble.create(1.0));
		ObjectsTest.doEqualityTests(CVMDouble.create(12345.0),CVMDouble.create(12345.0));
		
		
		assertFalse(CVMDouble.NEGATIVE_ZERO.equals(CVMDouble.ZERO));
		assertTrue(CVMDouble.NaN.equals(CVMDouble.NaN));
	}
	
	@Test public void testIntegerCompares() {
		assertTrue(CVMDouble.ZERO.compareTo(CVMLong.ZERO)==0);
		assertTrue(CVMDouble.ZERO.compareTo(CVMLong.ONE)==-1);
		assertTrue(CVMDouble.ONE.compareTo(CVMLong.ZERO)==1);
		assertTrue(CVMDouble.POSITIVE_INFINITY.compareTo(CVMLong.MAX_VALUE)>0);
		assertTrue(CVMDouble.NEGATIVE_INFINITY.compareTo(CVMLong.MIN_VALUE)<0);
	}
}
