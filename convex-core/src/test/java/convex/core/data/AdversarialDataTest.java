package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.test.Samples;

/**
 * Tests for adversarial data, i.e. data that should b=not be accepted by correct peers / clients
 */
public class AdversarialDataTest {

	// A value that is valid, but not a first class CVM value
	public static final ACell NON_CVM=Samples.INT_BLOBMAP_256.getRef(0).getValue();
	
	// A value that is non-canonical but otherwise valid CVM value
	public static final Blob NON_CANONICAL=Blob.createRandom(new Random(), Blob.CHUNK_LENGTH+1);

	// A value that is invalid
	public static final SetLeaf<CVMLong> NON_VALID=SetLeaf.unsafeCreate(new CVMLong[0]);

	@Test public void testAssumptions() {
		assertFalse(NON_CVM.isCVMValue());
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
	
	@SuppressWarnings({ "unchecked", "null" })
	@Test public void testBadSetTree() {
		SetTree<CVMLong> a = Samples.INT_SET_300;
		
		invalidTest(a.include(NON_CVM));
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
		assertFalse(b.isCVMValue());
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
		invalidTest(Sets.of(NON_CVM));
		
		// Inserting non-CVM values into existing valid sets
		invalidTest(Sets.of(1,2,3,4).include(NON_CVM));
		invalidTest(Samples.LONG_SET_100.include(NON_CVM));
	}
	
	@Test public void testBadKeywords() {
		invalidTest(Keyword.unsafeCreate((StringShort)null));
		invalidTest(Keyword.unsafeCreate(""));
		invalidTest(Keyword.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
		invalidTest(Keyword.unsafeCreate(Samples.MAX_SHORT_STRING));
	}
	
	@Test public void testBadSymbols() {
		invalidTest(Symbol.unsafeCreate((StringShort)null));
		invalidTest(Symbol.unsafeCreate(""));
		invalidTest(Symbol.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
		invalidTest(Symbol.unsafeCreate(Samples.MAX_SHORT_STRING));
	}

	private void invalidTest(ACell b) {
		assertThrows(InvalidDataException.class, ()->b.validate());
		doEncodingTest(b);
	}

	private void doEncodingTest(ACell b) {
		Blob enc=null;
		try {
			enc= b.getEncoding();
		} catch (Throwable t) {
			// probably no valid encoding, so skip this test
			return;
		}
		
		ACell c=null;
		try {
			c=Format.read(enc);
			c.validateCell();; // If we managed to read it, should validate
		} catch (BadFormatException e) {
			// not a readable format, so probably not dangerous
			return;
		} catch (InvalidDataException e) {
			fail("Failed to validate after re-reading?");
		}
		
		if (c.isCompletelyEncoded()) {
			// Shouldn't validate
			assertThrows(InvalidDataException.class, ()->b.validate());
		}
	}
}
