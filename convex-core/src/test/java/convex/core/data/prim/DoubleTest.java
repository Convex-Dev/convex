package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		
		// Canonical NaN encoding has just high 
		assertEquals(Blob.fromHex("1d7ff8000000000000"),nan.getEncoding());
		
		Blob BAD_NAN=Blob.fromHex("1d7ff8000000ffffff");
		
		assertThrows(BadFormatException.class,()->Format.read(BAD_NAN));
	}
	
	@Test public void testCompares() {
		assertTrue(CVMDouble.ZERO.compareTo(CVMDouble.ZERO)==0);
		assertTrue(CVMDouble.ZERO.compareTo(CVMDouble.ONE)==-1);
		assertTrue(CVMDouble.ONE.compareTo(CVMDouble.ZERO)==1);
		assertTrue(CVMDouble.POSITIVE_INFINITY.compareTo(CVMDouble.ZERO)>0);
		assertTrue(CVMDouble.NEGATIVE_INFINITY.compareTo(CVMDouble.ZERO)<0);
	}
	
	@Test public void testEquality() {
		ObjectsTest.doEqualityTests(CVMDouble.ONE, CVMDouble.create(1.0));
		ObjectsTest.doEqualityTests(CVMDouble.create(12345.0),CVMDouble.create(12345.0));
		ObjectsTest.doEqualityTests(CVMDouble.NaN,CVMDouble.create(Double.NaN));
	}
	
	@Test public void testIntegerCompares() {
		assertTrue(CVMDouble.ZERO.compareTo(CVMLong.ZERO)==0);
		assertTrue(CVMDouble.ZERO.compareTo(CVMLong.ONE)==-1);
		assertTrue(CVMDouble.ONE.compareTo(CVMLong.ZERO)==1);
		assertTrue(CVMDouble.POSITIVE_INFINITY.compareTo(CVMLong.MAX_VALUE)>0);
		assertTrue(CVMDouble.NEGATIVE_INFINITY.compareTo(CVMLong.MIN_VALUE)<0);
	}
}
