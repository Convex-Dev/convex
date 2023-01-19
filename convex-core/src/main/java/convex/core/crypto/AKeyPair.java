package convex.core.crypto;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

import convex.core.crypto.sodium.SodiumKeyPair;
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
	public static final int SEED_LENGTH=32;

	protected static final String ED25519 = "Ed25519";


	/**
	 * Gets a new byte array representation of the public key
	 * @return Bytes of public key
	 */
	public final byte[] getPublicKeyBytes() {
		return getAccountKey().getBytes();
	}

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
	 * @param <R> Type of Value
	 * @param value Value to sign. Can be any valid CVM value.
	 * @return Signed Data Object
	 */
	public abstract <R extends ACell> SignedData<R> signData(R value);

	@Override
	public final boolean equals(Object a) {
		if (!(a instanceof AKeyPair)) return false;	
		return equals((AKeyPair)a);
	}
	
	/**
	 * Tests if this keypair is equal to another key pair. Generally, a key pair
	 * should be considered equal if it has the same public key and produces identical signatures
	 * in all cases.
	 * 
	 * @param kp Other key pair to compare with
	 * @return True if key pairs are equal
	 */
	public abstract boolean equals(AKeyPair kp);

	/**
	 * Signs a hash value with this key pair, producing a signature of the appropriate type.
	 * @param hash Hash of value to sign
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
		SecureRandom r = new InsecureRandom(seed);
		Blob seedBlob=Blob.createRandom(r, 32);
		return create(seedBlob);
	}

	
	/**
	 * Create a key pair with the given Address and encoded private key
	 * 
	 * @param publicKey Public Key
	 * @param encodedPrivateKey Encoded private key
	 * @return New key pair
	 */
	public static AKeyPair create(AccountKey publicKey, Blob encodedPrivateKey) {
		return SodiumKeyPair.create(publicKey,encodedPrivateKey);
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
		return SodiumKeyPair.generate();
	}

	/**
	 * Creates a key pair using specific key material.
	 * 
	 * @param keyMaterial Bytes to use as key
	 * @return New key pair
	 */
	public static AKeyPair create(byte[] keyMaterial) {
		return create(Blob.wrap(keyMaterial));
	}
	
	/**
	 * Create a key pair with the given seed. Public key is generated
	 * automatically from the private key
	 *
	 * @param seed 32 bytes of seed material
	 * @return A new key pair using the given seed
	 */
	public static AKeyPair create(Blob seed) {
		// TODO: make switchable
		return SodiumKeyPair.create(seed);
	}

	/**
	 * Gets the JCA PrivateKey
	 * @return Private Key
	 */
	public abstract PrivateKey getPrivate();

	/**
	 * Gets the JCA PublicKey
	 * @return Public Key
	 */
	public PublicKey getPublic() {
		return getJCAKeyPair().getPublic();
	}
	
	@Override
	public String toString() {
		return getAccountKey()+":"+getEncodedPrivateKey();
	}

	/**
	 * Gets the JCA representation of this Key Pair
	 * @return JCA KepPair
	 */
	public abstract KeyPair getJCAKeyPair();

	/**
	 * Gets the Ed25519 seed for this key pair
	 * @return Seed blob of 32 bytes
	 */
	public abstract Blob getSeed();
	
	public static PublicKey publicKeyFromBytes(byte[] key) {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			SubjectPublicKeyInfo pubKeyInfo = new SubjectPublicKeyInfo(
					new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), key);
			X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(pubKeyInfo.getEncoded());
			PublicKey publicKey = keyFactory.generatePublic(x509KeySpec);
			return publicKey;
		} catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException e) {
			throw new Error(e);
		}
	}

}
