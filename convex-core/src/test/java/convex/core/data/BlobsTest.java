package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.crypto.ASignature;
import convex.core.data.impl.ZeroBlob;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
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
		
		assertTrue(Blob.fromHex("65").compareTo(Blob.fromHex("656667")) < 0);
		assertTrue(Blob.fromHex("85").compareTo(Blob.fromHex("858687")) < 0);
		
		// Some special cases
		assertEquals(-1,LongBlob.create(0).compareTo(LongBlob.create(-1))); // Unsigned!
		assertEquals(-1,Address.create(10).compareTo(LongBlob.create(-1))); // Unsigned!
		assertEquals(0,LongBlob.ZERO.compareTo(Address.ZERO)); // Equivalent blob values
	}
	
	@Test
	public void testEqualsCases() {
		// LongBlob is a Blob, but a similar Address isn't
		assertEquals(Blob.fromHex("0000000000000001"),LongBlob.create(1));
		assertNotEquals(Blob.fromHex("0000000000000001"),Address.create(1));
		
		// Two empty Bloblikes are different
		assertNotEquals(Strings.EMPTY,Blob.EMPTY);
		
		assertNotEquals(Address.create(1),Blob.fromHex("0000000000000001"));
	}
	
	@Test 
	public void testRegressions() {
		doBlobTests(Blob.fromHex("52efa4a423395cb4139003ca5ceaceac83164cc063e99c46fd6b1db02458d93d04a628b0e682b74e573d161706420ff7f115"));
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
		assertEquals(4,Blob.fromHex("0123").hexMatch(Blob.fromHex("0123"), 0, 4));
		assertEquals(3,Blob.fromHex("0123").hexMatch(Blob.fromHex("012f"), 0, 4));
		assertEquals(3,Blob.fromHex("ffff0123").hexMatch(Blob.fromHex("ffff012f"), 4, 4));

		assertEquals(0,Blob.fromHex("ffff0123").hexMatch(Blob.fromHex("ffff012f"), 3, 0));
	}
	
	@Test public void testHexDigit() {
		
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
		assertEquals(255L,Blob.fromHex("00ff").longValue());
		assertEquals(-1L,Blob.fromHex("ffffffffffffffff").longValue());
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
	public void testBlobBuilderBytes() {
		BlobBuilder bb=new BlobBuilder();
		bb.append(new byte[0]);
		assertEquals(0,bb.count());
		byte[] bs=new byte[1000];
		for (int i=0; i<bs.length; i++) bs[i]=(byte)i;
		
		for (int i=0; i<100; i++) {
			bb.append(bs);
		}
		ABlob b=bb.toBlob();
		assertEquals(Blob.wrap(bs), b.slice(10000, 11000));
		assertEquals(100000,b.count());
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
		assertEquals("abcde",Strings.create(r.slice(4095,4100)).toString());
		assertEquals("abcde",Strings.create(r.slice(n-5,n)).toString());
		assertEquals(Blob.class,r.slice(0,4096).getClass());
		assertEquals(BlobTree.class,r.slice(0,4097).getClass());
		
		final long fn=bb.count();
		assertThrows(IndexOutOfBoundsException.class,()->bb.slice(-1, fn));
		assertThrows(IndexOutOfBoundsException.class,()->bb.slice(fn, fn+1));
		assertSame(Blobs.empty(),bb.slice(0, 0));
		assertSame(Blobs.empty(),bb.slice(fn, fn));
	}
	
	@Test
	public void testSignature() {
		// Signature is another Blob special case
		ASignature sig=Samples.BAD_SIGNATURE;

		Blob b=sig.toFlatBlob();
		assertEquals(sig,b); // should be same types	
		assertTrue(sig.equalsBytes(b)); // should be same bytes
		assertEquals(b.getEncoding(),sig.getEncoding());
		doBlobTests(sig);
	}
	
	@Test
	public void testLongBlob() {
		LongBlob b = LongBlob.create("cafebabedeadbeef");
		Blob bb = Blob.fromHex("cafebabedeadbeef");
		
		assertEquals(b.getContentHash(),bb.getContentHash()); // same data hash		
		assertEquals(b.longValue(),bb.longValue());
		assertEquals(0xcafebabedeadbeefl,b.longValue());

		assertEquals(10, b.getHexDigit(1)); // 'a'

		for (int i = 0; i < 8; i++) {
			assertEquals(b.byteAt(i), bb.byteAt(i));
		}

		for (int i = 0; i < 16; i++) {
			assertEquals(b.getHexDigit(i), bb.getHexDigit(i));
		}
		
		// assertSame(b.getCanonical(),b.getChunk(0)); // Use canonical Blob as chunk

		assertTrue(bb.equalsBytes(b));
		assertTrue(b.hexEquals(bb, 3, 10));

		assertEquals(16, b.hexMatch(bb));
		assertEquals(10, b.hexMatch(bb.slice(0, 5)));
		assertEquals(8, bb.hexMatch(b.slice(0, 4)));

		ObjectsTest.doEqualityTests(b, bb);

		doLongBlobTests(b.longValue());
	}
	
	@Test
	public void testLongBlobExamples() {
		doLongBlobTests(-1);
		doLongBlobTests(0);
		doLongBlobTests(1);
		doLongBlobTests(555);
		doLongBlobTests(1l+Integer.MAX_VALUE);
		doLongBlobTests(Long.MAX_VALUE);
	}
	
	private void doLongBlobTests(long v) {
		// LongBlobs considered as Blob type
		LongBlob b=LongBlob.create(v);
		Blob fb=b.toFlatBlob();
		assertEquals(v,fb.longAt(0));
		
		assertEquals(fb,b);
		assertEquals(b,fb);
		assertEquals(fb.hashCode(),b.hashCode());
		
		// Test Address conversion
		if (b.longValue()>=0) {
			assertEquals(b.toHexString(),Address.create(b).toHexString());
		} else {
			assertNull(Address.create(b));
		}
		
		doBlobTests(b);
	}

	@Test
	public void testEmptyBlob() throws BadFormatException {
		ABlob e = Blob.EMPTY;
		assertEquals(0L,e.longValue());
		assertSame(e,e.getChunk(0));
		assertSame(e,e.slice(0,0));
		assertSame(e,e.append(e));
		assertSame(e,new BlobBuilder().toBlob());
		
		assertSame(e,Format.read(Format.encodedBlob(e)));	
		assertSame(e,Format.read(e.getEncoding()));
		
		assertSame(e,e.getChunk(0));
		
		assertNull(RT.print(e, 1));
		assertEquals(Strings.HEX_PREFIX,RT.print(e, 2));
		
		doBlobTests(e);
	}
	
	@Test public void testAppend() {
		ABlob tag=Blobs.fromHex("f00f");
		
		Blob b=Blobs.empty().append(tag).toFlatBlob();
		assertEquals(tag,b);
	}
	
	@Test
	public void testBlobSlice() {
		ABlob blob = Blob.fromHex("cafebabedeadbeef").slice(2,6);
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
		
		ABlob slice=two.slice(8,12);
		assertEquals(Blob.fromHex("f00dcafe"),slice);
		doBlobTests(slice);
	}

	@Test
	public void testFullBlob() {
		ABlob b=Samples.FULL_BLOB;
		ABlob two = b.append(b);
		assertEquals(Blob.EMPTY, b.getChunk(1));
		assertEquals(b, two.getChunk(1));
		assertTrue(b.equalsBytes(b));
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
		assertFalse(b.isCompletelyEncoded());
		
		// Last chunk
		assertEquals(Blob.class,b.slice(n-Blob.CHUNK_LENGTH,n).getClass());
		
		doBlobTests(b);
		
		Blob flat=b.toFlatBlob();
		doBlobTests(flat);
	}
	
	@Test
	public void testEncoding() throws BadFormatException {
		assertEquals(Blob.fromHex("3100"),Blobs.empty().getEncoding());
		
		// Bad VLC length
		assertThrows(BadFormatException.class,()->Format.read(Blob.fromHex("318000")));
	}
	
	@Test
	public void testCreate() {
		byte[] bs=new byte[100];
		assertThrows(IllegalArgumentException.class,()->Blob.create(bs,10,-1));
		assertSame(Blobs.empty(),Blob.create(bs, 10, 0));
	}
	
	@Test
	public void testPacked() {
		Blob chunk=Samples.FULL_BLOB;
		assertEquals(Blob.CHUNK_LENGTH,chunk.size());
		assertTrue(chunk.isChunkPacked());
		assertTrue(chunk.isFullyPacked());
		
		ABlob b=chunk.append(chunk);
		assertEquals(2*Blob.CHUNK_LENGTH,b.size());
		assertTrue(b.isChunkPacked());
		assertFalse(b.isFullyPacked());
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
	public void testBigBlob() throws InvalidDataException, BadFormatException, IOException {
		BlobTree bb = Samples.BIG_BLOB_TREE;
		long len = bb.count();

		assertEquals(Samples.BIG_BLOB_LENGTH, len);

		assertSame(bb, bb.slice(0));
		assertSame(bb, bb.slice(0, len));
		
		ABlob bb2=bb.append(bb);
		assertEquals(bb,bb2.slice(len,len*2));

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

		Ref<BlobTree> rb = Cells.persist(bb).getRef();
		BlobTree bbb = Format.read(bb.getEncoding());
		bbb.validate();
		assertEquals(bb, bbb);
		assertEquals(bb, rb.getValue());
		assertEquals(bb.count(), bb.hexMatch(bbb, 0, len));

		doBlobTests(bb);
	}
	
	@Test
	public void testBigBlobReplace() {
		int SIZE=10000;
		ABlob data=Blob.createRandom(new Random(5465), SIZE);

		ABlob dd=data.append(data);
		assertEquals(data,dd.slice(SIZE));
		
		ABlob cc=dd.replaceSlice(SIZE/2, data);
		ABlob data2=cc.slice(SIZE/2, SIZE/2+SIZE);
		assertEquals(data,data2);
	}

	@Test
	public void testBlobTreeOutOfRange() {
		assertThrows(IndexOutOfBoundsException.class, () -> Samples.BIG_BLOB_TREE.byteAt(Samples.BIG_BLOB_LENGTH));
		assertNull(Samples.BIG_BLOB_TREE.slice(-1));
		assertNull(Samples.BIG_BLOB_TREE.slice(1, Samples.BIG_BLOB_LENGTH+1));
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
	public void testZeroBlobs() {
		ZeroBlob z0=ZeroBlob.create(0);
		ZeroBlob z1=ZeroBlob.create(10);
		ZeroBlob z2=ZeroBlob.create(100);
		ZeroBlob z3=ZeroBlob.create(10000);
		ZeroBlob z4=ZeroBlob.create(100000);
		
		assertSame(Blob.EMPTY,z0.toFlatBlob());
		
		assertTrue(z3.append(z4) instanceof ZeroBlob);
		
		doBlobTests(z0);
		doBlobTests(z1);
		doBlobTests(z2);
		doBlobTests(z3);
		doBlobTests(z4);
		
		// non-canonical so should convert to blob tree
		ABlob cz4=z4.getCanonical();
		assertTrue(cz4 instanceof BlobTree);
	}
	
	@Test
	public void testBlobParse() {
		assertNull(Blobs.parse(null));
		assertNull(Blobs.parse("iugiubouib"));
		assertNull(Blobs.parse(Address.ZERO));
		assertNull(Blobs.parse("0x   89"));
		assertNull(Blobs.parse("123"));
		
		assertEquals(Blob.EMPTY,Blobs.parse(""));
		assertEquals(Blob.EMPTY,Blobs.parse(" 0x  "));
		
		String hex="1234cafebabe";
		ABlob b=Blob.fromHex(hex);
		
		assertEquals(b,Blobs.parse(hex));
		assertEquals(b,Blobs.parse(" 0x"+hex+" "));
		assertEquals(b,Blobs.parse(" "+hex+" "));
	}

	@Test
	public void testBlobEncoding() throws BadFormatException {
		byte[] bf = new byte[] { Tag.BLOB, 0 };
		Blob b = Format.read(Blob.wrap(bf));
		assertSame(Blob.EMPTY,b);
	}
	
	/*
	 *  Test case from issue #357, credit @jcoultas
	 */
	@Test
	public void testLongBlobBroken() throws BadFormatException, IOException {
	   ABlob value = Blob.fromHex("f".repeat(8194));  // 4KB + 1 byte
	   assertEquals(value,BlobTree.create(value)); // Check equality with canonical version
	   
	   Ref<ACell> pref = Cells.persist(value).getRef(); // ensure persisted
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
		
		assertThrows(IndexOutOfBoundsException.class,()->a.shortAt(-1));
		
		//  copy of the Blob data
		ABlob b=Blob.wrap(a.getBytes()).toCanonical();
		assertEquals(a.count(),b.count());
		
		BlobBuilder bb=new BlobBuilder(a);

		assertEquals(canonical,b);
		assertEquals(a,bb.toBlob());
		
		doBlobSliceTests(a);
		
		if (n>0) {
			assertEquals(n*2,a.hexMatch(b));
			
			assertEquals(a.slice(n/2,n),b.slice(n/2, n));
			
			assertEquals(a.get(n-1),CVMLong.forByte(a.byteAt(n-1)));
			
			// Reconstruct first and last bytes via hex digits
			assertEquals(a.get(0).longValue(),a.getHexDigit(0)*16+a.getHexDigit(1));
			assertEquals(a.get(n-1).longValue(),a.getHexDigit(n*2-2)*16+a.getHexDigit(n*2-1));
		}
		
		// Test interpretation as a long
		long lv=a.longValue();
		assertEquals(lv,b.longValue());
		
		if (n>=8) {
			LongBlob lb=LongBlob.create(lv);
			assertEquals(lb,a.slice(n-LongBlob.LENGTH,n));
		}

		// Round trip via ByteBuffer should produce a canonical Blob
		ByteBuffer buf=a.getByteBuffer();
		bb.clear();
		bb.append(buf);
		assertTrue(canonical.equalsBytes(bb.toBlob()));
		bb.append(a.getByteBuffer());
		assertEquals(n*2, bb.count());
		ABlob r=bb.toBlob();
		assertEquals(b,r.slice(0,n));
		assertEquals(b,r.slice(n,n*2));
		assertEquals(b.append(b),r);
		
		doBlobLikeTests(a);
		
	}

	public static void doBlobLikeTests(ABlobLike<?> a) {
		long n=a.count();
		ABlob b=a.toBlob();

		if (n>0) {
			assertEquals(a.byteAt(0),b.byteAt(0));
			assertEquals(a.getHexDigit(2*n-1),0xF&a.byteAt(n-1));
		}
		
		if (n>1) {
			assertEquals(a.byteAt(n-1),b.byteAt(n-1));
		}
		
		assertTrue(a.equalsBytes(b.toFlatBlob()));
		
		// Should pass tests for a CVM countable value
		CollectionsTest.doCountableTests(a);
	}

	public static void doBlobSliceTests(ABlob a) {
		long n=a.count();
		// slice tests
		assertEquals(a,a.slice(0));
		assertEquals(a,a.slice(0,n));
		assertSame(a.empty(),a.slice(n));
		
		assertNull(a.slice(0,Long.MAX_VALUE));
		assertNull(a.slice(-1,0));
		assertNull(a.slice(0,Integer.MAX_VALUE+2)); // 0x0100000001
	}
}
