package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.exceptions.BadFormatException;
import convex.test.Samples;

public class FormatTest {

	@Test public void testVLCLongLength() throws BadFormatException, BufferUnderflowException {
		ByteBuffer bb=ByteBuffer.allocate(100);
		bb.put(Tag.LONG);
		Format.writeVLCLong(bb, Long.MAX_VALUE);
		
		// must be max long length plus tag
		assertEquals(Format.MAX_VLC_LONG_LENGTH+1,bb.position());
		
		bb.flip();
		Blob b=Blob.fromByteBuffer(bb);
		assertEquals(Long.MAX_VALUE,(long)Format.read(b));
;	}
	
//	@Test public void testBigIntegerRegression() throws BadFormatException {
//		BigInteger expected=BigInteger.valueOf(-4223);
//		assertEquals(expected,Format.read("0adf01"));
//		
//		assertThrows(BadFormatException.class,()->Format.read("0affdf01"));
//	}
//	
//	@Test public void testBigIntegerRegression2() throws BadFormatException {
//		BigInteger b=BigInteger.valueOf(1496216);
//		Blob blob=Format.encodedBlob(b);
//		assertEquals(b,Format.read(blob));
//	}
//	
//	@Test public void testBigIntegerRegression3() throws BadFormatException {
//		Blob blob=Blob.fromHex("0a801d");
//		assertThrows(BadFormatException.class,()->Format.read(blob));
//	}
//	
//	@Test public void testBigDecimalRegression() throws BadFormatException {
//		Blob blob=Blob.fromHex("0e001d");
//		BigDecimal bd=Format.read(blob);
//		assertEquals(BigDecimal.valueOf(29),bd);
//		assertEquals(blob,Format.encodedBlob(bd));
//	}
	
	@Test public void testEmbeddedRegression() throws BadFormatException {
		Keyword k=Keyword.create("foo");
		Blob b=Format.encodedBlob(k);
		Object o=Format.read(b);
		assertEquals(k,o);
		assertTrue(Format.isEmbedded(k));
		Ref<?> r=Ref.create(o);
		assertTrue(r.isDirect());
	}
	
//	@Test public void testEmbeddedBigInteger() throws BadFormatException {
//		BigInteger one=BigInteger.ONE;
//		assertFalse(Format.isEmbedded(one));
//		AVector<BigInteger> v=Vectors.of(BigInteger.ONE,BigInteger.TEN);
//		assertFalse(v.getRef(0).isEmbedded());
//		Blob b=Format.encodedBlob(v);
//		AVector<BigInteger> v2=Format.read(b);
//		assertEquals(v,v2);
//		assertEquals(b,Format.encodedBlob(v2));
//	}
	
	@Test public void testBadFormats() throws BadFormatException {
		// test excess high order bits above the long range
		assertEquals(-3717066608267863778L,(long)Format.read("09ccb594f3d1bde9b21e"));
		assertThrows(BadFormatException.class,()->{
			Format.read("09b3ccb594f3d1bde9b21e");
		});
		
		// test excess high bytes for -1
		assertThrows(BadFormatException.class,()->Format.read("09ffffffffffffffffff7f"));

		// test excess high bytes for negative number
		assertEquals(Long.MIN_VALUE,(long)Format.read("09ff808080808080808000"));
		assertThrows(BadFormatException.class,()->Format.read("09ff80808080808080808000"));

	}
	
	@Test public void testStringRegression() throws BadFormatException {
		String s="��zI�&$\\ž1�����4�E4�a8�#?$wD(�#";
		Blob b=Format.encodedBlob(s);
		String s2=Format.read(b);
		assertEquals(s,s2);
	}
	
	@Test public void testMalformedTreeVectorRegression() {
		String s="810d6e6a57d5fd2cc66653bd4f72bd83580153344f68f5f643d98b2b5776954b8dc52f5a2014d6ccab85b3f83a5b36f92647";
		ByteBuffer bb=ByteBuffer.wrap(Blob.fromHex(s).getInternalArray());
		
		assertThrows(BadFormatException.class,()->{Format.read(bb);});
	}
	
	@Test public void testMalformedStrings() {
		// bad examples constructed using info from https://www.w3.org/2001/06/utf-8-wrong/UTF-8-test.html
		assertThrows(BadFormatException.class,()->Format.read("300180")); // continuation only
		assertThrows(BadFormatException.class,()->Format.read("3001FF")); 
	}
	
	@Test public void testCanonical() {
		assertTrue(Format.isCanonical(Vectors.empty()));
		assertTrue(Format.isCanonical(null));
		assertTrue(Format.isCanonical(1));
		assertTrue(Format.isCanonical(Blob.create(new byte[1000]))); // should be OK
		assertFalse(Format.isCanonical(Blob.create(new byte[10000]))); // too big to be canonical
		
		assertThrows(Error.class,()->Format.isCanonical(new ArrayList<Object>())); // a random class
		assertThrows(Error.class,()->Format.isCanonical(new AtomicLong(10L))); // a random Number subclass
	}
	
	@Test public void testReadBlobData() throws BadFormatException {
		Blob d=Blob.fromHex("cafebabe");
		Blob edData=Format.encodedBlob(d);
		AArrayBlob dd=Format.read(edData);
		assertEquals(d,dd);
		assertSame(edData,dd.getEncoding()); // should re-use encoded data object directly
	}
	
	@Test
	public void testBadMessageTooLong() throws BadFormatException {
		Object o=Samples.FOO;
		Blob data=Format.encodedBlob(o).append(Blob.fromHex("ff")).toBlob();
		assertThrows(BadFormatException.class,()->Format.read(data));
	}
	
	@Test 
	public void testWriteRef() {
		// TODO: consider whether this is valid
		// shouldn't be allowed to write a Ref directly as a top-level message
		// ByteBuffer b=ByteBuffer.allocate(10);
		// assertThrows(IllegalArgumentException.class,()->Format.write(b, Ref.create("foo")));
	}
}
