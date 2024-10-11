package convex.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.cvm.AccountStatus;
import convex.core.cvm.PeerStatus;
import convex.test.Samples;

/**
 * Parameterised test class for a bunch of vectors.
 *
 */
@RunWith(Parameterized.class)
public class ParamTestVector {
	private AVector<?> v;

	public ParamTestVector(String label, AVector<?> v) {
		this.v = v;
	}

	@Parameterized.Parameters(name = "{index}: {0}")
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

	@Test
	public void testGenericProperties() {
		VectorsTest.doVectorTests(v);
	}

	@Test
	public void testCanonical() {
		assertTrue(v.toCanonical().isCanonical());
	}

	@Test
	public void testElements() {
		int n = v.size();
		for (int i = 0; i < n; i++) {
			ACell o = v.get(i);
			assertEquals(o, v.slice(i, i+1).get(0));
		}

		assertThrows(Throwable.class, () -> v.get(-1));
		assertThrows(Throwable.class, () -> v.get(n));
	}
}
