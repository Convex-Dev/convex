package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Ops;
import convex.core.cvm.transactions.Call;
import convex.core.cvm.transactions.Transfer;
import convex.core.data.impl.DummyCell;
import convex.core.data.prim.ByteFlagExtended;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.test.Samples;

/**
 * Tests for adversarial data, i.e. data that should b=not be accepted by correct peers / clients
 */
public class AdversarialDataTest {

	// A value that is valid CAD3, but not a first class CVM value
	public static final ACell NON_CVM=ByteFlagExtended.create(15);
	
	// A value that is non-canonical but otherwise valid CVM value
	public static final Blob NON_CANONICAL=Blob.createRandom(new Random(), Blob.CHUNK_LENGTH+1);

	// A value that is invalid 
	public static final SetLeaf<CVMLong> NON_VALID=SetLeaf.unsafeCreate(new CVMLong[0]);

	// A value that is illegal in any encoding
	@SuppressWarnings("exports")
	public static final DummyCell NON_LEGAL=new DummyCell();

	
	@Test public void testAssumptions() {
		assertFalse(NON_CVM.isCVMValue());
		assertFalse(NON_LEGAL.isCVMValue());
		assertFalse(NON_CANONICAL.isCanonical());
		assertThrows(InvalidDataException.class, ()->NON_VALID.validate());
	} 
	
	@Test public void testBadVectors() {
		invalidTest(VectorTree.unsafeCreate(0)); // nothing in VectorTree
		invalidTest(VectorTree.unsafeCreate(16,Samples.INT_VECTOR_16)); // single child
		invalidTest(VectorTree.unsafeCreate(26, Samples.INT_VECTOR_16,Samples.INT_VECTOR_10)); // too short VectorTree
		invalidTest(VectorTree.unsafeCreate(33, Samples.INT_VECTOR_16,Samples.INT_VECTOR_16)); // Bad count
		invalidTest(VectorTree.unsafeCreate(29, Samples.INT_VECTOR_16,Samples.INT_VECTOR_16)); // Bad count
		invalidTest(VectorTree.unsafeCreate(42, Samples.INT_VECTOR_16,Samples.INT_VECTOR_10,Samples.INT_VECTOR_16)); // Bad child count
		invalidTest(VectorTree.unsafeCreate(42, Samples.INT_VECTOR_16,Samples.INT_VECTOR_16,Samples.INT_VECTOR_10)); // Non-packed final child
		invalidTest(VectorTree.unsafeCreate(316, Samples.INT_VECTOR_16,Samples.INT_VECTOR_300)); // Bad tailing vector
		
		invalidEncoding(Tag.VECTOR,"01"); // no data
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testBadMapLeafs() {
		MapEntry<CVMLong,CVMLong> a=MapEntry.of(1, 2);
		MapEntry<CVMLong,CVMLong> b=MapEntry.of(3, 4);
		if (a.getKeyHash().compareTo(b.getKeyHash())>0) {
			MapEntry<CVMLong,CVMLong> t=a; a=b; b=t; // swap so a, b are in hash order
		}
		invalidTest(MapLeaf.unsafeCreate(a,a)); // Duplicate key
		invalidTest(MapLeaf.unsafeCreate(a,b,a)); // Duplicate key not in order
		invalidTest(MapLeaf.unsafeCreate(b,a)); // Bad order
		
		// Too many map entries for a MapLeaf
		MapEntry<CVMLong,CVMLong>[] mes=new MapEntry[MapLeaf.MAX_ENTRIES+1];
		for (int i=0; i<mes.length; i++) {
			mes[i]=MapEntry.of(i, i);
		}
		Arrays.sort(mes, (x,y)->x.getKeyHash().compareTo(y.getKeyHash()));
		invalidTest(MapLeaf.unsafeCreate(mes));
		
		invalidTest(MapLeaf.unsafeCreate(new MapEntry[0]));
	}
	
	@Test public void testBadAddress() {
		invalidTest(Address.unsafeCreate(-1));
		invalidTest(Address.unsafeCreate(Long.MIN_VALUE));
	}
	
	@SuppressWarnings({ "unchecked", "null" })
	@Test public void testBadSetTree() {
		SetTree<CVMLong> a = Samples.INT_SET_300;
		
		invalidTest(a.include(NON_VALID));
		
		// Get a SetTree child, must be at least one by PigeonHole Principle
		int d=0;
		SetTree<CVMLong> b=null;
		int rc=a.getRefCount();
		for (int i=0; i<rc; i++) {
			ASet<CVMLong> ch=(ASet<CVMLong>) a.getRef(i).getValue();
			if (ch instanceof SetTree) {
				b=(SetTree<CVMLong>) ch;
				d=SetTree.digitForIndex(i, a.getMask());
			}
		}
		assertEquals(d,b.get(0).getHash().getHexDigit(0)); // d should be first digit of Hash
		assertEquals(1,b.shift);
	}
	
	@Test public void testBadSetLeafs() {
		CVMLong a=CVMLong.ZERO;
		CVMLong b=CVMLong.ONE;
		if (a.getHash().compareTo(b.getHash())>0) {
			CVMLong t=a; a=b; b=t; // swap so a, b are in hash order
		}
		invalidTest(SetLeaf.unsafeCreate(a,a)); // Duplicate element
		invalidTest(SetLeaf.unsafeCreate(a,b,a)); // Duplicate elements not in order
		invalidTest(SetLeaf.unsafeCreate(b,a)); // Bad order
		
		// Simulate too many map entries for a MapLeaf
		CVMLong[] mes=new CVMLong[SetLeaf.MAX_ELEMENTS+1];
		for (int i=0; i<mes.length; i++) {
			mes[i]=CVMLong.create(i);
		}
		Arrays.sort(mes, (x,y)->x.getHash().compareTo(y.getHash())); // put in right order
		invalidTest(SetLeaf.unsafeCreate(mes));

		// Not valid because an empty SetLeaf must be the Singleton empty set.
		invalidTest(SetLeaf.unsafeCreate(new ACell[0]));
		
		// Basic sets for invalid set values
		invalidTest(Sets.of(NON_VALID));
		
		// Inserting non-CVM values into existing valid sets
		invalidTest(Sets.of(1,2,3,4).include(NON_CVM));
		invalidTest(Samples.LONG_SET_100.conj(NON_CVM));
	}
	
	@Test public void testBadKeywords() {
		invalidTest(Keyword.unsafeCreate((StringShort)null));
		invalidTest(Keyword.unsafeCreate(""));
		invalidTest(Keyword.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
		invalidTest(Keyword.unsafeCreate(Samples.MAX_SHORT_STRING));
		
		invalidEncoding(Tag.KEYWORD,"00");
		invalidEncoding(Tag.KEYWORD,"0120ff");
		
		{
			byte[] bs=new byte[256];
			bs[0]=Tag.KEYWORD;
			bs[1]=(byte)160;
			assertThrows(BadFormatException.class,()->Keyword.read(Blob.wrap(bs),0));
		}
	}
	
	@Test public void testBadConstant() {
		invalidEncoding(Tag.OP+Ops.CONSTANT,"");
	}
	
	@Test public void testBadSymbols() {
		invalidTest(Symbol.unsafeCreate((StringShort)null));
		invalidTest(Symbol.unsafeCreate(""));
		invalidTest(Symbol.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
		invalidTest(Symbol.unsafeCreate(Samples.MAX_SHORT_STRING));
		
		invalidEncoding(Tag.SYMBOL,"00");
	}
	
	@Test
	public void testBadReceipt() {
		invalidEncoding(Tag.RECEIPT,"00ff"); // too many bytes
		invalidEncoding(Tag.RECEIPT,""); // too few bytes
		invalidEncoding(Tag.RECEIPT+Tag.RECEIPT_LOG_MASK,"0000"); // null log when should be present
		invalidEncoding(Tag.RECEIPT+Tag.RECEIPT_LOG_MASK,"00b0"); // log is not a vector

	}
	
	@Test
	public void testDummy() {
		invalidTest(Cells.DUMMY);
		// TODO: full validation : invalidTest(Vectors.repeat(Cells.DUMMY, 4096));
	}
	
	@Test
	public void testBadResult() {
		invalidTest(Result.buildFromVector(Vectors.of(Symbols.FOO,null,null,null,null))); // invalid ID
		invalidTest(Result.buildFromVector(Vectors.of(CVMLong.ONE,null,null,null,Keywords.BAR))); // Invalid info map
		invalidTest(Result.buildFromVector(Vectors.of(CVMLong.ONE,null,null,CVMLong.ONE,null))); // Invalid log vector
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testBadIndex() {
		invalidTest(Index.unsafeCreate(1, null, Index.EMPTY_CHILDREN, 0, 0));
		invalidTest(Index.unsafeCreate(0, null, Index.EMPTY_CHILDREN, 0, 0));

		invalidTest(Index.unsafeCreate(1, MapEntry.of(Blobs.fromHex("12"),CVMLong.ONE), Index.EMPTY_CHILDREN, 0, 1)); // bad depth
		invalidTest(Index.unsafeCreate(0, MapEntry.of(Blobs.fromHex("1234"),CVMLong.ONE), Index.EMPTY_CHILDREN, 0, 1)); // bad depth
		invalidTest(Index.unsafeCreate(2, MapEntry.of(Blobs.fromHex("1234"),CVMLong.ONE), Index.EMPTY_CHILDREN, 0, 1)); // insufficient depth
		invalidTest(Index.unsafeCreate(4, MapEntry.of(Blobs.fromHex("1234"),CVMLong.ONE), Index.EMPTY_CHILDREN, 0, 0)); // bad count

		{ // split at wrong depth
			Index c1=Index.create(Blobs.fromHex("1230"),CVMLong.ONE);
			Index c2=Index.create(Blobs.fromHex("1231"),CVMLong.ZERO);
			invalidTest(Index.unsafeCreate(2, null, new Ref[] {c1.getRef(),c2.getRef()}, 3, 2)); 
		}
		
		{ // inconsistent common prefix before depth
			Index c1=Index.create(Blobs.fromHex("1230"),CVMLong.ONE);
			Index c2=Index.create(Blobs.fromHex("1231"),CVMLong.ZERO);
			Index c3=Index.create(Blobs.fromHex("1332"),CVMLong.ZERO);
			invalidTest(Index.unsafeCreate(3, null, new Ref[] {c1.getRef(),c2.getRef(),c3.getRef()}, 3, 3));
		}
		
		{ // inconsistent common prefix with entry
			MapEntry e=MapEntry.create(Blobs.fromHex("12"), null);
			Index c0=Index.create(Blobs.fromHex("1200"),CVMLong.ONE);
			Index c1=Index.create(Blobs.fromHex("1311"),CVMLong.ZERO);
			invalidTest(Index.unsafeCreate(2, e, new Ref[] {c0.getRef(),c1.getRef()}, 3, 3));
		}
		
		{ // bad mask
			Index c1=Index.create(Blobs.fromHex("1230"),CVMLong.ONE);
			Index c2=Index.create(Blobs.fromHex("1231"),CVMLong.ZERO);
			invalidTest(Index.unsafeCreate(3, null, new Ref[] {c1.getRef(),c2.getRef()}, 5, 2)); 
		}
		
		{ // Two colliding children
			Index c1=Index.create(Blobs.fromHex("1230"),CVMLong.ONE);
			Index c2=Index.create(Blobs.fromHex("1230"),CVMLong.ZERO);
			invalidTest(Index.unsafeCreate(3, null, new Ref[] {c1.getRef(),c2.getRef()}, 1, 2)); 
			invalidTest(Index.unsafeCreate(3, null, new Ref[] {c1.getRef(),c2.getRef()}, 3, 2)); 
		}
		
		{ // Two colliding children at max depth
			Index c1=Index.create(Blobs.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef00"),CVMLong.ONE);
			Index c2=Index.create(Blobs.fromHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef11"),CVMLong.ONE);
			invalidTest(Index.unsafeCreate(64, null, new Ref[] {c1.getRef(),c2.getRef()}, 3, 2)); 
		}
	
	}
	
	@Test
	public void testBadNull() {
		invalidEncoding(Tag.NULL,"00"); // excess bytes
	}
	
	@Test
	public void testBadTags() {
		invalidEncoding(Tag.ILLEGAL,"00"); // disallowed tag
	}
	
	@Test
	public void testBadDouble() {
		// Double with invalid (non-canonical) NaN
		CVMDouble bd=CVMDouble.unsafeCreate(Double.longBitsToDouble(0x7ff80000ff000000L));
		assertTrue(bd.isCVMValue());

		invalidEncoding(Tag.DOUBLE,"00000000000000"); // too short double
		invalidEncoding(Tag.DOUBLE,"000000000000000000"); // too long double
	}
	
	
	@Test
	public void testBadBoolean() {
		invalidEncoding(Tag.TRUE,"12"); // excess byte
		invalidEncoding(Tag.FALSE,"00"); // excess byte
	}
	
	@Test
	public void testBadBlock() {
		invalidEncoding(Tag.BLOCK,""); // no data!
		invalidEncoding(Tag.BLOCK,"1234567812345678"); // timestamp only
		invalidEncoding(Tag.BLOCK,"12345678123456788100"); // list instead of vector

	}
	
	@Test
	public void testBadAccountStatus() {
		invalidTest(AccountStatus.create(-100, null));
	}
	
	@Test
	public void testBadCharacter() {
		invalidEncoding("3c2066"); // excess byte
		invalidEncoding("3d20ff66"); // excess byte
		invalidEncoding("3e10ffff66"); // excess byte on max codepoint

		invalidEncoding("3c"); // missing byte
		invalidEncoding("3d20"); // missing byte
		
		invalidEncoding("3d0020"); // excess leading zeros
		invalidEncoding("3d00ffff"); // excess leading zeros
		invalidEncoding("3f0000ffff"); // excess leading zeros
		invalidEncoding("3f0010ffff"); // excess leading zeros on max code point
		
		invalidEncoding("3d110000"); // just beyond maximum code point
		invalidEncoding("3f0fffffff"); // way beyond max code point
		invalidEncoding("3fffffffff"); // way beyond max code point, also negative int
	}
	
	protected void invalidEncoding(int tag, String more) {
		Blob b=Blob.forByte((byte)tag).append(Blob.fromHex(more)).toFlatBlob();
		invalidEncoding(b);
	}
	
	protected void invalidEncoding(String enc) {
		Blob b=Blob.fromHex(enc);
		invalidEncoding(b);
	}

	protected void invalidEncoding(Blob b) {
		assertThrows(BadFormatException.class,()->Format.read(b));
	}

	@Test
	public void testBadTransactions() {
		Address HERO=Init.GENESIS_ADDRESS;
		
		// invalid amount in Transfer
		invalidTest(Transfer.create(HERO, 0, HERO,Long.MAX_VALUE)); 
		
		// invalid offer amounts in Call
		invalidTest(Call.create(HERO, 0, HERO,Long.MAX_VALUE,Symbols.FOO,Vectors.empty())); 
		invalidTest(Call.create(HERO, 0, HERO,-10,Symbols.FOO,Vectors.empty())); 
	
		// invalid origin in Call. TODO: reconsider?
		assertThrows(IllegalArgumentException.class, ()->Call.create(null, 0, HERO,0,Symbols.FOO,Vectors.empty())); 
	}

	private void invalidTest(ACell b) {
		assertThrows(InvalidDataException.class, ()->b.validate());
		doEncodingTest(b);
	}

	private void doEncodingTest(ACell b) {
		Blob enc=null;
		try {
			enc= b.getEncoding();
		} catch (Exception t) {
			// probably no valid encoding, so skip this test
			return;
		}
		
		ACell c=null;
		try {
			c=Format.read(enc);
			c.validateCell(); // If we managed to read it, should validate at cell level
		} catch (BadFormatException e) {
			// not a readable format, so probably not dangerous
			return;
		} catch (InvalidDataException e) {
			fail("Failed to validate after re-reading?",e);
		}
		
		if (c.isCompletelyEncoded()) {
			// Shouldn't validate
			assertThrows(InvalidDataException.class, ()->b.validate());
		}
	}
}
