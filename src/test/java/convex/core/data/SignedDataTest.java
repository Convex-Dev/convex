package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.RT;
import convex.core.lang.TestState;
import convex.test.Samples;

public class SignedDataTest {
	@Test
	public void testBadSignature() {
		Ref<CVMLong> dref = Ref.get(RT.cvm(13L));
		SignedData<CVMLong> sd = SignedData.create(Samples.BAD_ACCOUNTKEY, Samples.BAD_SIGNATURE, dref);
		
		assertFalse(sd.isValid());

		assertEquals(13L, sd.getValueUnchecked().longValue());
		assertSame(Samples.BAD_ACCOUNTKEY, sd.getAccountKey());
		assertNotNull(sd.toString());

		assertThrows(BadSignatureException.class, () -> sd.getValue());
	}

	@Test
	public void testEmbeddedSignature() throws BadSignatureException {
		CVMLong cl=RT.cvm(158587);
		
		AKeyPair kp = TestState.HERO_PAIR;
		SignedData<CVMLong> sd = kp.signData(cl);
		
		assertTrue(sd.isValid());
		
		sd.validateSignature();
		assertEquals(cl, sd.getValue());
		
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
