package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.lang.RT;
import convex.test.generators.DataStructureGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestDataStructures {

	@SuppressWarnings("rawtypes")
	@Property
	public void empty(@From(DataStructureGen.class) ADataStructure a) {
		long c = a.count();

		ADataStructure<?> e = a.empty();
		if (c == 0) {
			assertSame(a, e);
		}

		assertEquals(0, e.count());
		assertTrue(e.isEmpty());
		assertEquals(0, e.size());
	}

	@SuppressWarnings("rawtypes")
	@Property
	public void testSequence(@From(DataStructureGen.class) ADataStructure a) {
		ASequence<?> seq = RT.sequence(a);
		assertEquals(seq.count(), a.count());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Property
	public void testJavaInterface(@From(DataStructureGen.class) ADataStructure a) {
		if (a instanceof Collection) {
			ACollection<ACell> coll = (ACollection<ACell>) a;

			assertThrows(UnsupportedOperationException.class, () -> coll.clear());
			assertThrows(UnsupportedOperationException.class, () -> coll.addAll(coll));
			assertThrows(UnsupportedOperationException.class, () -> coll.retainAll(coll));
			assertThrows(UnsupportedOperationException.class, () -> coll.removeAll(coll));
			assertThrows(UnsupportedOperationException.class, () -> coll.remove(null));

			if (coll instanceof List) {
				List<ACell> list = (List<ACell>) a;
				ASequence<ACell> seq = (ASequence<ACell>) list; // must be an ASequence

				assertThrows(UnsupportedOperationException.class, () -> list.set(0, null));
				assertThrows(UnsupportedOperationException.class, () -> list.addAll(0, coll));
				assertThrows(UnsupportedOperationException.class, () -> list.remove(0));

				int n = list.size();
				if (n > 0) {
					assertEquals(list.get(n - 1), RT.nth(seq, n - 1));
				}
			}
		}
	}

}
