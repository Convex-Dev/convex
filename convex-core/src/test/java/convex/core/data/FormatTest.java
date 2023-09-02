package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import convex.core.util.Utils;

public class FormatTest {

	@Test public void testVLCEncoding() {
		byte[] bs=new byte[12];
		
		// number 1
		assertEquals(1,Format.writeVLCLong(bs, 0, 1));
		checkStart("01",bs);
		
		// number 63
		assertEquals(1,Format.writeVLCLong(bs, 0, 63));
		checkStart("3f",bs);
		
		// number 64
		assertEquals(2,Format.writeVLCLong(bs, 0, 64));
		checkStart("8040",bs);
	}

	private void checkStart(String hex, byte[] bs) {
		Blob b=Blob.fromHex(hex);
		int n=Utils.checkedInt(b.count());
		if (!b.equalsBytes(bs, 0)) {
			fail("Expected "+b.toHexString()+ " but was "+Blob.wrap(bs,0,n));
		}
	}
}
