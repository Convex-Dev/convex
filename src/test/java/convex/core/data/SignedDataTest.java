package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitConfigTest;
import convex.core.lang.RT;
import convex.test.Samples;

public class SignedDataTest {
	@Test
	public void testBadSignature() {
		Ref<CVMLong> dref = Ref.get(RT.cvm(13L));
		SignedData<CVMLong> sd = SignedData.create(Samples.BAD_ACCOUNTKEY, Samples.BAD_SIGNATURE, dref);

		assertFalse(sd.checkSignature());

		assertTrue((sd.getRef().getFlags()&Ref.BAD_MASK)!=0);
		assertEquals(13L, sd.getValue().longValue());
		assertSame(Samples.BAD_ACCOUNTKEY, sd.getAccountKey());
		assertNotNull(sd.toString());

		assertThrows(BadSignatureException.class, () -> sd.validateSignature());
	}

	@Test
	public void testEmbeddedSignature() throws BadSignatureException {
		CVMLong cl=RT.cvm(158587);

		AKeyPair kp = InitConfigTest.HERO_KEYPAIR;
		SignedData<CVMLong> sd = kp.signData(cl);

		assertTrue(sd.checkSignature());

		sd.validateSignature();
		assertEquals(cl, sd.getValue());

		assertTrue(sd.getDataRef().isEmbedded());
	}
	
	@Test 
	public void testSignatureCache() {
		
	}

	@Test
	public void testNullValueSignings() throws BadSignatureException {
		SignedData<ACell> sd = SignedData.create(InitConfigTest.HERO_KEYPAIR, null);
		assertNull(sd.getValue());
		assertTrue(sd.checkSignature());
	}

	@Test
	public void testDataStructureSignature() throws BadSignatureException {
		AKeyPair kp = InitConfigTest.HERO_KEYPAIR;
		AVector<CVMLong> v = Vectors.of(1L, 2L, 3L);
		SignedData<AVector<CVMLong>> sd = kp.signData(v);

		assertTrue(sd.checkSignature());

		sd.validateSignature();
		assertEquals(v, sd.getValue());

		assertEquals(kp.getAccountKey(),sd.getAccountKey());
	}
}
