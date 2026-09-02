package convex.core.lang;

import static convex.test.Assertions.assertCVMEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.data.ACell;
import convex.core.data.ACollection;
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
public class RTSequencesParamTest {

	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { 0, null }, { 0, Vectors.empty() }, { 0, Lists.empty() },
				{ 2, MapEntry.of(1L, 2L) }, { 2, MapEntry.of(Maps.of(1L, 2L), 2L) },
				{ 2, MapEntry.of(null, 2L) }, { 3, Vectors.of(1L, 2L, 3L) }, { 2, List.of("foo", "bar") },
				{ 3, Sets.of(null, 1L, 1.0) } });
	}

	@ParameterizedTest(name = "{index}: {1}")
	@MethodSource("dataExamples")
	public void testCount(int expectedCount, ACollection<?> data) {
		assertEquals(expectedCount, RT.count(data));
	}

	@ParameterizedTest(name = "{index}: {1}")
	@MethodSource("dataExamples")
	public void testSeq(int expectedCount, ACollection<?> data) {
		assertEquals(expectedCount, RT.count(RT.sequence(data)));
	}

	@ParameterizedTest(name = "{index}: {1}")
	@MethodSource("dataExamples")
	public void testVec(int expectedCount, ACollection<?> data) {
		AVector<?> v = RT.vec(data);
		assertEquals(expectedCount, v.count());
		if (expectedCount > 0) {
			assertEquals(data.get(0), v.get(0));
		}
	}

	@ParameterizedTest(name = "{index}: {1}")
	@MethodSource("dataExamples")
	public void testCons(int expectedCount, ACollection<?> data) {
		ASequence<ACell> a = RT.cons(RT.cvm("foo"), RT.sequence(data));
		assertCVMEquals("foo", a.get(0));
		assertCVMEquals("foo", RT.nth(a, 0));
	}

	@ParameterizedTest(name = "{index}: {1}")
	@MethodSource("dataExamples")
	public void testFirst(int expectedCount, ACollection<?> data) {
		if (expectedCount > 0) {
			ACell fst = data.get(0);
			assertEquals(RT.nth(data, 0), fst);
		} else {
			if (data!=null) assertThrows(IndexOutOfBoundsException.class, () -> data.get(0));
		}
	}
}
