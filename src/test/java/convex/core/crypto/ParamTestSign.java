package convex.core.crypto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.util.Utils;

@RunWith(Parameterized.class)
public class ParamTestSign {
	private ABlob message;

	public ParamTestSign(String label, ABlob message) {
		this.message = message;
	}

	private static AArrayBlob GENESIS = Blob.fromHex(HashTest.GENESIS_HEADER);

	@Parameterized.Parameters(name = "{index}: {0}")
	public static Collection<Object[]> dataExamples() {
		return Arrays.asList(new Object[][] { { "Empty bytes", Blob.create(Utils.EMPTY_BYTES) },
				{ "Short hex string data", Blob.fromHex("CAFEBABE") },
				{ "Length 2 strict sublist of byte data", Blob.create(new byte[] { 1, 2, 3, 4 }) },
				{ "Bitcoin genesis header block", GENESIS },
				{ "Bitcoin genesis header block x2", GENESIS.append(GENESIS) } });
	}

	@Test
	public void testSignMessage() {
		byte[] hashedMessage = message.getContentHash().getBytes();
		ECDSAKeyPair kp1 = ECDSAKeyPair.generate(new SecureRandom());
		ECDSAKeyPair kp2 = SignTest.KEY_PAIR; // hard coded address

		// sign with freshly generated key pair
		ECDSASignature signature = ECDSASignature.sign(hashedMessage, kp1);
		assertTrue(ECDSASignature.verify(hashedMessage, signature, kp1.getPublicKey()));

		// sign with hard-coded key pair
		ECDSASignature signature2 = ECDSASignature.sign(hashedMessage, kp2);
		assertTrue(ECDSASignature.verify(hashedMessage, signature2, kp2.getPublicKey()));

		// make sure that use of wrong public key fails
		assertFalse(ECDSASignature.verify(hashedMessage, signature, kp2.getPublicKey()));
		assertFalse(ECDSASignature.verify(hashedMessage, signature2, kp1.getPublicKey()));
	}

}
