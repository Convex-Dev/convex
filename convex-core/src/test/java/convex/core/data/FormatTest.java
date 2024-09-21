package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class FormatTest {

	@Test public void testVLCEncoding() {
		checkVLCEncoding("00",0);
		checkVLCEncoding("01",1);
		checkVLCEncoding("3f",63);
		checkVLCEncoding("8040",64);
		checkVLCEncoding("80ffffffffffffffff7f",Long.MAX_VALUE);
		checkVLCEncoding("ff808080808080808000",Long.MIN_VALUE);
		
		assertBadVLCEncoding("80ffffffffffffffffff7f"); // too long
		assertBadVLCEncoding("ff80808080808080808000"); // long negative
		
		assertBadVLCEncoding("8000"); // excess leading bytes
		assertBadVLCEncoding("8080"); // no termination
		
		assertEquals(Format.MAX_VLC_LONG_LENGTH,Format.getVLCLength(Long.MAX_VALUE));
		assertEquals(Format.MAX_VLC_LONG_LENGTH,Format.getVLCLength(Long.MIN_VALUE));
	}
	
	private void assertBadVLCEncoding(String hex) {
		Blob b=Blob.fromHex(hex);
		assertThrows(BadFormatException.class,()->{
			long val=Format.readVLCLong(b.getInternalArray(), b.getInternalOffset());
			if (Format.getVLCLength(val)!=b.count()) throw new BadFormatException("Wrong length");
		});
	}

	private void checkVLCEncoding(String hex, long a) {
		byte[] bs=new byte[12];
		int blen=hex.length()/2;
		assertEquals(blen,Format.writeVLCLong(bs, 0, a));
		assertEquals(blen,Format.getVLCLength(a));
		checkStart(hex,bs);
		
		try {
			long b = Format.readVLCLong(bs, 0);
			assertEquals(a,b);
		} catch (BadFormatException e) {
			fail("Unexpected bad encoding exception: "+e);
		}
	}

	private void checkStart(String hex, byte[] bs) {
		Blob b=Blob.fromHex(hex);
		int n=Utils.checkedInt(b.count());
		if (!b.equalsBytes(bs, 0)) {
			fail("Expected "+b.toHexString()+ " but was "+Blob.wrap(bs,0,n));
		}
	}
}
