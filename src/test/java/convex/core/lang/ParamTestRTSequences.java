package convex.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static convex.test.Assertions.*;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Vectors;

/**
 * Set of test for objects that can be treated as sequences
 *
 */
@RunWith(Parameterized.class)
public class ParamTestRTSequences {
	@Parameterized.Parameters(name = "{index}: {1}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { 0, null }, { 0, Vectors.empty() }, { 0, Lists.empty() },
				{ 2, MapEntry.of(1L, 2L) }, { 2, MapEntry.of(Maps.of(1L, 2L), 2L) },
				{ 2, MapEntry.of(null, 2L) }, { 3, Vectors.of(1L, 2L, 3L) }, { 2, List.of("foo", "bar") },
				{ 3, Sets.of(null, 1L, 1.0) } });
	}

	private ASequence<?> data;
	private long expectedCount;

	public ParamTestRTSequences(int expectedCount, Object data) {
		this.expectedCount = expectedCount;
		this.data = RT.sequence(data);
	}

	@Test
	public void testCount() {
		assertEquals(expectedCount, RT.count(data));
	}

	@Test
	public void testSeq() {
		assertEquals(expectedCount, RT.count(RT.sequence(data)));
	}

	@Test
	public void testVec() {
		AVector<?> v = RT.vec(data);
		assertEquals(expectedCount, v.count());
		if (expectedCount > 0) {
			assertEquals(data.get(0), v.get(0));
		}
	}

	@Test
	public void testCons() {
		ASequence<ACell> a = RT.cons(RT.cvm("foo"), data);
		assertCVMEquals("foo", a.get(0));
		assertCVMEquals("foo", RT.nth(a, 0));
	}

	@Test
	public void testFirst() {
		if (expectedCount > 0) {
			Object fst = data.get(0);
			assertEquals(RT.nth(data, 0), fst);
		} else {
			assertThrows(IndexOutOfBoundsException.class, () -> data.get(0));
		}
	}

	@Test
	public void testNext() {
		if (expectedCount > 0) {
			ASequence<?> nxt = RT.next(data);
			assertEquals(expectedCount - 1, nxt.count());
			if (expectedCount > 1) {
				assertEquals((Object)RT.nth(data, 1), RT.nth(nxt, 0));
			}
		}
	}
}
