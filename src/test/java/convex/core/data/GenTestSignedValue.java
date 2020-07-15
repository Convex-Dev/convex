package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.generators.ValueGen;
import convex.test.Samples;

@RunWith(JUnitQuickcheck.class)
public class GenTestSignedValue {
	public GenTestSignedValue() {

	}

	private final AKeyPair KEYPAIR = Init.HERO_KP;
	private final Address ADDRESS = KEYPAIR.getAddress();
	private final Address BAD_ADDRESS = Samples.BAD_ADDRESS;

	@Test
	public void testAddressLength() {
		assertEquals(Address.LENGTH, ADDRESS.length());
	}

	@Test
	public void testNullValueSignings() throws BadSignatureException {
		SignedData<Object> sd = SignedData.create(KEYPAIR, null);
		assertNull(sd.getValue());
		assertTrue(sd.checkSignature());
	}

	@Property(trials = 20)
	public void dataSigning(@From(ValueGen.class) Object data) {
		SignedData<Object> good = KEYPAIR.signData(data);
		assertEquals(good,SignedData.create(KEYPAIR, data));
		assertTrue(good.checkSignature());

		SignedData<Object> bad = SignedData.create(BAD_ADDRESS, good.getSignature(), good.getDataRef());
		assertFalse(bad.checkSignature());
		try {
			bad.validateSignature();
			fail("Should not be able to validate a bad signature");
		} catch (BadSignatureException e) {
			/** OK **/
		}
	}

	@Property(trials = 20)
	public void signedDataRoundTrip(@From(ValueGen.class) Object data) throws BadFormatException {
		Ref<Object> ref = Ref.create(data);
		SignedData<Object> good = SignedData.createWithRef(KEYPAIR, ref);
		assertTrue(good.checkSignature());
		// System.out.println(good.getSignature());
		Blob d = good.getEncoding();
		// System.out.println(d);
		SignedData<Object> good2 = Format.read(d);
		assertEquals(good, good2);
	}
}
