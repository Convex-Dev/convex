package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.test.Samples;

/**
 * Tests for adversarial data, i.e. data that should b=not be accepted by correct peers / clients
 */
public class AdversarialDataTest {

	@SuppressWarnings("unchecked")
	public static final AVector<CVMLong> NON_CVM=(AVector<CVMLong>)Samples.INT_VECTOR_300.getRef(0).getValue();
	
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
		invalidTest(MapLeaf.unsafeCreate(b,a)); // Bad order
	}
	
	@Test public void testBadKeywords() {
		invalidTest(Keyword.unsafeCreate((AString)null));
		invalidTest(Keyword.unsafeCreate(""));
		invalidTest(Keyword.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
	}
	
	@Test public void testBadSymbols() {
		invalidTest(Symbol.unsafeCreate((AString)null));
		invalidTest(Symbol.unsafeCreate(""));
		invalidTest(Symbol.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
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
		} catch (BadFormatException e) {
			// not a readable format, so probably not dangerous
			return;
		}
		
		if (c.isCompletelyEncoded()) {
			// Shouldn't validate
			assertThrows(InvalidDataException.class, ()->b.validate());
		}
	}
}
