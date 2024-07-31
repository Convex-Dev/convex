package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.PrivateKey;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.util.Utils;

public class PEMToolsTest {

	String generateRandomHex(int size) {
		SecureRandom random = new SecureRandom();
		byte password[] = new byte[size];
		random.nextBytes(password);
		return Utils.toHexString(password);
	}
	
	static AKeyPair KP = AKeyPair.createSeeded(156858);

	@Test
	public void testPEMPrivateKey() {
		AKeyPair keyPair = KP;

		String testPassword = "test-password";
		String pemText = null;
		pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), testPassword.toCharArray());

		assertTrue(pemText != null);
		PrivateKey privateKey = null;

		privateKey = PEMTools.decryptPrivateKeyFromPEM(pemText, testPassword.toCharArray());

		AKeyPair importKeyPair = AKeyPair.create(privateKey);
		AString data = Strings.create(generateRandomHex(1024));
		ASignature leftSignature = keyPair.sign(data.getHash());
		ASignature rightSignature = importKeyPair.sign(data.getHash());
		assertTrue(leftSignature.equals(rightSignature));

		// TODO: fix equality testing
	    // Blob key1 = keyPair.getEncodedPrivateKey();
		// Blob key2 = importKeyPair.getEncodedPrivateKey();
		//assertEquals(key1,key2);
		//(keyPair,importKeyPair);
	}
	
	public static void main(String... args) {
		AKeyPair kp = KP;
		System.out.println(kp.getSeed());
		System.out.println(PEMTools.encryptPrivateKeyToPEM(kp.getPrivate(),"foo".toCharArray()));
	}
}
