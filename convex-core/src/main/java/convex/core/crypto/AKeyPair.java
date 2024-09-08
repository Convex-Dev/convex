package convex.core.crypto;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.Panic;

/**
 * Abstract base class for key pairs in Convex.
 * 
 * Intended as a lightweight container for underlying crypto primitives.
 */
public abstract class AKeyPair {
	public static final int SEED_LENGTH=32;

	protected static final String ED25519 = "Ed25519";
	
	private KeyPair keyPair=null;

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
	 * Signs a data value with this key pair 
	 * @param <R> Type of value
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
	 * Signs a message with this key pair, producing a signature of the appropriate type.
	 * @param message Message to sign
	 * @return A Signature compatible with the key pair.
	 */
	public abstract ASignature sign(AArrayBlob message);

	/**
	 * Create a deterministic key pair with the given long seed.
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
	
	static {
		Providers.init();
	}

	/**
	 * Generates a new, secure random key pair. Uses a Java SecureRandom instance.
	 * 
	 * @return New Key Pair instance.
	 */
	public static AKeyPair generate() {
		return Providers.generate();
	}

	/**
	 * Creates a key pair using specific key material.
	 * 
	 * @param keyMaterial Bytes to use as key. Last 32 bytes will be used
	 * @return New key pair
	 */
	public static AKeyPair create(byte[] keyMaterial) {
		int n=keyMaterial.length;
		return create(Blob.wrap(keyMaterial,n-SEED_LENGTH,SEED_LENGTH));
	}
	
	/**
	 * Create a key pair with the given Ed25519 seed. Public key is generated
	 * automatically from the private key
	 *
	 * @param ed25519seed 32 bytes of seed material
	 * @return A new key pair using the given seed
	 */
	public static AKeyPair create(Blob ed25519seed) {
		return Providers.generate(ed25519seed);
	}

	/**
	 * Gets the JCA PrivateKey
	 * @return Private Key
	 */
	public PrivateKey getPrivate() {
		try {
			return getJCAKeyPair().getPrivate();
		} catch (BadFormatException e) {
			throw new Panic(e);
		}
	}

	/**
	 * Gets the JCA PublicKey
	 * @return Public Key
	 */
	public PublicKey getPublic() {
		try {
			return getJCAKeyPair().getPublic();
		} catch (BadFormatException e) {
			throw new Panic(e);
		}
	}
	
	@Override
	public String toString() {
		return "Keypair for: "+getAccountKey();
	}

	/**
	 * Gets the JCA representation of this Key Pair
	 * @return JCA KepPair
	 * @throws BadFormatException In case of bad format
	 */
	public KeyPair getJCAKeyPair() throws BadFormatException {
		if (keyPair==null) {
			PublicKey pub=publicKeyFromBytes(getAccountKey().getBytes());
			PrivateKey priv=privateKeyFromBytes(getSeed().getBytes());
			keyPair=new KeyPair(pub,priv);
		}
		return keyPair;
	}
	
	/**
	 * Gets the seed from a JCA Private Key.
	 * Should always be last 32 bytes of the encoding
	 * @param privateKey Private Key in JCA format
	 * @return
	 */
	protected static Blob extractSeed(PrivateKey privateKey) {
		byte[] data=privateKey.getEncoded();
		int n=data.length;
		Blob seed=Blob.wrap(data,n-SEED_LENGTH,SEED_LENGTH);
		return seed;
	}

	/**
	 * Gets the Ed25519 seed for this key pair
	 * @return Seed blob of 32 bytes
	 */
	public abstract Blob getSeed();
	
	/**
	 * Create a KeyPair from given private key. Public key is generated
	 * automatically from the private key
	 *
	 * @param privateKey An PrivateKey item for private key
	 * @return A new key pair using the given private key
	 * @throws BadFormatException If the private key is not in correct format to create a key pair
	 */
	public static AKeyPair create(PrivateKey privateKey) throws BadFormatException {
		Ed25519PrivateKeyParameters privateKeyParam = new Ed25519PrivateKeyParameters(privateKey.getEncoded(), 16);
		Ed25519PublicKeyParameters publicKeyParam = privateKeyParam.generatePublicKey();
		PublicKey generatedPublicKey = publicKeyFromBytes(publicKeyParam.getEncoded());
		// PrivateKey generatedPrivateKey = privateFromBytes(privateKeyParam.getEncoded());
		return create(generatedPublicKey, privateKey);
	}

	/**
	 * Creates an Ed25519 Key Pair with the specified keys
	 * @param publicKey Public key
	 * @param privateKey Private key
	 * @return Key Pair instance
	 */
	public static AKeyPair create(PublicKey publicKey, PrivateKey privateKey) {
		KeyPair keyPair=new KeyPair(publicKey,privateKey);
		return create(keyPair);
	}

	/**
	 * Create a KeyPair from a JCA KeyPair
	 * @param keyPair JCA KeyPair
	 * @return AKeyPair instance
	 */
	public static AKeyPair create(KeyPair keyPair) {
		Blob seed=extractSeed(keyPair.getPrivate());
		return Providers.generate(seed);
	}

	/**
	 * Creates a private key using the given raw bytes.
	 * @param key 32 bytes private key data
	 * @return Ed25519 Private Key instance
	 */
	public static PrivateKey privateKeyFromBytes(byte[] key) throws BadFormatException {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			PrivateKeyInfo privKeyInfo = new PrivateKeyInfo(new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519),
					new DEROctetString(key));
			PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(privKeyInfo.getEncoded());
			PrivateKey privateKey = keyFactory.generatePrivate(pkcs8KeySpec);
			return privateKey;
		} catch (IOException e) {
			throw new BadFormatException(e);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new Panic(e);
		}
	}

	/**
	 * Extracts an AccountKey from an Ed25519 public key
	 * @param publicKey Public key
	 * @return AccountKey instance
	 */
	public static AccountKey extractAccountKey(PublicKey publicKey) {
		byte[] bytes=publicKey.getEncoded();
		int n=bytes.length;
		// take the bytes at the end of the encoding
		return AccountKey.wrap(bytes,n-AccountKey.LENGTH);
	}

	public static PrivateKey privateKeyFromBlob(Blob encodedKey) throws BadFormatException {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(encodedKey.getBytes());
			PrivateKey privateKey = keyFactory.generatePrivate(pkcs8KeySpec);
			return privateKey;
		} catch (InvalidKeySpecException e) {
			throw new BadFormatException("Extracting key from Blob failed",e);
		} catch (NoSuchAlgorithmException e) {
			throw new Panic(e);
		}
	}

	/**
	 * Gets a Ed25519 Private Key from a 32-byte array.
	 * @param privKey Bytes to use as a private key seed
	 * @return PrivateKey instance
	 * @throws GeneralSecurityException if security utilities fail
	 */
	public static PrivateKey privateFromBytes(byte[] privKey) throws GeneralSecurityException {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			PrivateKeyInfo privKeyInfo = new PrivateKeyInfo(new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), new DEROctetString(privKey));
	
			var pkcs8KeySpec = new PKCS8EncodedKeySpec(privKeyInfo.getEncoded());
	
	        PrivateKey result = keyFactory.generatePrivate(pkcs8KeySpec);
	        return result;
		} catch (IOException e ) {
			throw new GeneralSecurityException("IO filure in secure operation",e);
		}
	}

	public static PublicKey publicKeyFromBytes(byte[] key) throws BadFormatException {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			SubjectPublicKeyInfo pubKeyInfo = new SubjectPublicKeyInfo(
					new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), key);
			X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(pubKeyInfo.getEncoded());
			PublicKey publicKey = keyFactory.generatePublic(x509KeySpec);
			return publicKey;
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new Panic(e);
		} catch (IOException e) {
			throw new BadFormatException("Can't get public key from bytes",e);
		} 
	}

}
