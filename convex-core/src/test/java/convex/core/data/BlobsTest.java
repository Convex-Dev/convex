package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMByte;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.test.Samples;

public class BlobsTest {
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
	public void testLongBlob() {
		LongBlob b = LongBlob.create("cafebabedeadbeef");
		Blob bb = Blob.fromHex("cafebabedeadbeef");
		
		assertEquals(b.longValue(),bb.longValue());

		assertEquals(10, b.getHexDigit(1)); // 'a'

		for (int i = 0; i < 8; i++) {
			assertEquals(b.byteAt(i), bb.byteAt(i));
		}

		for (int i = 0; i < 16; i++) {
			assertEquals(b.getHexDigit(i), bb.getHexDigit(i));
		}

		assertTrue(bb.hexEquals(b));
		assertTrue(b.hexEquals(bb, 3, 10));

		assertEquals(16, b.commonHexPrefixLength(bb));
		assertEquals(10, b.commonHexPrefixLength(bb.slice(0, 5)));
		assertEquals(8, bb.commonHexPrefixLength(b.slice(0, 4)));
		
		// Longblobs considered as Blob type
		assertEquals(bb, b); 
		assertEquals(b, bb); 
		
		assertEquals(bb, b.toBlob());
		assertEquals(bb.hashCode(), b.hashCode());

		doBlobTests(b);

	}
	
	@Test
	public void testEmptyBlob() {
		ABlob blob = Blob.EMPTY;
		assertEquals(0L,blob.toLong());
		assertSame(blob,blob.getChunk(0));
		assertSame(blob,blob.slice(0,0));
		
		doBlobTests(Blob.EMPTY);
	}
	
	@Test
	public void testBlobSlice() {
		ABlob blob = Blob.fromHex("cafebabedeadbeef").slice(2,4);
		assertEquals(8,blob.hexLength());
		doBlobTests(blob);
	}

	@Test
	public void testFullBlob() {
		ABlob fb2 = Samples.FULL_BLOB.append(Samples.FULL_BLOB);
		assertEquals(Blob.EMPTY, Samples.FULL_BLOB.getChunk(1));
		assertEquals(Samples.FULL_BLOB, fb2.getChunk(1));

		assertTrue(Samples.FULL_BLOB.hexEquals(Samples.FULL_BLOB));

		doBlobTests(Samples.FULL_BLOB);
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

		Blob firstChunk = bb.getChunk(0);
		assertEquals(Blob.CHUNK_LENGTH, firstChunk.count());
		assertEquals(bb.byteAt(0), firstChunk.byteAt(0));

		Blob blob = bb.toBlob();
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
	public void testBlobFormat() throws BadFormatException {
		byte[] bf = new byte[] { Tag.BLOB, 0 };
		Blob b = Format.read(Blob.wrap(bf));
		assertEquals(0, b.count());
		assertNotEquals(b.getHash(), Hash.EMPTY_HASH);
		
		doBlobTests(b);
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
		
		if (a.isRegularBlob()) {
			assertEquals(a.count(),b.count());
			assertEquals(canonical,b);
		}
		
		if (n>0) {
			assertEquals(n*2,a.commonHexPrefixLength(b));
			
			assertEquals(a.slice(n/2,n/2),b.slice(n/2, n/2));
			
			assertEquals(a.get(n-1),CVMByte.create(a.byteAt(n-1)));
		}

		ObjectsTest.doAnyValueTests(canonical);
	}
}
