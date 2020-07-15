package convex.core.crypto;

import convex.core.Constants;
import convex.core.data.Address;
import convex.core.data.SignedData;

/**
 * Abstract base class for key pairs in Convex.
 * 
 * Intended as a lightweight container for underlying crypto primitives.
 */
public abstract class AKeyPair {

	/**
	 * Gets the Address of this KeyPair
	 * @return Address for this KeyPair
	 */
	public abstract Address getAddress();

	/**
	 * Signs a value with this key pair 
	 * @param <R>
	 * @param value Value to sign. Can be any valid CVM value.
	 * @return Signed Data Object
	 */
	public abstract <R> SignedData<R> signData(R value);

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
		if (Constants.USE_ED25519) {
			return Ed25519KeyPair.createSeeded(seed);
		} else {
			return ECDSAKeyPair.createSeeded(seed);
		}
	}
	
	static {
		Providers.init();
	}

	public static AKeyPair generate() {
		if (Constants.USE_ED25519) {
			return Ed25519KeyPair.generate();
		} else {
			return ECDSAKeyPair.generate();
		}
	}

	public static AKeyPair create(byte[] keyMaterial) {
		if (Constants.USE_ED25519) {
			return Ed25519KeyPair.create(keyMaterial);
		} else {
			return ECDSAKeyPair.create(keyMaterial);
		}
	}
}
