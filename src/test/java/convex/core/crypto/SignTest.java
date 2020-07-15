package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.util.Utils;

public class SignTest {

	@Test
	public void testSignVariance() {

		byte[] message1 = "Hello".getBytes(StandardCharsets.UTF_8);
		byte[] message2 = "Goodbye".getBytes(StandardCharsets.UTF_8);

		// create a key pair
		SecureRandom sr = new SecureRandom();
		ECDSAKeyPair kp = ECDSAKeyPair.generate(sr);

		// create and verify first signature
		ECDSASignature s1 = ECDSASignature.sign(message1, kp);
		assertTrue(ECDSASignature.verify(message1, s1, kp.getPublicKey()));

		// second signing should be the same
		assertEquals(s1, ECDSASignature.sign(message1, kp));

		// create and verify second signature
		ECDSASignature s2 = ECDSASignature.sign(message2, kp);
		assertNotEquals(s1, s2);
		assertTrue(ECDSASignature.verify(message2, s2, kp.getPublicKey()));

		// signatures should not be valid for the wrong message
		assertFalse(ECDSASignature.verify(message2, s1, kp.getPublicKey()));
		assertFalse(ECDSASignature.verify(message1, s2, kp.getPublicKey()));

		// Signing a message should result in different r
		// Need to avoid ECDSA nonce re-use private key recovery attacks
		assertNotEquals(s1.r, s2.r);

		// public key recovery from signature should work
		BigInteger rpk1 = ECDSASignature.publicKeyFromSignature(s1, message1);
		BigInteger rpk2 = ECDSASignature.publicKeyFromSignature(s2, message2);
		assertEquals(kp.getPublicKey(), rpk1);
		assertEquals(kp.getPublicKey(), rpk2);

	}

	public static final String PRIVATE_KEY_STRING = "a392604efc2fad9c0b3da43b5f698a2e3f270f170d859912be0d54742275c5f6";
	static final String PUBLIC_KEY_STRING = "506bc1dc099358e5137292f4efdd57e400f29ba5132aa5d12b18dac1c1f6aaba645c0b7b58158babbfa6c6cd5a48aa7340a8749176b120e8516216787a13dc76";

	static final byte[] PRIVATE_KEY = Utils.hexToBytes(PRIVATE_KEY_STRING);
	static final byte[] PUBLIC_KEY = Utils.hexToBytes(PUBLIC_KEY_STRING);

	static final ECDSAKeyPair KEY_PAIR = new ECDSAKeyPair(PRIVATE_KEY, PUBLIC_KEY);

	@Test
	public void testLengths() {
		assertEquals(32, PRIVATE_KEY.length);
		assertEquals(64, PUBLIC_KEY.length);
	}

	@Test
	public void testPrivateFromPublic() {
		// private Key from https://kjur.github.io/jsrsasign/sample/sample-ecdsa.html
		// public key skips "04" header
		String pk = PRIVATE_KEY_STRING;
		String expected_private = PUBLIC_KEY_STRING;
		ECDSAKeyPair kp = ECDSAKeyPair.create(Utils.hexToBytes(pk));
		BigInteger b = kp.getPublicKey();
		assertEquals(expected_private, Utils.toHexString(b, 128));
	}

	@Test
	public void testSeededKeys() {
		assertEquals(ECDSAKeyPair.createSeeded(10), ECDSAKeyPair.createSeeded(10));
		assertNotEquals(ECDSAKeyPair.createSeeded(10), ECDSAKeyPair.createSeeded(11));
	}

	@Test
	public void testPublicKeyGen() {
		ECDSAKeyPair kp = ECDSAKeyPair.create(Utils.toBigInteger(PRIVATE_KEY));
		assertEquals(Utils.toBigInteger(PUBLIC_KEY), kp.getPublicKey());
	}

	/* Test case adapted from web3j */
	@Test
	public void testSignMessage() {
		byte[] TEST_MESSAGE = "aaa".getBytes();
		byte[] HASHED_MESSAGE = Hash.sha256(TEST_MESSAGE).getBytes();
		ECDSASignature signature = ECDSASignature.sign(HASHED_MESSAGE, KEY_PAIR);

		assertTrue(ECDSASignature.verify(HASHED_MESSAGE, signature, Utils.hexToBigInt(PUBLIC_KEY_STRING)));
	}

	/* Test case adapted from web3j */
	@Test
	public void testSignMessage2() {
		byte[] TEST_MESSAGE = "A test message".getBytes(StandardCharsets.UTF_8);
		byte[] HASHED_MESSAGE = Hash.keccak256(TEST_MESSAGE).getBytes();
		ECDSASignature signature = ECDSASignature.sign(HASHED_MESSAGE, KEY_PAIR);

		String EXPECTED_R = "9631f6d21dec448a213585a4a41a28ef3d4337548aa34734478b563036163786";
		String EXPECTED_S = "2ff816ee6bbb82719e983ecd8a33a4b45d32a4b58377ef1381163d75eedc900b";

		assertTrue(ECDSASignature.verify(HASHED_MESSAGE, signature, Utils.hexToBigInt(PUBLIC_KEY_STRING)));
		assertEquals(EXPECTED_R, Utils.toHexString(signature.r, 64));
		assertEquals(EXPECTED_S, Utils.toHexString(signature.s, 64));
		assertEquals(0, signature.recoveryID);

		ABlob d = signature.getEncoding();
		ABlob rData = d.slice(1, 32);
		ABlob sData = d.slice(33, 32);
		assertEquals(EXPECTED_R, rData.toHexString());
		assertEquals(EXPECTED_S, sData.toHexString());

	}
}