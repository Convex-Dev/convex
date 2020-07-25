package convex.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;

public class VLCEncodingTest {

	@Test
	public void testMessageLength() throws BadFormatException {
		ByteBuffer bb = Blob.fromHex("8048").getByteBuffer();
		assertEquals(0, bb.position());
		int len = Format.peekMessageLength(bb);
		assertEquals(72, len);
		assertEquals(2, Format.getVLCLength(len));
	}

	/**
	 * Test the assumption that MAX_MESSAGE_LENGTH is the largest length that can be
	 * VLC encoded in 2 bytes
	 */
	@Test
	public void testVLCLength() throws BadFormatException {
		assertEquals(2, Format.getVLCLength(Format.LIMIT_ENCODING_LENGTH));
		assertEquals(3, Format.getVLCLength(Format.LIMIT_ENCODING_LENGTH + 1));

		ByteBuffer bb = Blob.fromHex("BF7F").getByteBuffer();
		assertEquals(0, bb.position());
		int len = Format.peekMessageLength(bb);
		assertEquals(Format.LIMIT_ENCODING_LENGTH, len);
		assertEquals(2, Format.getVLCLength(len));
	}

	@Test
	public void testLongVLC() {
		// note 09 as tag for long
		assertEquals("0900", Format.encodedString(0L));
		assertEquals("0901", Format.encodedString(1L));
		assertEquals("097f", Format.encodedString(-1L));

		assertEquals("093f", Format.encodedString(63L)); // 6 lowest bits set
		assertEquals("098040", Format.encodedString(64L)); // first overflow to 2 bytes VLC
		assertEquals("098100", Format.encodedString(128L)); // first carry of positive bit

		assertEquals("0941", Format.encodedString(-63L));
		assertEquals("0940", Format.encodedString(-64L)); // sign bit only in 1 byte
		assertEquals("09ff3f", Format.encodedString(-65L)); // sign overflow to 2 bytes VLC
		assertEquals("09ff00", Format.encodedString(-128L));
		assertEquals("09fe7f", Format.encodedString(-129L)); // first negative carry

		assertEquals("0980ffffffffffffffff7f", Format.encodedString(Long.MAX_VALUE));
		assertEquals("09ff808080808080808000", Format.encodedString(Long.MIN_VALUE));
	}

	@Test
	public void testIntVLC() {
		// note 07 as tag for int
		assertEquals("0700", Format.encodedString(0));
		assertEquals("0701", Format.encodedString(1));
		assertEquals("077f", Format.encodedString(-1));

		assertEquals("073f", Format.encodedString(63)); // 6 lowest bits set
		assertEquals("078040", Format.encodedString(64)); // first overflow to 2 bytes VLC
		assertEquals("078100", Format.encodedString(128)); // first carry of positive bit

		assertEquals("0741", Format.encodedString(-63));
		assertEquals("0740", Format.encodedString(-64)); // sign bit only in 1 byte
		assertEquals("07ff3f", Format.encodedString(-65)); // sign overflow to 2 bytes VLC
		assertEquals("07ff00", Format.encodedString(-128));
		assertEquals("07fe7f", Format.encodedString(-129)); // first negative carry

		assertEquals("0787ffffff7f", Format.encodedString(Integer.MAX_VALUE));
		assertEquals("07f880808000", Format.encodedString(Integer.MIN_VALUE));
	}

	@Test
	public void testShortVLC() {
		// note 05 as tag for short
		assertEquals("0500", Format.encodedString((short) 0));
		assertEquals("0501", Format.encodedString((short) 1));
		assertEquals("057f", Format.encodedString((short) -1));

		assertEquals("053f", Format.encodedString((short) 63)); // 6 lowest bits set
		assertEquals("058040", Format.encodedString((short) 64)); // first overflow to 2 bytes VLC
		assertEquals("058100", Format.encodedString((short) 128)); // first carry of positive bit

		assertEquals("0541", Format.encodedString((short) -63));
		assertEquals("0540", Format.encodedString((short) -64)); // sign bit only in 1 byte
		assertEquals("05ff3f", Format.encodedString((short) -65)); // sign overflow to 2 bytes VLC
		assertEquals("05ff00", Format.encodedString((short) -128));
		assertEquals("05fe7f", Format.encodedString((short) -129)); // first negative carry

		assertEquals("0581ff7f", Format.encodedString(Short.MAX_VALUE));
		assertEquals("05fe8000", Format.encodedString(Short.MIN_VALUE));
	}

//  TODO: Currently not allowing BigInteger as valid data object. May reconsider.
//	@Test public void testBigIntegerVLC() {
//		// note 0a as tag for short
//		assertEquals("0a00",Format.encodedString(BigInteger.valueOf(0)));
//		assertEquals("0a01",Format.encodedString(BigInteger.valueOf(1)));
//		assertEquals("0a7f",Format.encodedString(BigInteger.valueOf(-1)));
//
//		assertEquals("0a3f",Format.encodedString(BigInteger.valueOf(63))); // 6 lowest bits set
//		assertEquals("0a8040",Format.encodedString(BigInteger.valueOf(64))); // first overflow to 2 bytes VLC
//		assertEquals("0a8100",Format.encodedString(BigInteger.valueOf(128))); // first carry of positive bit
//
//		assertEquals("0a41",Format.encodedString(BigInteger.valueOf(-63))); 
//		assertEquals("0a40",Format.encodedString(BigInteger.valueOf(-64))); // sign bit only in 1 byte
//		assertEquals("0aff3f",Format.encodedString(BigInteger.valueOf(-65))); // sign overflow to 2 bytes VLC
//		assertEquals("0aff00",Format.encodedString(BigInteger.valueOf(-128))); 
//		assertEquals("0afe7f",Format.encodedString(BigInteger.valueOf(-129))); // first negative carry
//
//		assertEquals("0a80ffffffffffffffff7f",Format.encodedString(BigInteger.valueOf(Long.MAX_VALUE))); 
//		assertEquals("0aff808080808080808000",Format.encodedString(BigInteger.valueOf(Long.MIN_VALUE))); 
//
//		BigInteger b1=BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(128));
//		assertEquals("0a80ffffffffffffffffff00",Format.encodedString(b1)); 
//		
//		BigInteger b2=BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.valueOf(128));
//		assertEquals("0aff80808080808080808000",Format.encodedString(b2)); 
//	}

	@Test
	public void testLongVLCBadFormat() {
		// too long encodings
		assertThrows(BadFormatException.class, () -> Format.read(Blob.fromHex("098000")));
		assertThrows(BadFormatException.class, () -> Format.read(Blob.fromHex("09FF7F")));
	}

	@Test
	public void testVLCSignExtend() {
		assertEquals(0L, Format.vlcSignExtend((byte) 0x00));
		assertEquals(-64L, Format.vlcSignExtend((byte) 0x40));
		assertEquals(-1L, Format.vlcSignExtend((byte) 0xFF));
		assertEquals(63, Format.vlcSignExtend((byte) 0x3F));
		assertEquals(0L, Format.vlcSignExtend((byte) 0x80));
	}

//	@Test public void testBigIntegerVLCRegression() throws BadFormatException {
//		BigInteger b1=new BigInteger("-10826789006513807832719466915686947597958886414817953");
//		String b1s=b1.toString(16);
//		String encodedString=Format.encodedString(b1);
//		BigInteger b2=Format.read(Blob.fromHex(encodedString));
//		String b2s=b2.toString(16);
//		assertEquals(b1s,b2s);
//		assertEquals(b1,b2);
//	} 

//	@Test public void testLongVLCRegression() throws BadFormatException {
//		long longVal=-221195466131295L;
//		BigInteger b1=BigInteger.valueOf(longVal);
//		String encodedString=Format.encodedString(b1);
//		String longEncodedString=Format.encodedString(longVal);
//		assertEquals(longEncodedString.substring(2),encodedString.substring(2)); // should be equal after tag
//		BigInteger b2=Format.read(Blob.fromHex(encodedString));
//		assertEquals(b1,b2);
//	} 

	@Test
	public void testLongVLCRegression2() throws BadFormatException {
		long b = 1496216L;
		Blob blob = Format.encodedBlob(b);
		assertEquals(b, (long) Format.read(blob));
	}

	@Test
	public void testIntVLCRegression() throws BadFormatException {
		int b = 1234578;
		Blob blob = Format.encodedBlob(b);
		assertEquals(b, (int) Format.read(blob));
	}
}
