package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;

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
		
		assertEquals(Format.MAX_VLQ_LONG_LENGTH,Format.getVLQLongLength(Long.MAX_VALUE));
		assertEquals(Format.MAX_VLQ_LONG_LENGTH,Format.getVLQLongLength(Long.MIN_VALUE));
	}
	
	private void assertBadVLCEncoding(String hex) {
		Blob b=Blob.fromHex(hex);
		assertThrows(BadFormatException.class,()->{
			long val=Format.readVLQLong(b.getInternalArray(), b.getInternalOffset());
			if (Format.getVLQLongLength(val)!=b.count()) throw new BadFormatException("Wrong length");
		});
	}
	
	@Test public void testBigCount() throws BadFormatException {
		int c = Integer.MAX_VALUE;
		int n=Format.getVLQCountLength(c);
		assertEquals(5,n);
		ByteBuffer bb=ByteBuffer.allocate(n);
		Format.writeVLQCount(bb, c);
		bb.flip();
		assertEquals(c,Format.peekMessageLength(bb));
		Blob b=Blob.fromByteBuffer(bb);
		assertEquals(n,b.count());
	}

	private void checkVLCEncoding(String hex, long a) {
		byte[] bs=new byte[12];
		int blen=hex.length()/2;
		assertEquals(blen,Format.writeVLQLong(bs, 0, a));
		assertEquals(blen,Format.getVLQLongLength(a));
		checkStart(hex,bs);
		
		try {
			long b = Format.readVLQLong(bs, 0);
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
