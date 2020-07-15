package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.TestState;
import convex.test.Samples;

public class SignedDataTest {
	@Test
	public void testBadSignature() {
		Ref<Long> dref = Ref.create(13L);
		SignedData<Long> sd = SignedData.create(Samples.BAD_ADDRESS, Samples.BAD_SIGNATURE, dref);

		assertEquals(13L, (long) sd.getValueUnchecked());
		assertSame(Samples.BAD_ADDRESS, sd.getAddress());
		assertNotNull(sd.toString());

		assertThrows(BadSignatureException.class, () -> sd.getValue());
	}

	@Test
	public void testEmbeddedSignature() throws BadSignatureException {
		AKeyPair kp = TestState.HERO_PAIR;
		SignedData<Long> sd = kp.signData(1L);
		sd.validateSignature();
		assertEquals(1L, sd.getValue());
	}

	@Test
	public void testDataStructureSignature() throws BadSignatureException {
		AKeyPair kp = TestState.HERO_PAIR;
		AVector<Long> v = Vectors.of(1L, 2L, 3L);
		SignedData<Object> sd = kp.signData(v);
		sd.validateSignature();
		assertEquals(v, sd.getValue());
	}
}
