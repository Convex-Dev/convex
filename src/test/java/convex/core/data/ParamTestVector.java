package convex.core.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.exceptions.BadFormatException;
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
						{ "MapEntry vector", MapEntry.create(1L, 2L) }, { "Nested vector", Vectors.of(Vectors.empty()) },
						{ "Vector with Account status", Vectors.of(AccountStatus.create(1000L,Samples.ACCOUNT_KEY)) },
						{ "Vector with Peer status", Vectors.of(PeerStatus.create(1000L)) },
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
		assertTrue(v.isCanonical());
	}

	@Test
	public void testElements() {
		int n = v.size();
		for (int i = 0; i < n; i++) {
			Object o = v.get(i);
			assertEquals(o, v.slice(i, 1).get(0));
		}

		assertThrows(Throwable.class, () -> v.get(-1));
		assertThrows(Throwable.class, () -> v.get(n));
	}

	@Test
	public void testBuffer() throws BadFormatException {
		int size = v.size();
		ByteBuffer b = ByteBuffer.allocate(3000);
		v.write(b);
		b.flip();
		AVector<?> rec = Format.read(b);
		assertEquals(0, b.remaining());
		assertEquals(size, rec.size());
		assertEquals(v, rec);
	}

}
