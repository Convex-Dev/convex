package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.TestState;
import convex.test.Samples;

public class SignedDataTest {
	@Test
	public void testBadSignature() {
		Ref<Long> dref = Ref.get(13L);
		SignedData<Long> sd = SignedData.create(Samples.BAD_ACCOUNTKEY, Samples.BAD_SIGNATURE, dref);
		
		assertFalse(sd.isValid());

		assertEquals(13L, (long) sd.getValueUnchecked());
		assertSame(Samples.BAD_ACCOUNTKEY, sd.getAccountKey());
		assertNotNull(sd.toString());

		assertThrows(BadSignatureException.class, () -> sd.getValue());
	}

	@Test
	public void testEmbeddedSignature() throws BadSignatureException {
		AKeyPair kp = TestState.HERO_PAIR;
		SignedData<Long> sd = kp.signData(1L);
		
		assertTrue(sd.isValid());
		
		sd.validateSignature();
		assertEquals(1L, sd.getValue());
		
		assertTrue(sd.getDataRef().isEmbedded());
	}

	@Test
	public void testDataStructureSignature() throws BadSignatureException {
		AKeyPair kp = TestState.HERO_PAIR;
		AVector<Long> v = Vectors.of(1L, 2L, 3L);
		SignedData<Object> sd = kp.signData(v);
		
		assertTrue(sd.isValid());
		
		sd.validateSignature();
		assertEquals(v, sd.getValue());
		
		assertEquals(kp.getAccountKey(),sd.getAccountKey());
	}
}
