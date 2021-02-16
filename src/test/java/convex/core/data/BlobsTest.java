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

import convex.core.crypto.Hash;
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

		assertEquals(clen + hclen, bt.length());
		assertEquals(a, bt.getChunk(0));
		assertEquals(b, bt.getChunk(1));

		doBlobTests(bt);

	}

	@Test
	public void testLongBlob() {
		LongBlob b = LongBlob.create("cafebabedeadbeef");
		Blob bb = Blob.fromHex("cafebabedeadbeef");

		assertEquals(10, b.getHexDigit(1)); // 'a'

		for (int i = 0; i < 8; i++) {
			assertEquals(b.get(i), bb.get(i));
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
	public void testFullBlob() {
		ABlob fb2 = Samples.FULL_BLOB.append(Samples.FULL_BLOB);
		assertEquals(Blob.EMPTY, Samples.FULL_BLOB.getChunk(1));
		assertEquals(Samples.FULL_BLOB, fb2.getChunk(1));

		assertTrue(Samples.FULL_BLOB.hexEquals(Samples.FULL_BLOB));

		doBlobTests(Samples.FULL_BLOB);
	}
		
	@Test 
	public void testEncodingSize() {
		int el=(int) Samples.FULL_BLOB.getEncoding().length();
		assertEquals(Blobs.MAX_ENCODING_LENGTH,el);
		assertEquals(Blob.MAX_ENCODING_LENGTH,el);
		
		assertTrue(Samples.MAX_EMBEDDED_BLOB.isEmbedded());
		assertFalse(Samples.FULL_BLOB.isEmbedded());
	}

	@Test
	public void testBigBlob() throws InvalidDataException, BadFormatException {
		BlobTree bb = Samples.BIG_BLOB_TREE;
		long len = bb.length();

		assertEquals(Samples.BIG_BLOB_LENGTH, len);

		assertSame(bb, bb.slice(0));
		assertSame(bb, bb.slice(0, len));

		Blob firstChunk = bb.getChunk(0);
		assertEquals(Blob.CHUNK_LENGTH, firstChunk.length());
		assertEquals(bb.get(0), firstChunk.get(0));

		Blob blob = bb.toBlob();
		assertEquals(bb.length(), blob.length());
		assertEquals(bb.get(len - 1), blob.get(len - 1));
		assertEquals(bb.get(0), blob.get(0));
		assertEquals(bb.getChunk(1), blob.getChunk(1));

		assertEquals(len * 2, bb.commonHexPrefixLength(bb));

		bb.validate();

		Ref<BlobTree> rb = Ref.createPersisted(bb);
		BlobTree bbb = Format.read(bb.getEncoding());
		bbb.validate();
		assertEquals(bb, bbb);
		assertEquals(bb, rb.getValue());
		assertEquals(bb.length(), bb.hexMatchLength(bbb, 0, len));

		doBlobTests(bb);
	}

	@Test
	public void testBlobTreeOutOfRange() {
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.get(Samples.BIG_BLOB_LENGTH));
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.slice(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.slice(1, Samples.BIG_BLOB_LENGTH));
	}

	@Test
	public void testBlobFormat() throws BadFormatException {
		byte[] bf = new byte[] { Tag.BLOB, 0 };
		Blob b = Format.read(Blob.wrap(bf));
		assertEquals(0, b.length());
		assertNotEquals(b.getHash(), Hash.EMPTY_HASH);
	}

	public static void doBlobTests(ABlob a) {
		long n = a.length();
		assertTrue(n >= 0L);

		ObjectsTest.doCellTests(a);
	}
}
