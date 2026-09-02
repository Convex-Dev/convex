package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.PeerStatus;
import convex.test.Samples;

/**
 * Parameterised test class for a bunch of vectors.
 *
 */
public class VectorParamTest {

	public static Collection<Object[]> dataExamples() {
		return Arrays
				.asList(new Object[][] { { "Empty Vector", Vectors.empty() }, { "Single value vector", Vectors.of(7L) },
						{ "MapEntry vector", MapEntry.of(1L, 2L) }, { "Nested vector", Vectors.of(Vectors.empty()) },
						{ "Vector with Account status", Vectors.of(AccountStatus.create(1000L,Samples.ACCOUNT_KEY)) },
						{ "Vector with Peer status", Vectors.of(PeerStatus.create(Address.create(11), 1000L)) },
						{ "Length 10 vector", Samples.INT_VECTOR_10 }, { "Length 16 vector", Samples.INT_VECTOR_16 },
						{ "Length 23 vector", Samples.INT_VECTOR_23 }, { "Length 32 vector", Samples.INT_VECTOR_32 },
						{ "Length 300 vector", Samples.INT_VECTOR_300 },
						{ "Length 256 tree vector", Samples.INT_VECTOR_256 } });
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testGenericProperties(String label, AVector<?> v) {
		VectorsTest.doVectorTests(v);
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testCanonical(String label, AVector<?> v) {
		assertTrue(v.toCanonical().isCanonical());
	}

	@ParameterizedTest(name = "{index}: {0}")
	@MethodSource("dataExamples")
	public void testElements(String label, AVector<?> v) {
		int n = v.size();
		for (int i = 0; i < n; i++) {
			ACell o = v.get(i);
			assertEquals(o, v.slice(i, i+1).get(0));
		}

		assertThrows(Throwable.class, () -> v.get(-1));
		assertThrows(Throwable.class, () -> v.get(n));
	}
}
