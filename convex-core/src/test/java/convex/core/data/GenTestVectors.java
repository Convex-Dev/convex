package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.test.generators.VectorGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestVectors {

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Property
	public void testGenericProperties(@From(VectorGen.class) AVector a) {
		VectorsTest.doVectorTests(a);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void testConcatPrefixes(@From(VectorGen.class) AVector a, @From(VectorGen.class) AVector b) {
		long al = a.count();
		long bl = b.count();

		AVector<ACell> ab = a.concat(b);
		VectorsTest.doVectorTests(ab); // useful to test these

		assertEquals(al + bl, ab.count());

		assertEquals(al, a.commonPrefixLength(a));
		assertTrue(al <= a.commonPrefixLength(ab));
		assertTrue(bl <= b.concat(a).commonPrefixLength(b));

		long cp = a.commonPrefixLength(b);
		assertEquals(cp, b.commonPrefixLength(a));
		if (cp > 0) {
			assertEquals(a.get(cp - 1), b.get(cp - 1));
		}
	}
}
