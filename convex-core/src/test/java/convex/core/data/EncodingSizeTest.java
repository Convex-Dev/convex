package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import convex.test.Samples;

/**
 * Test class for maximum encoding sizes. We construct and verify the maximum
 * encoding size for each type.
 */
public class EncodingSizeTest {
	public static int size(ACell a) {
		return Utils.checkedInt(Format.encodedBlob(a).count());
	}
	
	@Test public void testLong() {
		CVMLong a=CVMLong.create(Long.MAX_VALUE);
		CVMLong b=CVMLong.create(Long.MAX_VALUE);
		assertEquals(CVMLong.MAX_ENCODING_LENGTH,size(a));
		assertEquals(CVMLong.MAX_ENCODING_LENGTH,size(b));
		assertEquals(1+Format.MAX_VLC_LONG_LENGTH,CVMLong.MAX_ENCODING_LENGTH);
	}
	
	@Test public void testByte() {
		CVMByte a=CVMByte.create(0);
		CVMByte b=CVMByte.create(255);
		assertEquals(CVMByte.MAX_ENCODING_LENGTH,size(a));
		assertEquals(CVMByte.MAX_ENCODING_LENGTH,size(b));
	}
	
	@Test public void testNull() {
		assertEquals(1,size(null));
	}
	
	@Test public void testBoolean() {
		assertEquals(CVMBool.MAX_ENCODING_LENGTH,size(CVMBool.TRUE));
		assertEquals(CVMBool.MAX_ENCODING_LENGTH,size(CVMBool.FALSE));
	}
	
	@Test public void testDouble() {
		assertEquals(CVMDouble.MAX_ENCODING_LENGTH,size(CVMDouble.ZERO));
		assertEquals(CVMDouble.MAX_ENCODING_LENGTH,size(CVMDouble.NaN));
	}
	
	@Test public void testBlob() {
		assertEquals(Blob.MAX_ENCODING_LENGTH,size(Samples.FULL_BLOB));
		assertEquals(Format.MAX_EMBEDDED_LENGTH,Samples.MAX_EMBEDDED_BLOB.getEncodingLength());
	}
	
	@Test public void testLongBlob() {
		assertEquals(LongBlob.MAX_ENCODING_LENGTH,size(LongBlob.create(Long.MAX_VALUE)));
	}
	
	@Test public void testBlobTree() {
		long n =0x0f00000000000000l+(Format.MAX_EMBEDDED_LENGTH-3);
		assertEquals(Format.MAX_VLC_LONG_LENGTH-1,Format.getVLCLength(n)); // can't be max count?
		BlobTree a=(BlobTree) Blobs.createFilled(3, n);
		assertEquals(BlobTree.MAX_ENCODING_SIZE,size(a));
	}
}
