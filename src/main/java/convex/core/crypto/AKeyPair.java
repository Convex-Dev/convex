package convex.core.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;

/**
 * Abstract base class for key pairs in Convex.
 * 
 * Intended as a lightweight container for underlying crypto primitives.
 */
public abstract class AKeyPair {

	/**
	 * Gets the Account Public Key of this KeyPair
	 * @return AccountKey for this KeyPair
	 */
	public abstract AccountKey getAccountKey();
	
	/**
	 * Gets the Private key encoded as a Blob
	 * @return Blob Private key data encoding
	 */
	public abstract Blob getEncodedPrivateKey();

	/**
	 * Signs a value with this key pair 
	 * @param <R>
	 * @param value Value to sign. Can be any valid CVM value.
	 * @return Signed Data Object
	 */
	public abstract <R extends ACell> SignedData<R> signData(R value);

	@Override
	public abstract boolean equals(Object a);

	/**
	 * Signs a hash value with this key pair, producing a signature of the appropriate type.
	 * @param hash
	 * @return A Signature compatible with the key pair.
	 */
	public abstract ASignature sign(Hash hash);

	/**
	 * Create a deterministic key pair with the given seed.
	 * 
	 * SECURITY: Never use this for valuable keys or real assets: intended for deterministic testing only.
	 * @param seed Any long value. The same seed will produce the same key pair.
	 * @return New key pair
	 */
	public static AKeyPair createSeeded(long seed) {
		return Ed25519KeyPair.createSeeded(seed);
	}
	
	/**
	 * Create a key pair with the given Address and encoded private key
	 * 
	 * SECURITY: Never use this for valuable keys or real assets: intended for deterministic testing only.
	 * @param seed Any long value. The same seed will produce the same key pair.
	 * @return New key pair
	 */
	public static AKeyPair create(AccountKey address, Blob encodedPrivateKey) {
		return Ed25519KeyPair.create(address,encodedPrivateKey);
	}
	
	static {
		Providers.init();
	}

	/**
	 * Generates a new, secure random key pair. Uses a Java SecureRandom instance.
	 * 
	 * @return New Key Pair instance.
	 */
	public static AKeyPair generate() {
		return Ed25519KeyPair.generate();
	}

	public static AKeyPair create(byte[] keyMaterial) {
		return Ed25519KeyPair.create(keyMaterial);
	}

	public abstract PrivateKey getPrivate();

	public abstract PublicKey getPublic();
	
	@Override
	public String toString() {
		return getAccountKey()+":"+getEncodedPrivateKey();
	}
}
