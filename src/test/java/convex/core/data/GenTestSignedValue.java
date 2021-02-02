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
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestSignedValue {
	public GenTestSignedValue() {

	}

	private final AKeyPair KEYPAIR = Init.HERO_KP;
	private final AccountKey BAD_ADDRESS = AccountKey.dummy("bad");

	@Test
	public void testNullValueSignings() throws BadSignatureException {
		SignedData<ACell> sd = SignedData.create(KEYPAIR, null);
		assertNull(sd.getValue());
		assertTrue(sd.checkSignature());
	}

	@Property(trials = 20)
	public void dataSigning(@From(ValueGen.class) ACell data) {
		SignedData<ACell> good = KEYPAIR.signData(data);
		assertEquals(good,SignedData.create(KEYPAIR, data));
		assertTrue(good.checkSignature());

		SignedData<ACell> bad = SignedData.create(BAD_ADDRESS, good.getSignature(), good.getDataRef());
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
		Ref<ACell> ref = Ref.get(data);
		SignedData<ACell> good = SignedData.createWithRef(KEYPAIR, ref);
		assertTrue(good.checkSignature());
		// System.out.println(good.getSignature());
		Blob d = good.getEncoding();
		// System.out.println(d);
		SignedData<ACell> good2 = Format.read(d);
		assertEquals(good, good2);
	}
}
