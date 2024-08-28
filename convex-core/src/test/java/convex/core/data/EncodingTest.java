package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import convex.core.data.Refs.RefTreeStats;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.lang.ops.Constant;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.test.Samples;
import convex.test.Testing;
 
public class EncodingTest {

	@Test public void testVLCLongLength() throws BadFormatException, BufferUnderflowException {
		assertEquals(1,Format.getVLCLength(0x0f));
		assertEquals(1,Format.getVLCLength(0x3f));
		assertEquals(2,Format.getVLCLength(0x40)); // roll over at 64
		assertEquals(Format.MAX_VLC_LONG_LENGTH,Format.getVLCLength(Long.MAX_VALUE));
		
		assertEquals(1,Format.getVLCLength(-1)); // small negative numbers in one byte
		assertEquals(1,Format.getVLCLength(-64)); 
		assertEquals(2,Format.getVLCLength(-65)); // roll over at -65

	}
	
	@Test public void testVLCCountLength() throws BadFormatException, BufferUnderflowException {
		assertEquals(1,Format.getVLCCountLength(0x0f));
		assertEquals(1,Format.getVLCCountLength(0x7f));
		assertEquals(2,Format.getVLCCountLength(0x80)); // roll over at 128
		assertEquals(Format.MAX_VLC_COUNT_LENGTH,Format.getVLCCountLength(Long.MAX_VALUE));
		
		assertThrows(IllegalArgumentException.class, ()->Format.getVLCCountLength(-10));
	}
	
	@Test public void testVLCCount() throws BadFormatException {
		doVLCCountTest(0);
		doVLCCountTest(1);
		doVLCCountTest(63);
		doVLCCountTest(64);
		doVLCCountTest(127);
		doVLCCountTest(128);
		doVLCCountTest(255);
		doVLCCountTest(256);
		doVLCCountTest(65535);
		doVLCCountTest(65536);
		doVLCCountTest(56447567);
		doVLCCountTest(Integer.MAX_VALUE);
		doVLCCountTest(Long.MAX_VALUE);
		doVLCLongTest(Long.MIN_VALUE);
	}
	
	byte[] buf=new byte[50];
	private void doVLCCountTest(long x) throws BadFormatException {
		int n=Format.getVLCCountLength(x);
		int offset=(int) (x%10); // variable offset, somewhat pseudorandom
		
		long pos=Format.writeVLCCount(buf, offset, x);
		assertEquals(n+offset,pos);
		
		long r=Format.readVLCCount(buf, offset);
		assertEquals(x,r);
		
		doVLCLongTest(x);
		doVLCLongTest(-x);
	}
	
	private void doVLCLongTest(long x) throws BadFormatException {
		int n=Format.getVLCLength(x);
		int offset=(int) ((x+n)&0x0f); // variable offset, somewhat pseudorandom
		
		long pos=Format.writeVLCLong(buf, offset, x);
		assertEquals(n+offset,pos);
		
		long r=Format.readVLCLong(buf, offset);
		assertEquals(x,r);
	}
	
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
		// test high zero bytes
		assertThrows(BadFormatException.class,()->Format.read("1100"));
		assertThrows(BadFormatException.class,()->Format.read("12007f"));
		
		// Test excess bytes
		assertThrows(BadFormatException.class,()->Format.read("10ff"));
		assertThrows(BadFormatException.class,()->Format.read("11ffff"));
		assertThrows(BadFormatException.class,()->Format.read("18ffffffffffffffffdd"));
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
		// assertEquals(4+16*ME,MapTree.MAX_ENCODING_LENGTH); // TODO: recheck
		assertEquals(Maps.MAX_ENCODING_SIZE,MapTree.MAX_ENCODING_LENGTH);
		
		// Vectors
		assertEquals(1+Format.MAX_VLC_COUNT_LENGTH+17*ME,VectorLeaf.MAX_ENCODING_LENGTH);
		
		// Blobs
		Blob maxBlob=Blob.create(new byte[Blob.CHUNK_LENGTH]);
		assertEquals(Blob.MAX_ENCODING_LENGTH,maxBlob.getEncodingLength());
		assertEquals(Blob.MAX_ENCODING_LENGTH,Blobs.MAX_ENCODING_LENGTH);
		
		// Address
		Address maxAddress=Address.create(Long.MAX_VALUE);
		assertEquals(1+Format.MAX_VLC_COUNT_LENGTH,Address.MAX_ENCODING_LENGTH);
		assertEquals(Address.MAX_ENCODING_LENGTH,maxAddress.getEncodingLength());
	}
	
	@Test 
	public void testIllegalEmbedded() throws BadFormatException {
		AVector<?> v=Vectors.of(1);
		assertEquals("80011101",v.getEncoding().toHexString());
		
		ACell s=Samples.NON_EMBEDDED_STRING;
		Blob neb=s.getEncoding();
		assertEquals(s,Format.read(neb)); // valid readable encoding
		assertTrue(neb.count()>Format.MAX_EMBEDDED_LENGTH);
		
		// create encoding with non-embedded child ref
		// This should be invalid, as it should be canonically coded as an indirect ref!
		Blob b=Blob.fromHex("8001"+neb.toHexString());
		
		assertThrows(BadFormatException.class,()->Format.read(b));
	}
	
	@Test public void testDeltaEncoding() throws BadFormatException, IOException {
		Blob randBlob=Blob.createRandom(new Random(1234), 2*Format.MAX_EMBEDDED_LENGTH);
		AVector<?> v=Vectors.of(1,randBlob,randBlob);
		
		ArrayList<ACell> novelty=new ArrayList<>();
		Cells.announce(v,r->novelty.add(r.getValue()));
		if (v.isEmbedded()) novelty.add(v);
		
		assertEquals(2,novelty.size());
		
		Blob b=Format.encodeDelta(novelty);
		
		AVector<?> v2 = Format.decodeMultiCell(b);
		assertEquals(v,v2);
	}
	
	@Test public void testMultiCellEncoding() throws BadFormatException {
		ArrayList<ACell> al=new ArrayList<ACell>();
		al.add(CVMLong.ONE);
		al.add(Vectors.of(1,2,3));
		al.add(CVMDouble.ZERO);
		
		Blob enc=Format.encodeCells(al);
		
		ACell[] cs=Format.decodeCells(enc);
		assertEquals(CVMLong.ONE,cs[0]);
		assertEquals(CVMDouble.ZERO,cs[2]);
		assertEquals(3,cs.length);
	}
	
	@Test 
	public void testSignedDataEncoding() throws BadFormatException {
		ABlob bigBlob=Blob.createRandom(new Random(123), 10000).toCanonical();
		Invoke trans=Invoke.create(Address.create(607), 6976, Vectors.of(1,bigBlob,2,bigBlob));
		SignedData<ATransaction> strans=Samples.KEY_PAIR.signData(trans);
		assertFalse(strans.isEmbedded());
		AVector<?> v=Vectors.of(strans);
		
		Blob enc=Format.encodeMultiCell(v,true);
		
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
	
	@Test public void testBeliefEncoding() throws BadFormatException, InvalidDataException, IOException {
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
		b=Cells.announce(b, noveltyHandler);
		novelty.add(b);
		
		Blob enc=Format.encodeDelta(novelty);
		
		Belief b2=Format.decodeMultiCell(enc);
		assertEquals(Refs.totalRefCount(b),Refs.totalRefCount(b2));
		
		novelty.clear();
		b=Cells.announce(b, noveltyHandler);
		assertTrue(novelty.isEmpty());
		
	}
	
	@Test public void testMessageEncoding() throws BadFormatException {
		assertNull(Format.decodeMultiCell(Blob.fromHex("00")));
		
		doMultiEncodingTest(CVMLong.ONE);
		doMultiEncodingTest(Samples.NON_EMBEDDED_STRING);
		doMultiEncodingTest(Vectors.of(1,2,3));
		
		// Two non-embedded identical children
		AVector<ACell> v1=Vectors.of(1,Samples.NON_EMBEDDED_STRING,Samples.NON_EMBEDDED_STRING,Samples.INT_VECTOR_23);
		doMultiEncodingTest(v1);
		
		// Moar layers
		AVector<ACell> v2=Vectors.of(7,Samples.NON_EMBEDDED_STRING,v1.concat(Samples.INT_VECTOR_23));
		doMultiEncodingTest(v2);
		
		// Mooooar layers
		AVector<ACell> v3=Vectors.of(13,v2,v1,Samples.KEY_PAIR.signData(v2));
		doMultiEncodingTest(v3);
		
		// Wrap in transaction
		SignedData<ATransaction> st=Samples.KEY_PAIR.signData(Invoke.create(Address.ZERO, 0, v3));
		doMultiEncodingTest(st);
	}
	
	@Test public void testIndexEncoding() throws BadFormatException {
		Index<Blob, ACell> bm=Index.none();
		
		bm=bm.assoc(Blobs.fromHex(""), CVMLong.create((6785759)));
		bm=bm.assoc(Blobs.fromHex("0a"), CVMLong.create((1678575659)));
		bm=bm.assoc(Blobs.fromHex("0a56"), CVMLong.create((346785759)));
		bm=bm.assoc(Blobs.fromHex("0a79"), CVMLong.create((896785759)));
		
		Index<Blob, ACell> decoded=doMultiEncodingTest(bm);
		assertTrue(decoded.containsKey(Blob.fromHex("0a79")));
	}
	
	@SuppressWarnings("unchecked")
	private <T extends ACell> T doMultiEncodingTest(ACell a) throws BadFormatException {
		long rc=Refs.totalRefCount(a);
		Blob enc=Format.encodeMultiCell(a,true);
		ACell decoded=Format.decodeMultiCell(enc);
		assertEquals(a,decoded);
		
		assertEquals(rc,Refs.totalRefCount(decoded));
		
		return (T) decoded;
	}
	
	@Test public void testEncodingSizeAssumptions() {
		// These should be exact
		checkExactCodingSize(Hash.EMPTY_HASH);
		checkExactCodingSize(CVMBool.TRUE);
		checkExactCodingSize(CVMDouble.NaN);
		
		// These should have sufficient size
		checkSufficientCodingSize(Strings.EMPTY);
		checkSufficientCodingSize(Symbols.FOO);
		checkSufficientCodingSize(CVMLong.ZERO);
	}
	
	public static void checkExactCodingSize(ACell c) {
		assertEquals(c.getEncodingLength(),c.estimatedEncodingSize());
		checkCodingSize(c);
	}

	public static void checkSufficientCodingSize(ACell c) {
		assertTrue(c.getEncodingLength()<=c.estimatedEncodingSize());
		checkCodingSize(c);
	}
	
	public static void checkCodingSize(ACell c) {
		int n=c.getEncoding().size();
		assertEquals(n,c.getEncodingLength());		
		assertTrue(Cells.storageSize(c)>=n);
		assertTrue(n<=Format.LIMIT_ENCODING_LENGTH);
	}


	@Test public void testDeltaBeliefEncoding() throws BadFormatException, IOException {
		AKeyPair kp=AKeyPair.createSeeded(101);
		Order o=Order.create();
		o=o.append(kp.signData(Block.create(0, Vectors.of(kp.signData(Invoke.create(Address.ZERO, 1, o))))));
		o=o.append(kp.signData(Block.create(2, Vectors.of(kp.signData(Invoke.create(Address.ZERO, 2, o))))));
		o=o.append(kp.signData(Block.create(200, Vectors.of(kp.signData(Invoke.create(Address.ZERO, 3, Samples.MIN_TREE_STRING))))));
		
		Belief b=Belief.create(kp, o);
		
		b=Cells.persist(b);
		assertTrue(b.getRef().getStatus()==Ref.PERSISTED);
		
		ArrayList<ACell> novelty=new ArrayList<>();
		
		// At this point we know something updated our belief, so we want to rebroadcast
		// belief to network
		Consumer<Ref<ACell>> noveltyHandler = r -> {
			ACell x = r.getValue();
			novelty.add(x);
		};

		// persist the state of the Peer, announcing the new Belief
		// (ensure we can handle missing data requests etc.)
		b=Cells.announce(b, noveltyHandler);
		novelty.add(b);
		Blob enc=Format.encodeDelta(novelty);
		
		// Check decode of full delta
		Belief b2=Format.decodeMultiCell(enc);
		assertEquals(b,b2);
		
		// Check no new novelty if announce again
		novelty.clear();
		b=Cells.announce(b, noveltyHandler);
		assertEquals(0,novelty.size());
		
		// Extend Belief with new Peer Order
		AKeyPair kp2=AKeyPair.createSeeded(156757);
		Order o2=b.getOrder(kp.getAccountKey());
		o2=o2.append(kp2.signData(Block.create(400, Vectors.of(kp.signData(Invoke.create(Address.ZERO, 7, o))))));
		Belief b3=b.withOrders(b.getOrders().assoc(kp2.getAccountKey(), kp2.signData(o2)));
		
		novelty.clear();
		b3=Cells.announce(b3, noveltyHandler);
		assertFalse(novelty.isEmpty());
		novelty.add(b3);
		Blob enc2=Format.encodeDelta(novelty);
		
		// Check decode of full delta
		Belief b4=Format.decodeMultiCell(enc2);
		Refs.RefTreeStats stats2=Refs.getRefTreeStats(b4.getRef());
		assertNotEquals(stats2.total,stats2.direct); // should be some non-direct Refs
	}
	
	@Test public void testBadMessageEncoding() {
		Blob first=Vectors.of(1,2,3).getEncoding();
		
		// Non-embedded child value
		assertThrows(BadFormatException.class,()->Format.decodeMultiCell(first.append(Blob.fromHex("00")).toFlatBlob()));
		
		// illegal child tag
		assertThrows(BadFormatException.class,()->Format.decodeMultiCell(first.append(Blob.fromHex("00FF")).toFlatBlob()));
	}
	
	@Test public void testFullMessageEncoding() throws BadFormatException {
		testFullencoding(Samples.BIG_BLOB_TREE);
		testFullencoding(Samples.INT_SET_300);
		testFullencoding(Samples.INT_LIST_300);
	}

	public static void testFullencoding(ACell s) throws BadFormatException {
		RefTreeStats rstats  = Refs.getRefTreeStats(s.getRef());
		Blob b=Format.encodeMultiCell(s,true);
		
		ACell s2=Format.decodeMultiCell(b);
		// System.err.println(Refs.printMissingTree(s2));
		assertEquals(s,s2);
		
		RefTreeStats rstats2  = Refs.getRefTreeStats(s2.getRef());
		assertEquals(rstats.total,rstats2.total);
	}
}
