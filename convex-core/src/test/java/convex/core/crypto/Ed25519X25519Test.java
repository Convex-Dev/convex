package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.util.Utils;

public class Ed25519X25519Test {

	@Test
	public void testLibsodiumKeyConversion() {
		// Seed and expected secret conversion from libsodium's ed25519_convert test.
		Blob seed = Blob.fromHex("421151a459faeade3d247115f94aedae42318124095afabe4d1451a559faedee");
		AKeyPair ed25519KeyPair = AKeyPair.create(seed);
		byte[] expectedSecret = Utils.hexToBytes(
				"8052030376d47112be7f73ed7a019293dd12ad910b654455798b4667d73de166");

		AsymmetricCipherKeyPair converted = Ed25519X25519.keyPair(ed25519KeyPair);
		byte[] convertedSecret = ((X25519PrivateKeyParameters) converted.getPrivate()).getEncoded();
		byte[] publicFromSecret = ((X25519PublicKeyParameters) converted.getPublic()).getEncoded();
		byte[] publicFromAccountKey = Ed25519X25519.publicKey(ed25519KeyPair.getAccountKey()).getEncoded();

		assertArrayEquals(expectedSecret, convertedSecret);
		assertArrayEquals(publicFromSecret, publicFromAccountKey);
	}

	@Test
	public void testInvalidSeed() {
		assertThrows(IllegalArgumentException.class, () -> Ed25519X25519.keyPair((Blob) null));
		assertThrows(IllegalArgumentException.class, () -> Ed25519X25519.keyPair(Blob.EMPTY));
	}
}
