package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.CVMEncoder;
import convex.core.cvm.CVMTag;
import convex.core.data.prim.AByteFlag;
import convex.core.data.prim.ByteFlag;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;

public class ByteFlagTest {

	@Test 
	public void testAllByteFlags() throws BadFormatException, IOException {
		for (int i=0; i<16; i++) {
			doByteFlagTest(i);
		}
	}
	
	@Test 
	public void testInavlidValues() {
		assertNull(AByteFlag.create(-1));
		assertNull(AByteFlag.create(16));
		assertNull(AByteFlag.create(CVMTag.TRUE)); // we expect 1, not 0xb1 !
		
		// sneaky checks where the low byte is 0
		assertNull(AByteFlag.create(0x100000000l)); 	
		assertNull(AByteFlag.create(Long.MIN_VALUE)); 
	}
	
	@Test public void testBooleans() {
		ByteFlag bf=ByteFlag.create(0);
		ByteFlag bt=ByteFlag.create(1);
		
		assertNotSame(bf,CVMBool.FALSE);
		assertEquals(bf,CVMBool.FALSE);
		assertEquals(CVMBool.FALSE,bf);

		assertEquals(bt,CVMBool.TRUE);
		assertEquals(CVMBool.TRUE,bt);

	}

	private void doByteFlagTest(int i) throws BadFormatException, IOException {
		AByteFlag b=AByteFlag.create(i);
		byte tag=b.getTag();
		
		assertEquals(Tag.BYTE_FLAG_BASE+i,tag);
		assertEquals(i,tag&0x0f); // value is low hex digit of tag
		
		// Test the encoding. Not much to do, but important!
		Blob enc=b.getEncoding();
		assertEquals(1,enc.count());
		assertEquals(tag,enc.byteAt(0));
		
		String rd="#["+enc.toHexString()+"]";
		if (i>=2) {
			// Printing for non-boolean byte flags
			assertEquals(rd,RT.print(b).toString());
		}
		assertSame(b,Reader.read(rd));
		
		// The CVM decoder returns singletons. A cache may instead retain an equal
		// generic CAD3 ByteFlag, including the alternative representation of 0/1.
		assertSame(b,CVMEncoder.INSTANCE.decode(enc));
		Cells.persist(ByteFlag.create(i),Samples.TEST_STORE);
		assertEquals(b,Samples.TEST_STORE.decode(enc));
		
		ObjectsTest.doAnyValueTests(b);
	}
}
