package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ObjectsTest;
import convex.core.lang.RT;

public class ByteTest {

	@Test public void testCache() {
		assertSame(CVMByte.create(1),CVMByte.create(1));
	}
	
	@Test public void testAllValues() {
		// We might as well exhaustively test all byte values, since there are not too many
		for (int i=0; i<256; i++) {
			CVMByte b=CVMByte.create(i);
			doByteTest(b);
		}
	}
	
	@Test public void testCVMCast() {
		// CVM converts all Java integers to Long, including bytes
		assertEquals(RT.cvm(1L),(CVMLong)RT.cvm((byte)1));
	}
	
	public void doByteTest(CVMByte a) {
		long val=a.longValue();
		assertTrue((val>=0)&&(val<=255)); // Long value should be in 0..255 range
		assertEquals(CVMByte.create(val),a);
		assertSame(a,CVMByte.create((byte)val));
		
		assertTrue(a.isCanonical());
		assertTrue(a.isEmbedded());
		assertTrue(a.isCVMValue());
		
		doMathsPropertyTest(a);
		
		ObjectsTest.doAnyValueTests(a);
	}

	private void doMathsPropertyTest(CVMByte a) {
		assertSame(a.signum(),RT.signum(RT.castLong(a)));
	}
}
