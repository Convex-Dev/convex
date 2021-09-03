package convex.core.lang;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.ASet;
import convex.test.generators.CollectionGen;
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestRT {

	@SuppressWarnings("rawtypes")
	@Property
	public void setConversion(@From(CollectionGen.class) ACollection a) {
		long ac = a.count();
		ASet<ACell> set = RT.castSet(a);
		assertTrue(set.count() <= ac);
		for (Object o : a) {
			assertTrue(set.contains(o));
		}
	}

	@Property
	public void strTest(@From(ValueGen.class) ACell b) {
		String s = RT.str(b);
		assertNotNull(s);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Property
	public void conjTest(@From(CollectionGen.class) ACollection a, @From(ValueGen.class) ACell b) {
		ACollection<ACell> ac = a.conj(b);
		assertTrue(ac.contains(b));
	}
}
