package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.junit.jupiter.api.Test;

import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Utils;

public class ECIESTest {

	private static final AKeyPair RECIPIENT = AKeyPair.createSeeded(1337);

	@Test
	public void testRoundTrip() throws BadFormatException {
		Blob plaintext = Blob.wrap("A secret for the recipient".getBytes(StandardCharsets.UTF_8));

		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), plaintext);

		assertEquals(plaintext.count() + ECIES.OVERHEAD, encrypted.count());
		assertEquals(plaintext, ECIES.decrypt(RECIPIENT, encrypted));
	}

	@Test
	public void testRoundTripFromSeed() throws BadFormatException {
		Blob plaintext = Blob.wrap("seed overload".getBytes(StandardCharsets.UTF_8));
		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), plaintext);

		assertEquals(plaintext, ECIES.decrypt(RECIPIENT.getSeed(), encrypted));
	}

	@Test
	public void testReusableDecryptor() throws BadFormatException {
		ECIES.Decryptor decryptor = ECIES.createDecryptor(RECIPIENT);

		for (int i = 0; i < 10; i++) {
			Blob plaintext = Blob.wrap(("message " + i).getBytes(StandardCharsets.UTF_8));
			Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), plaintext);
			assertEquals(plaintext, decryptor.decrypt(encrypted));
		}

		Blob invalid = ECIES.encrypt(AKeyPair.createSeeded(7331).getAccountKey(), Blob.SINGLE_A);
		assertThrows(BadFormatException.class, () -> decryptor.decrypt(invalid));

		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), Blob.SINGLE_A);
		assertEquals(Blob.SINGLE_A, decryptor.decrypt(encrypted));
	}

	@Test
	public void testReusableDecryptorFromSeed() throws BadFormatException {
		ECIES.Decryptor decryptor = ECIES.createDecryptor(RECIPIENT.getSeed());
		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), Blob.SINGLE_A);

		assertEquals(Blob.SINGLE_A, decryptor.decrypt(encrypted));
	}

	@Test
	public void testEmptyPlaintext() throws BadFormatException {
		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), Blob.EMPTY);

		assertEquals(ECIES.OVERHEAD, encrypted.count());
		assertEquals(Blob.EMPTY, ECIES.decrypt(RECIPIENT, encrypted));
	}

	@Test
	public void testLargePlaintext() throws BadFormatException {
		Blob plaintext = Blob.createRandom(new java.util.Random(1234), 100_000);

		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), plaintext);

		assertEquals(plaintext, ECIES.decrypt(RECIPIENT, encrypted));
	}

	@Test
	public void testEncryptionIsRandomised() throws BadFormatException {
		Blob plaintext = Blob.wrap("same plaintext".getBytes(StandardCharsets.UTF_8));

		Blob first = ECIES.encrypt(RECIPIENT.getAccountKey(), plaintext);
		Blob second = ECIES.encrypt(RECIPIENT.getAccountKey(), plaintext);

		assertFalse(first.equals(second));
		assertEquals(plaintext, ECIES.decrypt(RECIPIENT, first));
		assertEquals(plaintext, ECIES.decrypt(RECIPIENT, second));
	}

	@Test
	public void testWrongKeyFails() {
		AKeyPair other = AKeyPair.createSeeded(7331);
		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), Blob.SINGLE_A);

		assertThrows(BadFormatException.class, () -> ECIES.decrypt(other, encrypted));
	}

	@Test
	public void testTamperingFails() {
		Blob encrypted = ECIES.encrypt(RECIPIENT.getAccountKey(), Blob.SINGLE_A);

		assertTamperingFails(encrypted, 0); // encapsulated key
		assertTamperingFails(encrypted, 32); // ciphertext
		assertTamperingFails(encrypted, encrypted.count() - 1); // authentication tag
	}

	@Test
	public void testMalformedEnvelopeFails() {
		assertThrows(BadFormatException.class,
				() -> ECIES.decrypt(RECIPIENT, Blob.wrap(new byte[ECIES.OVERHEAD - 1])));

		byte[] bytes = ECIES.encrypt(RECIPIENT.getAccountKey(), Blob.EMPTY).getBytes();
		Arrays.fill(bytes, 0, 32, (byte) 0);
		assertThrows(BadFormatException.class, () -> ECIES.decrypt(RECIPIENT, Blob.wrap(bytes)));
	}

	@Test
	public void testInvalidAccountKeyFails() {
		assertThrows(IllegalArgumentException.class, () -> ECIES.encrypt(AccountKey.ZERO, Blob.EMPTY));
	}

	@Test
	public void testInvalidArgumentsFail() {
		assertThrows(IllegalArgumentException.class, () -> ECIES.encrypt(null, Blob.EMPTY));
		assertThrows(IllegalArgumentException.class, () -> ECIES.encrypt(RECIPIENT.getAccountKey(), null));
		assertThrows(IllegalArgumentException.class, () -> ECIES.decrypt((AKeyPair) null, Blob.EMPTY));
		assertThrows(IllegalArgumentException.class, () -> ECIES.decrypt(RECIPIENT, null));
		assertThrows(IllegalArgumentException.class, () -> ECIES.decrypt((Blob) null, Blob.EMPTY));
		assertThrows(IllegalArgumentException.class, () -> ECIES.decrypt(Blob.EMPTY, Blob.EMPTY));
		assertThrows(IllegalArgumentException.class, () -> ECIES.createDecryptor((AKeyPair) null));
		assertThrows(IllegalArgumentException.class, () -> ECIES.createDecryptor((Blob) null));
		assertThrows(IllegalArgumentException.class, () -> ECIES.createDecryptor(Blob.EMPTY));
		assertThrows(IllegalArgumentException.class, () -> ECIES.createDecryptor(RECIPIENT).decrypt(null));
	}

	@Test
	public void testRFC9180AppendixA1Vector() throws BadFormatException {
		// RFC 9180 Appendix A.1.1, sequence number 0:
		// https://www.rfc-editor.org/rfc/rfc9180.html#appendix-A.1.1
		byte[] recipientPrivate = hex("4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8");
		byte[] recipientPublic = hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
		byte[] ephemeralPrivate = hex("52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736");
		byte[] encapsulatedKey = hex("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431");
		byte[] info = hex("4f6465206f6e2061204772656369616e2055726e");
		byte[] aad = hex("436f756e742d30");
		Blob plaintext = Blob.fromHex("4265617574792069732074727574682c20747275746820626561757479");
		Blob expected = Blob.fromHex(
				"37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431" +
				"f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a9" +
				"6d8770ac83d07bea87e13c512a");

		X25519PublicKeyParameters recipientPublicKey = new X25519PublicKeyParameters(recipientPublic);
		X25519PrivateKeyParameters ephemeralPrivateKey = new X25519PrivateKeyParameters(ephemeralPrivate);
		AsymmetricCipherKeyPair ephemeralKeyPair = new AsymmetricCipherKeyPair(
				new X25519PublicKeyParameters(encapsulatedKey), ephemeralPrivateKey);

		Blob encrypted = ECIES.encryptHPKE(recipientPublicKey, plaintext, info, aad, ephemeralKeyPair);
		assertEquals(expected, encrypted);

		X25519PrivateKeyParameters recipientPrivateKey = new X25519PrivateKeyParameters(recipientPrivate);
		AsymmetricCipherKeyPair recipientKeyPair = new AsymmetricCipherKeyPair(recipientPublicKey,
				recipientPrivateKey);
		assertEquals(plaintext, ECIES.decryptHPKE(recipientKeyPair, expected, info, aad));
	}

	private static void assertTamperingFails(Blob encrypted, long index) {
		byte[] bytes = encrypted.getBytes();
		bytes[(int) index] ^= 1;
		assertThrows(BadFormatException.class, () -> ECIES.decrypt(RECIPIENT, Blob.wrap(bytes)));
	}

	private static byte[] hex(String value) {
		return Utils.hexToBytes(value);
	}
}
