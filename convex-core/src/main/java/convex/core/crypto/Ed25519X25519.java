package convex.core.crypto;

import java.math.BigInteger;
import java.util.Arrays;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.math.ec.rfc8032.Ed25519;

import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.util.Utils;

/**
 * Libsodium-compatible conversion from Ed25519 account keys to X25519 keys.
 */
final class Ed25519X25519 {

	private static final int KEY_LENGTH = 32;
	private static final BigInteger CURVE_25519_PRIME = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

	private Ed25519X25519() {
	}

	/** Converts a validated Ed25519 AccountKey to its X25519 public key. */
	static X25519PublicKeyParameters publicKey(AccountKey accountKey) {
		byte[] edwardsKey = accountKey.getBytes();
		if (!Ed25519.validatePublicKeyFull(edwardsKey, 0)) {
			throw new IllegalArgumentException("Recipient AccountKey is not a valid Ed25519 public key");
		}

		// Ed25519 encodes y in little-endian form, with the sign of x in the top bit.
		// The Edwards-to-Montgomery birational map is u = (1 + y) / (1 - y).
		edwardsKey[31] &= 0x7f;
		BigInteger y = Utils.littleEndianToBigInteger(edwardsKey);
		BigInteger denominator = BigInteger.ONE.subtract(y).mod(CURVE_25519_PRIME);
		BigInteger u = BigInteger.ONE.add(y)
				.multiply(denominator.modInverse(CURVE_25519_PRIME))
				.mod(CURVE_25519_PRIME);
		return new X25519PublicKeyParameters(Utils.bigIntegerToLittleEndian(u, KEY_LENGTH), 0);
	}

	/** Converts an Ed25519 key pair to the matching X25519 key pair. */
	static AsymmetricCipherKeyPair keyPair(AKeyPair keyPair) {
		return keyPair(keyPair.getSeed());
	}

	/**
	 * Converts an Ed25519 seed to the matching X25519 key pair without retaining
	 * the seed or its temporary byte representation.
	 */
	static AsymmetricCipherKeyPair keyPair(Blob seed) {
		if (seed == null) throw new IllegalArgumentException("Ed25519 seed must not be null");
		if (seed.count() != AKeyPair.SEED_LENGTH) throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
		byte[] scalar = scalar(seed);
		try {
			X25519PrivateKeyParameters privateKey = new X25519PrivateKeyParameters(scalar);
			return new AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey);
		} finally {
			Arrays.fill(scalar, (byte) 0);
		}
	}

	private static byte[] scalar(Blob seed) {
		byte[] seedBytes = seed.getBytes();
		byte[] hash = new byte[64];
		SHA512Digest digest = new SHA512Digest();
		try {
			digest.update(seedBytes, 0, seedBytes.length);
			digest.doFinal(hash, 0);
		} finally {
			Arrays.fill(seedBytes, (byte) 0);
		}

		// Match libsodium: SHA-512 the Ed25519 seed, take 32 bytes, then clamp for X25519.
		byte[] scalar = Arrays.copyOf(hash, KEY_LENGTH);
		Arrays.fill(hash, (byte) 0);
		scalar[0] &= (byte) 248;
		scalar[31] &= (byte) 127;
		scalar[31] |= (byte) 64;
		return scalar;
	}
}
