package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

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
		invalidTest(MapLeaf.unsafeCreate(a,b,a)); // Duplicate key not in order
		invalidTest(MapLeaf.unsafeCreate(b,a)); // Bad order
		
		// Too many map entries for a MapLeaf
		MapEntry<CVMLong,CVMLong>[] mes=new MapEntry[MapLeaf.MAX_ENTRIES+1];
		for (int i=0; i<mes.length; i++) {
			mes[i]=MapEntry.of(i, i);
		}
		Arrays.sort(mes, (x,y)->x.getKeyHash().compareTo(y.getKeyHash()));
		invalidTest(MapLeaf.unsafeCreate(mes));
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
		
		// Too many map entries for a MapLeaf
		CVMLong[] mes=new CVMLong[SetLeaf.MAX_ELEMENTS+1];
		for (int i=0; i<mes.length; i++) {
			mes[i]=CVMLong.create(i);
		}
		Arrays.sort(mes, (x,y)->x.getHash().compareTo(y.getHash()));
		invalidTest(SetLeaf.unsafeCreate(mes));
	}
	
	@Test public void testBadKeywords() {
		invalidTest(Keyword.unsafeCreate((AString)null));
		invalidTest(Keyword.unsafeCreate(""));
		invalidTest(Keyword.unsafeCreate(Samples.TOO_BIG_SYMBOLIC));
		invalidTest(Keyword.unsafeCreate(Samples.MAX_SHORT_STRING));
	}
	
	@Test public void testBadSymbols() {
		invalidTest(Symbol.unsafeCreate((AString)null));
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
			assertEquals(b,c); // If we managed to read it, should at least be equal
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
