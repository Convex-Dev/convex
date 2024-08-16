package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	
	static AKeyPair KP = AKeyPair.createSeeded(156858);

	@Test
	public void testPEMPrivateKey() throws Exception {
		AKeyPair keyPair = KP;

		String testPassword = "test-password";
		String pemText = null;
		pemText = PEMTools.encryptPrivateKeyToPEM(keyPair, testPassword.toCharArray());

		assertTrue(pemText != null);

		AKeyPair importKeyPair = PEMTools.decryptPrivateKeyFromPEM(pemText, testPassword.toCharArray());
		AString data = Strings.create(generateRandomHex(1024));
		ASignature leftSignature = keyPair.sign(data.getHash());
		ASignature rightSignature = importKeyPair.sign(data.getHash());
		assertTrue(leftSignature.equals(rightSignature));

	    Blob seed1 = keyPair.getSeed();
		Blob seed2 = importKeyPair.getSeed();
		assertEquals(seed1,seed2);
	}
	
	public static void main(String... args) throws Exception {
		AKeyPair kp = KP;
		System.out.println(kp.getSeed());
		System.out.println(PEMTools.encryptPrivateKeyToPEM(kp,"foo".toCharArray()));
	}
}
