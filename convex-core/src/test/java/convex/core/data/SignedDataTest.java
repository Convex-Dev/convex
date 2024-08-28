package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadSignatureException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.transactions.Invoke;
import convex.test.Samples;

public class SignedDataTest {
	@SuppressWarnings("unchecked")
	@Test
	public void testBadSignature() throws IOException {
		Ref<CVMLong> dref = Ref.get(RT.cvm(13L));
		SignedData<CVMLong> sd = SignedData.create(Samples.BAD_ACCOUNTKEY, Samples.BAD_SIGNATURE, dref);

		// should not yet be checked
		assertFalse(sd.isSignatureChecked());
		
		// Signature check should fail since bad signature
		assertFalse(sd.checkSignature());
		
		// should now be checked
		assertTrue(sd.isSignatureChecked());

		assertTrue((sd.getRef().getFlags()&Ref.BAD_MASK)!=0);
		assertEquals(13L, sd.getValue().longValue());
		assertSame(Samples.BAD_ACCOUNTKEY, sd.getAccountKey());
		assertNotNull(sd.toString());

		assertThrows(BadSignatureException.class, () -> sd.validateSignature());
		
		Cells.persist(sd);
		
		SignedData<CVMLong> sd1 = (SignedData<CVMLong>) Ref.forHash(sd.getHash()).getValue();
		// should have cached checked signature
		assertTrue(sd1.isSignatureChecked());
		assertFalse(sd1.checkSignature());
		
		doSignedDataTest(sd);
		doSignedDataTest(sd1);
	}

	/**
	 * Generic tests or SignedData instance
	 * @param sd SignedData instance to test
	 */
	private void doSignedDataTest(SignedData<?> sd) {
		RecordTest.doRecordTests(sd);
	}

	@Test
	public void testEmbeddedSignature() throws BadSignatureException {
		CVMLong cl=RT.cvm(158587);

		AKeyPair kp = InitTest.HERO_KEYPAIR;
		SignedData<CVMLong> sd = kp.signData(cl);
		
		// should be checked by default
		assertTrue(sd.isSignatureChecked());

		assertTrue(sd.checkSignature());

		sd.validateSignature();
		assertEquals(cl, sd.getValue());

		assertTrue(sd.getValueRef().isEmbedded());
		
		doSignedDataTest(sd);
	}
	
	@SuppressWarnings("unchecked")
	@Test 
	public void testSignatureCache() throws IOException {
		CVMLong cl=RT.cvm(1585856457);
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		SignedData<CVMLong> sd = kp.signData(cl);
		Cells.persist(sd);
		
		SignedData<CVMLong> sd1 = (SignedData<CVMLong>) Ref.forHash(sd.getHash()).getValue();
		// should have cached checked signature
		assertTrue(sd1.isSignatureChecked());
		assertTrue(sd1.checkSignature());
		
		doSignedDataTest(sd);
		doSignedDataTest(sd1);
	}
	
	@Test
	public void testRefs() {
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		SignedData<?> sd=kp.signData(Vectors.of(1,3,5,7));
		
		assertEquals(1,sd.getRefCount());
		assertEquals(6,Refs.totalRefCount(sd));
		
		SignedData<?> nsd=sd.updateRefs(r->r);
		assertSame(nsd,sd);
		
		Refs.RefTreeStats stats=Refs.getRefTreeStats(sd.getRef());
		assertEquals(6,stats.total);
		
		doSignedDataTest(sd);
	}

	@Test
	public void testNullValueSignings() throws BadSignatureException {
		SignedData<ACell> sd = SignedData.sign(InitTest.HERO_KEYPAIR, null);
		assertNull(sd.getValue());
		assertTrue(sd.checkSignature());
		
		doSignedDataTest(sd);
	}

	@Test
	public void testDataStructureSignature() throws BadSignatureException {
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		AVector<CVMLong> v = Vectors.of(1L, 2L, 3L);
		SignedData<AVector<CVMLong>> sd = kp.signData(v);
		
		assertEquals(1,sd.getRefCount());

		assertTrue(sd.checkSignature());

		sd.validateSignature();
		assertEquals(v, sd.getValue());

		assertEquals(kp.getAccountKey(),sd.getAccountKey());
		
		doSignedDataTest(sd);
		
		// Test signing again produces exactly equal value
		SignedData<AVector<CVMLong>> sd2 = kp.signData(v);
		ObjectsTest.doEqualityTests(sd, sd2);
	}
	
	@Test 
	public void testEncoding() {
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		SignedData<?> sd=kp.signData(Invoke.create(Address.ZERO, 0, Samples.MAX_SYMBOLIC));
		
		Blob enc=sd.getEncoding();
		Ref<SignedData<?>> r=sd.getRef();
		
		assertEquals(enc.getContentHash(),r.getHash());
		
		doSignedDataTest(sd);
	}
}
