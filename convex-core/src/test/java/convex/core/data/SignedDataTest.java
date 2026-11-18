package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Address;
import convex.core.cvm.CVMEncoder;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.MissingDataException;
import convex.core.init.InitTest;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
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

		Cells.persist(sd, Samples.TEST_STORE);

		SignedData<CVMLong> sd1 = (SignedData<CVMLong>) Ref.forHash(sd.getHash(), Samples.TEST_STORE).getValue();
		// should have cached checked signature
		assertTrue(sd1.isSignatureChecked());
		assertFalse(sd1.checkSignature());

		doSignedDataTest(sd);
		doSignedDataTest(sd1);
	}

	/**
	 * Generic tests of SignedData instance
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
		Cells.persist(sd, Samples.TEST_STORE);

		SignedData<CVMLong> sd1 = (SignedData<CVMLong>) Ref.forHash(sd.getHash(), Samples.TEST_STORE).getValue();
		// should have cached checked signature
		assertTrue(sd1.isSignatureChecked());
		assertTrue(sd1.checkSignature());
		
		doSignedDataTest(sd);
		doSignedDataTest(sd1);
	}
	
	@Test
	public void testCellChecksNeedNoSignedValue() throws Exception {
		SignedData<?> sd=InitTest.HERO_KEYPAIR.signData(Samples.INT_VECTOR_300);
		assertFalse(sd.getValueRef().isEmbedded());

		try (MemoryStore empty=new MemoryStore()) {
			SignedData<?> decoded=empty.decode(sd.getEncoding());
			assertThrows(MissingDataException.class,()->decoded.getValue());

			Cells.validateCell(decoded);
			assertTrue(decoded.checkSignature());
		}
	}

	@Test
	public void testEmbeddedSmallVectorIsCheckedByDecoding() throws Exception {
		AVector<CVMLong> valid=Vectors.of(1,2,3);
		SignedData<?> sd=InitTest.HERO_KEYPAIR.signData(valid);
		assertTrue(sd.getValueRef().isEmbedded());
		Blob encoding=sd.getEncoding();
		int valueStart=1+AccountKey.LENGTH+Ed25519Signature.SIGNATURE_LENGTH;
		assertEquals(valid.getEncoding(),encoding.slice(valueStart));

		try (MemoryStore store=new MemoryStore()) {
			SignedData<?> decoded=store.decode(encoding);
			Cells.validate(decoded);
			assertEquals(valid,decoded.getValue());

			// [1 2 3] with a count of 4, then with its first element given a redundant
			// leading zero byte
			String header=encoding.slice(0,valueStart).toHexString();
			for (String invalid:new String[] {"8004110111021103","800312000111021103"}) {
				assertThrows(BadFormatException.class,()->store.decode(Blob.fromHex(header+invalid)));
			}
		}
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

	/** Signed data without a public key has the short encoding 0x91 (CAD003) */
	@Test
	public void testShortForm() throws BadFormatException {
		AKeyPair kp = InitTest.HERO_KEYPAIR;
		// Large enough that the short form is not embedded either
		ABlob value=Blob.createRandom(new Random(7), 80);
		SignedData<ABlob> sd=SignedData.create(kp.signData(value).getSignature(), value.getRef());

		Blob enc=sd.getEncoding();
		assertEquals(Tag.SIGNED_DATA_SHORT,enc.byteAt(0));
		assertEquals(1+Ed25519Signature.SIGNATURE_LENGTH+value.getEncodingLength(),enc.count());
		assertFalse(sd.isEmbedded());

		SignedData<ABlob> decoded=Samples.TEST_STORE.decode(enc);
		assertEquals(sd,decoded);
		assertTrue(decoded.checkSignature(kp.getAccountKey()));

		// A message re-encodes each child cell it decodes
		Blob msg=Format.encodeMultiCell(Vectors.of(sd),true);
		AVector<?> v=(AVector<?>)CVMEncoder.INSTANCE.decodeMultiCell(msg);
		assertEquals(sd,v.get(0));
	}
}
