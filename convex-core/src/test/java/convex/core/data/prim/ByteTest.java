package convex.core.data.prim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.Hash;
import convex.core.data.ObjectsTest;
import convex.core.lang.RT;

public class ByteTest {

	@Test public void testCache() {
		assertSame(CVMByte.create(1),CVMByte.create(1));
	}
	
	@Test public void testValues() {
		for (int i=0; i<256; i++) {
			CVMByte b=CVMByte.create(i);
			assertSame(b,CVMByte.create((byte)i));
			
			Hash h=b.getHash();
			assertNotNull(h);
			
			// check hash is cached correctly
			assertSame(h,b.getHash());
			
			ObjectsTest.doAnyValueTests(b);
		}
		
	}
	
	@Test public void testCVMCast() {
		// CVM converts all numbers to Long
		assertEquals(RT.cvm(1L),(CVMLong)RT.cvm((byte)1));
	}
}
