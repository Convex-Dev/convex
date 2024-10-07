package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

/**
 * Tests for the Format utility class
 */
public class FormatTest {

	@Test public void testVLQLongs() {
		checkVLQLong("00",0);
		checkVLQLong("01",1);
		checkVLQLong("3f",63);
		checkVLQLong("8040",64); // first overflow into 2 bytes
		
		checkVLQLong("7f",-1);
		checkVLQLong("40",-64);
		checkVLQLong("ff3f",-65);
		checkVLQLong("ff00",-128);
		checkVLQLong("fe7f",-129);

		checkVLQLong("87ffffff7f",Integer.MAX_VALUE);
		checkVLQLong("8880808000",Integer.MAX_VALUE+1l);
		checkVLQLong("80ffffffffffffffff7f",Long.MAX_VALUE);
		checkVLQLong("ff808080808080808000",Long.MIN_VALUE);
		
		assertBadVLQLong("80ffff00ffffffffff7f"); // termination in middle
		assertBadVLQLong("80ffffffffffffffffff7f"); // too long
		assertBadVLQLong("ff80808080808080808000"); // long negative
		assertBadVLQLong("017f"); // terminated by first byte
		
		assertBadVLQLong("8000"); // excess leading bytes
		assertBadVLQLong("8080"); // no termination
		
		assertEquals(Format.MAX_VLQ_LONG_LENGTH,Format.getVLQLongLength(Long.MAX_VALUE));
		assertEquals(Format.MAX_VLQ_LONG_LENGTH,Format.getVLQLongLength(Long.MIN_VALUE));
		
		assertThrows(ArrayIndexOutOfBoundsException.class,()->Format.readVLQLong(new byte[0], 0));
	}
	
	@Test public void testVLQCounts() {
		checkVLQCount("00",0);
		checkVLQCount("01",1);
		checkVLQCount("3f",63);
		checkVLQCount("40",64);
		checkVLQCount("7f",127);
		checkVLQCount("8100",128); // first overflow into 2 bytes
		checkVLQCount("8480808000",1024l*1024l*1024l); // 1gb example
		checkVLQCount("9080808000",4*1024l*1024l*1024l); // 4gb example
		checkVLQCount("87ffffff7f",Integer.MAX_VALUE);
		checkVLQCount("8880808000",Integer.MAX_VALUE+1l); // a pretty number, 2^32
		checkVLQCount("ffffffffffffffff7f",Long.MAX_VALUE); // all 63 bits set!

		assertBadVLQCount("ffffffffffffffffff"); // not terminated max value
		assertBadVLQCount("81ffffffffffffffffff"); // excess leading 1 (would overflow)
		assertBadVLQCount("80ffffffffffffffffff7f"); // excess byte
		assertBadVLQCount("ffff00ffffffffff7f"); // terminating byte in middle
		assertBadVLQCount("8000"); // Excess leading zeros
		assertBadVLQCount("80808000"); // Excess leading zeros
		assertBadVLQCount("ff"); // Not terminated
		assertBadVLQCount("ffff"); // Not terminated
		assertBadVLQCount("01ff"); // terminated by first byte

		assertThrows(ArrayIndexOutOfBoundsException.class,()->Format.readVLQCount(new byte[0], 0));
		
		assertEquals(Format.MAX_VLQ_COUNT_LENGTH,Format.getVLQCountLength(Long.MAX_VALUE));
	}
	
	private void assertBadVLQLong(String hex) {
		Blob b=Blob.fromHex(hex);
		assertThrows(BadFormatException.class,()->{
			long val=Format.readVLQLong(b.getInternalArray(), b.getInternalOffset());
			if (Format.getVLQLongLength(val)!=b.count()) throw new BadFormatException("Wrong length");
		});
	}
	
	private void assertBadVLQCount(String hex) {
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

	private void checkVLQLong(String hex, long a) {
		int blen=hex.length()/2;
		byte[] bs=new byte[blen];
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
	
	private void checkVLQCount(String hex, long a) {
		int blen=hex.length()/2;
		byte[] bs=new byte[blen];
		assertEquals(blen,Format.writeVLQCount(bs, 0, a));
		assertEquals(blen,Format.getVLQCountLength(a));
		checkStart(hex,bs);
		
		try {
			long b = Format.readVLQCount(bs, 0);
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
