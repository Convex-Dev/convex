package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Order;
import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.test.Samples;
import convex.test.Testing;

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
		ACell o=Format.read(b);
		assertEquals(k,o);
		assertTrue(Format.isEmbedded(k));
		Ref<?> r=Ref.get(o);
		assertTrue(r.isDirect());
	}
	
	@Test public void testEmbeddedBigInteger() throws BadFormatException {
		CVMBigInteger big=CVMBigInteger.MIN_POSITIVE;
		Blob b=big.getEncoding();
		assertTrue(big.isEmbedded());
		assertEquals(big,Format.read(b));
	}
	
	@Test public void testBadLongFormats() throws BadFormatException {
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
	
	@Test public void testBlobReading() {
		assertThrows(BadFormatException.class, ()->Format.read(Blobs.empty()));
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
		// TODO: confirm we are OK with bad UTF-8 bytes?
		// bad examples constructed using info from https://www.w3.org/2001/06/utf-8-wrong/UTF-8-test.html
		// assertThrows(BadFormatException.class,()->Format.read("300180")); // continuation only
		//assertThrows(BadFormatException.class,()->Format.read("3001FF")); 
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
		Blob data=Format.encodedBlob(o).append(Blob.fromHex("ff")).toFlatBlob();
		assertThrows(BadFormatException.class,()->Format.read(data));
	}
	
	@Test
	public void testMessageLength() throws BadFormatException {
		// empty bytebuffer, therefore no message length -> returns negative
		ByteBuffer bb1=Blob.fromHex("").toByteBuffer();
		assertTrue(0>Format.peekMessageLength(bb1));
		
		// bad first byte! Needs to carry if 0x40 or more
		ByteBuffer bb2=Testing.messageBuffer("43");
		assertThrows(BadFormatException.class,()->Format.peekMessageLength(bb2));
		
		// maximum message length 
		ByteBuffer bb2a=Testing.messageBuffer("BF7F");
		assertEquals(Format.LIMIT_ENCODING_LENGTH,Format.peekMessageLength(bb2a));

		// 2 byte message length with negative sign = BAD
		ByteBuffer bb2aa=Testing.messageBuffer("C000");
		assertThrows(BadFormatException.class,()->Format.peekMessageLength(bb2aa));
		
		ByteBuffer bb2b=Testing.messageBuffer("8101");
		assertEquals(129,Format.peekMessageLength(bb2b));

		ByteBuffer bb3=Testing.messageBuffer("FFFF");
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
		assertEquals(1+Format.MAX_VLC_LONG_LENGTH+17*ME,VectorLeaf.MAX_ENCODING_LENGTH);
		
		// Blobs
		Blob maxBlob=Blob.create(new byte[Blob.CHUNK_LENGTH]);
		assertEquals(Blob.MAX_ENCODING_LENGTH,maxBlob.getEncodingLength());
		assertEquals(Blob.MAX_ENCODING_LENGTH,Blobs.MAX_ENCODING_LENGTH);
		
		// Address
		Address maxAddress=Address.create(Long.MAX_VALUE);
		assertEquals(1+Format.MAX_VLC_LONG_LENGTH,Address.MAX_ENCODING_LENGTH);
		assertEquals(Address.MAX_ENCODING_LENGTH,maxAddress.getEncodingLength());
	}
	
	@Test 
	public void testIllegalEmbedded() throws BadFormatException {
		AVector<?> v=Vectors.of(1);
		assertEquals("80010901",v.getEncoding().toHexString());
		
		ACell s=Samples.NON_EMBEDDED_STRING;
		Blob neb=s.getEncoding();
		assertEquals(s,Format.read(neb)); // valid readable encoding
		assertTrue(neb.count()>Format.MAX_EMBEDDED_LENGTH);
		
		// create encoding with non-embedded child ref
		// This should be invalid, as it should be canonically coded as an indirect ref!
		Blob b=Blob.fromHex("8001"+neb.toHexString());
		
		assertThrows(BadFormatException.class,()->Format.read(b));
	}
	
	@Test public void testDeltaEncoding() throws BadFormatException {
		Blob randBlob=Blob.createRandom(new Random(1234), 2*Format.MAX_EMBEDDED_LENGTH);
		AVector<?> v=Vectors.of(1,randBlob,randBlob);
		
		ArrayList<ACell> novelty=new ArrayList<>();
		ACell.createPersisted(v,r->novelty.add(r.getValue()));
		if (v.isEmbedded()) novelty.add(v);
		
		assertEquals(2,novelty.size());
		
		Blob b=Format.encodeDelta(novelty);
		
		AVector<?> v2 = Format.decodeMultiCell(b);
		assertEquals(v,v2);
		
	}
	
	@Test 
	public void testSignedDataEncoding() throws BadFormatException {
		Blob bigBlob=Blob.createRandom(new Random(123), 10000);
		Invoke trans=Invoke.create(Address.create(607), 6976, Vectors.of(1,bigBlob,2,bigBlob));
		SignedData<ATransaction> strans=Samples.KEY_PAIR.signData(trans);
		assertFalse(strans.isEmbedded());
		AVector<?> v=Vectors.of(strans);
		
		Blob enc=Format.encodeMultiCell(v);
		
		AVector<?> v2=Format.decodeMultiCell(enc);
		assertEquals(v,v2);
		
		SignedData<ATransaction> dtrans = doMultiEncodingTest(strans);
		assertEquals(strans.getValue(),dtrans.getValue());
	}
	
	@SuppressWarnings("unused")
	@Test 
	public void testFailedMissingEncoding() throws BadFormatException {
		ABlob bigBlob=Blob.createRandom(new Random(123), 5000).toCanonical();
		
	}
	
	@Test public void testBeliefEncoding() throws BadFormatException, InvalidDataException {
		AKeyPair kp=Samples.KEY_PAIR;
		Order order=Order.create();
		order.append(kp.signData(Block.create(0, Vectors.empty())));
		order.append(kp.signData(Block.create(2, Vectors.of(kp.signData(Invoke.create(Address.create(123), 0, Constant.create(CVMLong.ONE)))))));
		order.append(kp.signData(Block.create(2, Vectors.empty())));
		Belief b=Belief.create(kp, order);
		
		ArrayList<ACell> novelty=new ArrayList<>();
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell o = r.getValue();
			novelty.add(o);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		b=ACell.createAnnounced(b, noveltyHandler);
		novelty.add(b);
		
		Blob enc=Format.encodeDelta(novelty);
		
		Belief b2=Format.decodeMultiCell(enc);
		assertEquals(Refs.totalRefCount(b),Refs.totalRefCount(b2));
		
		novelty.clear();
		b=ACell.createAnnounced(b, noveltyHandler);
		assertTrue(novelty.isEmpty());
		
	}
	
	@Test public void testMessageEncoding() throws BadFormatException {
		assertNull(Format.decodeMultiCell(Blob.fromHex("00")));
		
		doMultiEncodingTest(CVMLong.ONE);
		doMultiEncodingTest(Samples.NON_EMBEDDED_STRING);
		doMultiEncodingTest(Vectors.of(1,2,3));
		
		// Two non-embedded identical children
		AVector<?> v1=Vectors.of(1,Samples.NON_EMBEDDED_STRING,Samples.NON_EMBEDDED_STRING,Samples.INT_VECTOR_23);
		doMultiEncodingTest(v1);
		
		// Moar layers
		AVector<?> v2=Vectors.of(7,Samples.NON_EMBEDDED_STRING,v1.concat(Samples.INT_VECTOR_23));
		doMultiEncodingTest(v2);
		
		// Mooooar layers
		AVector<?> v3=Vectors.of(13,v2,v1,Samples.KEY_PAIR.signData(v2));
		doMultiEncodingTest(v3);
		
		// Wrap in transaction
		SignedData<ATransaction> st=Samples.KEY_PAIR.signData(Invoke.create(Address.ZERO, 0, v3));
		doMultiEncodingTest(st);
	}
	
	@Test public void testBlobMapEncoding() throws BadFormatException {
		BlobMap<Blob, ACell> bm=BlobMaps.empty();
		
		bm=bm.assoc(Blobs.fromHex(""), CVMLong.create((6785759)));
		bm=bm.assoc(Blobs.fromHex("0a"), CVMLong.create((1678575659)));
		bm=bm.assoc(Blobs.fromHex("0a56"), CVMLong.create((346785759)));
		bm=bm.assoc(Blobs.fromHex("0a79"), CVMLong.create((896785759)));
		
		BlobMap<Blob, ACell> decoded=doMultiEncodingTest(bm);
		assertTrue(decoded.containsKey(Blob.fromHex("0a79")));
	}
	
	@SuppressWarnings("unchecked")
	private <T extends ACell> T doMultiEncodingTest(ACell a) throws BadFormatException {
		long rc=Refs.totalRefCount(a);
		Blob enc=Format.encodeMultiCell(a);
		ACell decoded=Format.decodeMultiCell(enc);
		assertEquals(a,decoded);
		
		assertEquals(rc,Refs.totalRefCount(decoded));
		
		// since this is a full encoding, expect all Refs to be direct
		Refs.visitAllRefs(Ref.get(decoded), r->{
			ACell c=r.getValue();
			int n=c.getRefCount();
			for (int i=0; i<n; i++) {
				if (!c.getRef(i).isDirect()) {
					fail("Unexpected indirect ref");
				}
			}
		});
		return (T) decoded;
	}
	
	@Test public void testBadMessageEncoding() {
		Blob first=Vectors.of(1,2,3).getEncoding();
		
		// Non-embedded child value
		assertThrows(BadFormatException.class,()->Format.decodeMultiCell(first.append(Blob.fromHex("00")).toFlatBlob()));
		
		// illegal child tag
		assertThrows(BadFormatException.class,()->Format.decodeMultiCell(first.append(Blob.fromHex("00FF")).toFlatBlob()));
	}
}
