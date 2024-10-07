package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.AByteFlag;
import convex.core.exceptions.BadFormatException;

public class ByteFlagTest {

	@Test 
	public void testAllByteFlags() throws BadFormatException {
		for (int i=0; i<16; i++) {
			doByteFlagTest(i);
		}
	}
	
	@Test 
	public void testInavlidValues() {
		assertNull(AByteFlag.create(-1));
		assertNull(AByteFlag.create(16));
		assertNull(AByteFlag.create(Tag.TRUE)); // we expect 1, not 0xb1 !
		
		// sneaky checks where the low byte is 0
		assertNull(AByteFlag.create(0x100000000l)); 	
		assertNull(AByteFlag.create(Long.MIN_VALUE)); 
	}

	private void doByteFlagTest(int i) throws BadFormatException {
		AByteFlag b=AByteFlag.create(i);
		byte tag=b.getTag();
		
		assertEquals(Tag.BYTE_FLAG_BASE+i,tag);
		assertEquals(i,tag&0x0f); // value is low hex digit of tag
		
		// Test the encoding. Not much to do, but important!
		Blob enc=b.getEncoding();
		assertEquals(1,enc.count());
		assertEquals(tag,enc.byteAt(0));
		
		// should all be singletons!
		assertSame(b,Format.read(enc));
		
		ObjectsTest.doAnyValueTests(b);
	}
}
