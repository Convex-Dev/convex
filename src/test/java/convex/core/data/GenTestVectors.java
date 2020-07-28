package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.generators.VectorGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestVectors {

	@Property
	public void testGenericProperties(@From(VectorGen.class) AVector<Object> a) {
		VectorsTest.doVectorTests(a);
	}

	@Property
	public void testConcatPrefixes(@From(VectorGen.class) AVector<Object> a, @From(VectorGen.class) AVector<Object> b) {
		long al = a.count();
		long bl = b.count();

		AVector<Object> ab = a.concat(b);
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
