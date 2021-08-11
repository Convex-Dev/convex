package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.test.Samples;

public class EncodingTest {

	@Test public void testVLCLongLength() throws BadFormatException, BufferUnderflowException {
		ByteBuffer bb=ByteBuffer.allocate(100);
		bb.put(Tag.LONG);
		Format.writeVLCLong(bb, Long.MAX_VALUE);
		
		// must be max long length plus tag
		assertEquals(Format.MAX_VLC_LONG_LENGTH+1,bb.position());
		
		bb.flip();
		Blob b=Blob.fromByteBuffer(bb);
		
		CVMLong max=RT.cvm(Long.MAX_VALUE);
		
		assertEquals(max,Format.read(b));
		
		assertEquals(max.getEncoding(),b);
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
		Ref<?> r=Ref.get(o);
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
		assertEquals(-3717066608267863778L,((CVMLong)Format.read("09ccb594f3d1bde9b21e")).longValue());
		assertThrows(BadFormatException.class,()->{
			Format.read("09b3ccb594f3d1bde9b21e");
		});
		
		// test excess high bytes for -1
		assertThrows(BadFormatException.class,()->Format.read("09ffffffffffffffffff7f"));

		// test excess high bytes for negative number
		assertEquals(RT.cvm(Long.MIN_VALUE),(CVMLong)Format.read("09ff808080808080808000"));
		assertThrows(BadFormatException.class,()->Format.read("09ff80808080808080808000"));

	}
	
	@Test public void testStringRegression() throws BadFormatException {
		StringShort s=StringShort.create("��zI�&$\\ž1�����4�E4�a8�#?$wD(�#");
		Blob b=Format.encodedBlob(s);
		StringShort s2=Format.read(b);
		assertEquals(s,s2);
	}
	
	@Test public void testListRegression() throws BadFormatException {
		MapEntry<ACell,ACell> me=MapEntry.create(Blobs.fromHex("41da2aa427dc50975dd0b077"), RT.cvm(-1449690165L));
		List<ACell> l=List.reverse(me);
		assertEquals(me,l.reverse()); // ensure MapEntry gets converted to canonical vector
		
		Blob b=Format.encodedBlob(l);
		List<ACell> l2=Format.read(b);
		
		assertEquals(l,l2);
	}
	
	@Test public void testMalformedStrings() {
		// bad examples constructed using info from https://www.w3.org/2001/06/utf-8-wrong/UTF-8-test.html
		assertThrows(BadFormatException.class,()->Format.read("300180")); // continuation only
		assertThrows(BadFormatException.class,()->Format.read("3001FF")); 
	}
	
	@Test public void testCanonical() {
		assertTrue(Format.isCanonical(Vectors.empty()));
		assertTrue(Format.isCanonical(null));
		assertTrue(Format.isCanonical(RT.cvm(1)));
		assertTrue(Format.isCanonical(Blob.create(new byte[1000]))); // should be OK
		assertFalse(Blob.create(new byte[10000]).isCanonical()); // too big to be canonical	
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
		ACell o=Samples.FOO;
		Blob data=Format.encodedBlob(o).append(Blob.fromHex("ff")).toBlob();
		assertThrows(BadFormatException.class,()->Format.read(data));
	}
	
	@Test
	public void testMessageLength() throws BadFormatException {
		// empty bytebuffer, therefore no message lengtg
		ByteBuffer bb1=Blob.fromHex("").toByteBuffer();
		assertThrows(IndexOutOfBoundsException.class,()->Format.peekMessageLength(bb1));
		
		// bad first byte! Needs to carry if 0x40 or more
		ByteBuffer bb2=Blob.fromHex("43").toByteBuffer();
		assertThrows(BadFormatException.class,()->Format.peekMessageLength(bb2));
		
		// maximum message length 
		ByteBuffer bb2a=Blob.fromHex("BF7F").toByteBuffer();
		assertEquals(Format.LIMIT_ENCODING_LENGTH,Format.peekMessageLength(bb2a));

		// overflow message length
		Blob overflow=Blob.fromHex("C000");
		ByteBuffer bb2aa=overflow.toByteBuffer();
		assertThrows(BadFormatException.class,()->Format.peekMessageLength(bb2aa));
		
		ByteBuffer bb2b=Blob.fromHex("8043").toByteBuffer();
		assertEquals(67,Format.peekMessageLength(bb2b));

		
		ByteBuffer bb3=Blob.fromHex("FFFF").toByteBuffer();
		assertThrows(BadFormatException.class,()->Format.peekMessageLength(bb3));
	}
	
	@Test 
	public void testHexDigits() {
		byte[] bs=new byte[8];
		
		Blob src=Blob.fromHex("cafebabe");
		Format.writeHexDigits(bs, 2, src, 2, 4);		
		assertEquals(Blobs.fromHex("00000204feba0000"),Blob.wrap(bs));
		
		Format.writeHexDigits(bs, 3, src, 0, 3);
		assertEquals(Blobs.fromHex("0000020003caf000"),Blob.wrap(bs));
	}
	
	@Test 
	public void testWriteRef() {
		// TODO: consider whether this is valid
		// shouldn't be allowed to write a Ref directly as a top-level message
		// ByteBuffer b=ByteBuffer.allocate(10);
		// assertThrows(IllegalArgumentException.class,()->Format.write(b, Ref.create("foo")));
	}
	
	@Test
	public void testMaxLengths() {
		int ME=Format.MAX_EMBEDDED_LENGTH;
		
		Blob maxEmbedded=Blob.create(new byte[ME-3]); // Maximum embedded length
		Blob notEmbedded=Blob.create(new byte[ME-2]); // Non-embedded length
		assertTrue(maxEmbedded.isEmbedded());
		assertFalse(notEmbedded.isEmbedded());
		assertEquals(ME, maxEmbedded.getEncodingLength());
		
		// Maps
		assertEquals(2+16*ME,MapLeaf.MAX_ENCODING_LENGTH);
		assertEquals(4+16*ME,MapTree.MAX_ENCODING_LENGTH);
		assertEquals(Maps.MAX_ENCODING_SIZE,MapTree.MAX_ENCODING_LENGTH);
		
		// Vectors
		assertEquals(1+Format.MAX_VLC_LONG_LENGTH+17*ME,VectorLeaf.MAX_ENCODING_SIZE);
		
		// Blobs
		Blob maxBlob=Blob.create(new byte[Blob.CHUNK_LENGTH]);
		assertEquals(Blob.MAX_ENCODING_LENGTH,maxBlob.getEncodingLength());
		assertEquals(Blob.MAX_ENCODING_LENGTH,Blobs.MAX_ENCODING_LENGTH);
		
		// Address
		Address maxAddress=Address.create(Long.MAX_VALUE);
		assertEquals(1+Format.MAX_VLC_LONG_LENGTH,Address.MAX_ENCODING_LENGTH);
		assertEquals(Address.MAX_ENCODING_LENGTH,maxAddress.getEncodingLength());
	}
}
