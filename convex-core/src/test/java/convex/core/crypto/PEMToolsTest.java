package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.util.Utils;

public class PEMToolsTest {

	String generateRandomHex(int size) {
		SecureRandom random = new SecureRandom();
		byte password[] = new byte[size];
		random.nextBytes(password);
		return Utils.toHexString(password);
	}

	@Test
	public void testPEMPrivateKey() {
		AKeyPair keyPair = Ed25519KeyPair.generate();

		String testPassword = generateRandomHex(32);
		String pemText = null;
		try {
			pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), testPassword.toCharArray());
		} catch (Error e) {
			throw e;
		}

		assertTrue(pemText != null);
		PrivateKey privateKey = null;
		try {
			privateKey = PEMTools.decryptPrivateKeyFromPEM(pemText, testPassword.toCharArray());
		} catch (Error e) {
			throw e;
		}

		AKeyPair importKeyPair = Ed25519KeyPair.create(privateKey);
		AString data = Strings.create(generateRandomHex(1024));
		ASignature leftSignature = keyPair.sign(data.getHash());
		ASignature rightSignature = importKeyPair.sign(data.getHash());
		assertTrue(leftSignature.equals(rightSignature));

        Blob key1 = keyPair.getEncodedPrivateKey();
		Blob key2 = importKeyPair.getEncodedPrivateKey();
		assertTrue(key1.equals(key2));

		assertTrue(keyPair.equals(importKeyPair));
	}
}
