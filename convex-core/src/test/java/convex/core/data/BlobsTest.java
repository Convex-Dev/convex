package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMByte;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.test.Samples;

public class BlobsTest {
	@Test public void testConstants() {
		assertEquals(4096,Blob.CHUNK_LENGTH);
		assertEquals(Blob.CHUNK_LENGTH,1<<Blobs.CHUNK_SHIFT);
	}
	
	@Test
	public void testCompare() {
		assertTrue(Blob.fromHex("01").compareTo(Blob.fromHex("FF")) < 0);
		assertTrue(Blob.fromHex("40").compareTo(Blob.fromHex("30")) > 0);
		assertTrue(Blob.fromHex("0102").compareTo(Blob.fromHex("0201")) < 0);

		assertTrue(Blob.fromHex("01").compareTo(Blob.fromHex("01")) == 0);
		assertTrue(Blob.fromHex("").compareTo(Blob.fromHex("")) == 0);
	}

	@Test
	public void testNullHash() {
		AArrayBlob d = Blob.create(new byte[] { Tag.NULL });
		assertTrue(d.getContentHash().equals(Hash.NULL_HASH));
	}
	
	@Test public void testBlobWrap() {
		byte[] bs=new byte[] {0,1,2,3,4};
		assertSame(Blob.EMPTY,Blob.wrap(bs,2,0));
		assertEquals(Blobs.fromHex("0001"),Blob.wrap(bs,0,2));
		assertEquals(Blobs.fromHex("0304"),Blob.wrap(bs,3,2));
		
		assertThrows(IndexOutOfBoundsException.class,()->Blob.wrap(bs,-1,0));
		assertThrows(IndexOutOfBoundsException.class,()->Blob.wrap(bs,2,10));
		assertThrows(IndexOutOfBoundsException.class,()->Blob.wrap(bs,5,1));
	}
	
	@Test
	public void testHexEquals() {
		assertTrue(Blob.fromHex("0123").hexEquals(Blob.fromHex("0123"), 0, 4));
		assertTrue(Blob.fromHex("0125").hexEquals(Blob.fromHex("5123"), 1, 2));
		assertTrue(Blob.fromHex("012345").hexEquals(Blob.fromHex("a123"), 2, 2));
		
		// zero length ranges
		assertTrue(Blob.fromHex("0123").hexEquals(Blob.fromHex("4567"), 1, 0));
		assertTrue(Blob.fromHex("0123").hexEquals(Blob.fromHex("4567"), 0, 0));
		assertTrue(Blob.fromHex("0123").hexEquals(Blob.fromHex("4567"), 4, 0));
	}
	
	@Test
	public void testHexMatchLength() {
		assertEquals(4,Blob.fromHex("0123").hexMatchLength(Blob.fromHex("0123"), 0, 4));
		assertEquals(3,Blob.fromHex("0123").hexMatchLength(Blob.fromHex("012f"), 0, 4));
		assertEquals(3,Blob.fromHex("ffff0123").hexMatchLength(Blob.fromHex("ffff012f"), 4, 4));

		assertEquals(0,Blob.fromHex("ffff0123").hexMatchLength(Blob.fromHex("ffff012f"), 3, 0));
	}
	

	@Test
	public void testFromHex() {
		// bad length for blob
		assertNull(Blob.fromHex("2"));
		assertNull(Blob.fromHex("zz"));
	}

	@Test
	public void testBlobTreeConstruction() {
		// Testing construction of BlobTree from Chunks
		Random r = new Random();
		int clen = Blob.CHUNK_LENGTH;
		int hclen = clen / 2;
		Blob a = Blob.createRandom(r, clen);
		Blob b = Blob.createRandom(r, hclen);
		BlobTree bt = BlobTree.create(a, b);

		assertEquals(clen + hclen, bt.count());
		assertEquals(a, bt.getChunk(0));
		assertEquals(b, bt.getChunk(1));

		doBlobTests(bt);
	}

	@Test
	public void testToLong() {
		assertEquals(255L,Blob.fromHex("ff").toLong());
		assertEquals(-1L,Blob.fromHex("ffffffffffffffff").toLong());
	}
	
	@Test
	public void testBlobBuilder() {
		BlobBuilder bb=new BlobBuilder();
		assertEquals(0,bb.count());
		bb.append("a");
		assertEquals(1,bb.count());
		
		bb.append('b');
		assertEquals("ab",bb.getCVMString().toString());
	}
	
	@Test
	public void testBlobBuilderLarge() {
		ABlob src=Strings.create("abcde").toBlob();
		long sn=5;
		long n=0;
		BlobBuilder bb=new BlobBuilder();
		
		for (int i=0; i<12; i++) {
			bb.append(src);
			n+=sn;
			src=src.append(src);
			sn*=2;
		}
		assertEquals(BlobTree.class,src.getClass());
		
		assertEquals(n,bb.count());
		ABlob r=bb.toBlob();
		assertEquals(n,r.count());
		assertEquals("abcde",Strings.create(r.slice(4095,5)).toString());
		assertEquals("abcde",Strings.create(r.slice(n-5,5)).toString());
		assertEquals(Blob.class,r.slice(0,4096).getClass());
		assertEquals(BlobTree.class,r.slice(0,4097).getClass());
	}
	
	@Test
	public void testLongBlob() {
		LongBlob b = LongBlob.create("cafebabedeadbeef");
		Blob bb = Blob.fromHex("cafebabedeadbeef");
		
		assertEquals(b.getContentHash(),bb.getContentHash()); // same data hash
		
		assertEquals(b.longValue(),bb.longValue());

		assertEquals(10, b.getHexDigit(1)); // 'a'

		for (int i = 0; i < 8; i++) {
			assertEquals(b.byteAt(i), bb.byteAt(i));
		}

		for (int i = 0; i < 16; i++) {
			assertEquals(b.getHexDigit(i), bb.getHexDigit(i));
		}
		
		assertSame(b.getCanonical(),b.getChunk(0)); // Use canonical Blob as chunk

		assertTrue(bb.hexEquals(b));
		assertTrue(b.hexEquals(bb, 3, 10));

		assertEquals(16, b.commonHexPrefixLength(bb));
		assertEquals(10, b.commonHexPrefixLength(bb.slice(0, 5)));
		assertEquals(8, bb.commonHexPrefixLength(b.slice(0, 4)));
		
		// LongBlobs considered as Blob type
		assertEquals(bb, b); 
		assertEquals(b, bb); 
		
		assertEquals(bb, b.toFlatBlob());
		assertEquals(bb.hashCode(), b.hashCode());

		doBlobTests(b);

	}
	
	@Test
	public void testEmptyBlob() throws BadFormatException {
		ABlob e = Blob.EMPTY;
		assertEquals(0L,e.toLong());
		assertSame(e,e.getChunk(0));
		assertSame(e,e.slice(0,0));
		assertSame(e,e.append(e));
		assertSame(e,new BlobBuilder().toBlob());
		
		assertSame(e,Format.read(e.getEncoding()));
		
		assertSame(e,e.getChunk(0));
		
		doBlobTests(e);
	}
	
	@Test
	public void testBlobSlice() {
		ABlob blob = Blob.fromHex("cafebabedeadbeef").slice(2,4);
		assertEquals(8,blob.hexLength());
		doBlobTests(blob);
	}
	
	@Test
	public void testBlobAppendSmall() {
		ABlob src = Blob.fromHex("cafebabedeadbeef");
		src=src.append(Blob.fromHex("f00d"));
		assertEquals(10,src.count());
		
		ABlob two=src.append(src);
		assertEquals(20,two.count());
		doBlobTests(two);
		
		ABlob slice=two.slice(8,4);
		assertEquals(Blob.fromHex("f00dcafe"),slice);
		doBlobTests(slice);
	}

	@Test
	public void testFullBlob() {
		ABlob b=Samples.FULL_BLOB;
		ABlob two = b.append(b);
		assertEquals(Blob.EMPTY, b.getChunk(1));
		assertEquals(b, two.getChunk(1));
		assertTrue(b.hexEquals(b));
		assertTrue(two.isCanonical());

		doBlobTests(b);
		doBlobTests(two);
	}
	
	@Test
	public void testFullBlobPlus() {
		ABlob b=Samples.FULL_BLOB_PLUS; // A bit larger than a chunk
		long n=b.count();
		assertTrue(n>Blob.CHUNK_LENGTH);
		assertTrue(b.isCanonical());
		
		assertEquals(Blob.class,b.slice(n-Blob.CHUNK_LENGTH,Blob.CHUNK_LENGTH).getClass());
		
		doBlobTests(b);
		
		Blob flat=b.toFlatBlob();
		doBlobTests(flat);
	}
		
	@Test 
	public void testEncodingSize() {
		int el=(int) Samples.FULL_BLOB.getEncoding().count();
		assertEquals(Blobs.MAX_ENCODING_LENGTH,el);
		assertEquals(Blob.MAX_ENCODING_LENGTH,el);
		
		assertTrue(Samples.MAX_EMBEDDED_BLOB.isEmbedded());
		assertFalse(Samples.FULL_BLOB.isEmbedded());
	}

	@Test
	public void testBigBlob() throws InvalidDataException, BadFormatException {
		BlobTree bb = Samples.BIG_BLOB_TREE;
		long len = bb.count();

		assertEquals(Samples.BIG_BLOB_LENGTH, len);

		assertSame(bb, bb.slice(0));
		assertSame(bb, bb.slice(0, len));
		
		ABlob bb2=bb.append(bb);
		assertEquals(bb,bb2.slice(len,len));

		Blob firstChunk = bb.getChunk(0);
		assertEquals(Blob.CHUNK_LENGTH, firstChunk.count());
		assertEquals(bb.byteAt(0), firstChunk.byteAt(0));

		Blob blob = bb.toFlatBlob();
		assertEquals(bb.count(), blob.count());
		assertEquals(bb.byteAt(len - 1), blob.byteAt(len - 1));
		assertEquals(bb.byteAt(0), blob.byteAt(0));
		assertEquals(bb.getChunk(1), blob.getChunk(1));

		assertEquals(len * 2, bb.commonHexPrefixLength(bb));

		bb.validate();

		Ref<BlobTree> rb = ACell.createPersisted(bb);
		BlobTree bbb = Format.read(bb.getEncoding());
		bbb.validate();
		assertEquals(bb, bbb);
		assertEquals(bb, rb.getValue());
		assertEquals(bb.count(), bb.hexMatchLength(bbb, 0, len));

		doBlobTests(bb);
	}

	@Test
	public void testBlobTreeOutOfRange() {
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.byteAt(Samples.BIG_BLOB_LENGTH));
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.slice(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.slice(1, Samples.BIG_BLOB_LENGTH));
	}

	@Test
	public void testBlobTreeSizing() {
		assertEquals(0,BlobTree.calcChunks(0));
		assertEquals(1,BlobTree.calcChunks(1));
		assertEquals(1,BlobTree.calcChunks(4095));
		assertEquals(1,BlobTree.calcChunks(4096));
		assertEquals(2,BlobTree.calcChunks(4097));
		assertEquals(16,BlobTree.calcChunks(65536));
		assertEquals(17,BlobTree.calcChunks(65537));
		assertEquals(256,BlobTree.calcChunks(1048576));
		assertEquals(257,BlobTree.calcChunks(1048577));
		assertEquals(0x0008000000000000l,BlobTree.calcChunks(Long.MAX_VALUE));

		assertEquals(4096,BlobTree.childSize(4097));
		assertEquals(4096,BlobTree.childSize(65536));
		assertEquals(65536,BlobTree.childSize(65537));
		assertEquals(65536,BlobTree.childSize(1048576));
		assertEquals(1048576,BlobTree.childSize(1048577));
		assertEquals(0x1000000000000000l,BlobTree.childSize(Long.MAX_VALUE));
		
		assertEquals(2,BlobTree.childCount(4097));
		assertEquals(16,BlobTree.childCount(65536));
		assertEquals(2,BlobTree.childCount(65537));
		assertEquals(16,BlobTree.childCount(1048576));
		assertEquals(2,BlobTree.childCount(1048577));
		assertEquals(8,BlobTree.childCount(Long.MAX_VALUE));
	}
	
	@Test
	public void testPrint() {
		assertEquals("0x",Utils.print(Blob.EMPTY));
		assertEquals("0x1337",Utils.print(Blob.fromHex("1337")));
	}

	@Test
	public void testBlobFormat() throws BadFormatException {
		byte[] bf = new byte[] { Tag.BLOB, 0 };
		Blob b = Format.read(Blob.wrap(bf));
		assertEquals(0, b.count());
		assertNotEquals(b.getHash(), Hash.EMPTY_HASH);
		
		doBlobTests(b);
	}
	
	/*
	 *  Test case from issue #357, credit @jcoultas
	 */
	@Test
	public void testLongBlobBroken() throws BadFormatException {
	   Blob value = Blob.fromHex("f".repeat(8194));  // 4KB + 1 byte
	   assertEquals(value,BlobTree.create(value)); // Check equality with canonical version
	   Ref<ACell> pref = ACell.createPersisted(value); // ensure persisted
	   assertEquals(BlobTree.class,pref.getValue().getClass());
	   Blob b = value.getEncoding();
	   ACell o = Format.read(b);

	   assertEquals(RT.getType(value), RT.getType(o));
	   assertEquals(value, o);
	   assertEquals(b, Format.encodedBlob(o));
	   assertEquals(pref.getValue(), o);
	}

	/**
	 * Generic tests for an arbitrary ABlob instance
	 * @param a Any blob to test, might not be canonical
	 */
	public static void doBlobTests(ABlob a) {
		long n = a.count();
		assertTrue(n >= 0L);
		ABlob canonical=a.toCanonical();
		
		//  copy of the Blob data
		ABlob b=Blob.wrap(a.getBytes()).toCanonical();
		assertEquals(a.count(),b.count());
		
		if (a.isRegularBlob()) {
			assertEquals(canonical,b);
		}
		
		BlobBuilder bb=new BlobBuilder(a);
		assertEquals(a,bb.toBlob());
		
		assertSame(a,a.slice(0,n));
		
		if (n>0) {
			assertEquals(n*2,a.commonHexPrefixLength(b));
			
			assertEquals(a.slice(n/2,n/2),b.slice(n/2, n/2));
			
			assertEquals(a.get(n-1),CVMByte.create(a.byteAt(n-1)));
		}

		ObjectsTest.doAnyValueTests(canonical);
		
		// Round trip via ByteBuffer should produce canonical Blob
		ByteBuffer buf=a.getByteBuffer();
		bb.clear();
		bb.append(buf);
		assertEquals(canonical,bb.toBlob());
		bb.append(a.getByteBuffer());
		assertEquals(n*2, bb.count());
		ABlob r=bb.toBlob();
		assertEquals(a,r.slice(0,n));
		assertEquals(a,r.slice(n,n));
		assertEquals(a.append(a),r);
	}
}
