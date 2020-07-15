package convex.core.crypto;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Random;

import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;

import convex.core.data.Address;
import convex.core.data.SignedData;
import convex.core.util.Utils;

/**
 * Key pair for signing using ECDSA secp256k1 curve
 * 
 * Stores public and private keys as positive BigIntegers
 * 
 * SECURITY: not IWriteable so these don't get included in messages
 * accidentally!
 * 
 * "Being able to break security doesn’t make you a hacker anymore than being
 * able to hotwire cars makes you an automotive engineer." - Eric Raymond
 */
public class ECDSAKeyPair extends AKeyPair {
	private final BigInteger privateKey;
	private final BigInteger publicKey;

	private Address address = null;

	private ECDSAKeyPair(BigInteger privateKey, BigInteger publicKey) {
		this.privateKey = privateKey;
		this.publicKey = publicKey;

		if (privateKey.compareTo(ECDSASignature.N) >= 0) {
			throw new Error("Unexpectedly large private key?");
		}

	}

	public ECDSAKeyPair(byte[] privateKey, byte[] publicKey) {
		this(Utils.toBigInteger(privateKey), Utils.toBigInteger(publicKey));
	}

	@Override
	public Address getAddress() {
		Address a = address;
		if (a != null) return a;
		address = Address.fromPublicKey(publicKey);
		return address;
	}

	public BigInteger getPrivateKey() {
		return privateKey;
	}

	public BigInteger getPublicKey() {
		return publicKey;
	}

	/**
	 * Generate a new key pair with the given secure random provider
	 * 
	 * @param secureRandom A secure random number generator instance.
	 * @return A new key pair
	 */
	public static ECDSAKeyPair generate(SecureRandom secureRandom) {
		// Use ECDSA with secp256k1 via BouncyCastle provider
		KeyPairGenerator generator;
		try {
			generator = KeyPairGenerator.getInstance("ECDSA","BC");
		} catch (NoSuchAlgorithmException e) {
			throw new Error("Algorithm not available", e);
		} catch (NoSuchProviderException e) {
			throw new Error("Provider not available", e);
		}
		ECGenParameterSpec paramSpec = new ECGenParameterSpec(ECDSASignature.CURVE_SPEC);

		// initialise with secure random
		try {
			generator.initialize(paramSpec, secureRandom);
		} catch (InvalidAlgorithmParameterException e) {
			throw new Error("Invalid algorithm parameter", e);
		}

		// generate keypair and convert to SignKeyPair format
		KeyPair kp = generator.generateKeyPair();
		return create(kp);
	}

	/**
	 * Generate a new key pair with a defualt secure random generator
	 * 
	 * @return A new key pair
	 */
	public static ECDSAKeyPair generate() {
		return generate(new SecureRandom());
	}

	/**
	 * Create a SignKeyPair from a given private key. Public key is generated
	 * automatically from the private key
	 * 
	 * @param privateKey
	 * @return A new key pair using the given private key
	 */
	public static ECDSAKeyPair create(BigInteger privateKey) {
		return new ECDSAKeyPair(privateKey, ECDSASignature.publicKeyFromPrivate(privateKey));
	}

	/**
	 * Create a SignKeyPair from a KeyPair, assuming BCEC
	 */
	public static ECDSAKeyPair create(KeyPair kp) {
		BCECPrivateKey privateKey = (BCECPrivateKey) kp.getPrivate();
		BCECPublicKey publicKey = (BCECPublicKey) kp.getPublic();

		BigInteger priv = privateKey.getD();

		// public key is encoded, we want to convert to 64-byte BigInteger
		byte[] enc = publicKey.getQ().getEncoded(false);
		// skip header byte
		int eLength = enc.length;
		assert (eLength == 65);
		BigInteger pub = new BigInteger(1, Arrays.copyOfRange(enc, 1, eLength));

		return new ECDSAKeyPair(priv, pub);
	}

	/**
	 * Create a SignKeyPair from given private key material. Public key is generated
	 * automatically from the private key
	 * 
	 * @param keyMaterial An array of 32 bytes of random material to use for private key
	 * @return A new key pair using the given private key
	 */
	public static ECDSAKeyPair create(byte[] keyMaterial) {
		if (keyMaterial.length != 32) throw new IllegalArgumentException("256 bit private key material expected!");
		return create(Utils.toBigInteger(keyMaterial));
	}

	/**
	 * Create a SignKeyPair deterministically using a seed.
	 * 
	 * Not secure. Intended for testing purposes. DO NOT use to protect valuable
	 * assets.
	 * 
	 * @param seed
	 * @return A new key pair generated with the given seed.
	 */
	public static ECDSAKeyPair createSeeded(long seed) {
		Random r = new Random(seed);
		byte[] privateKey = new byte[32];
		BigInteger pk = null;
		do {
			r.nextBytes(privateKey);
			pk = Utils.toBigInteger(privateKey);
		} while ((pk.signum() <= 0) || (pk.compareTo(ECDSASignature.N) >= 0));
		return create(pk);
	}

	// generic hashcode and equals methods

	@Override
	public int hashCode() {
		// simple hashcode calculation
		// TODO: maybe should cache this?
		return 31 * privateKey.hashCode() + publicKey.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof ECDSAKeyPair)) return false;
		return equals((ECDSAKeyPair) o);
	}

	public boolean equals(ECDSAKeyPair ecKeyPair) {
		if (!privateKey.equals(ecKeyPair.privateKey)) return false;
		return publicKey.equals(ecKeyPair.publicKey);
	}

	static {
		ECDSASignature.init();
	}

	public <R> SignedData<R> signData(R o) {
		return SignedData.create(this, o);
	}

	@Override
	public ASignature sign(Hash hash) {
		return ECDSASignature.sign(hash, this);
	}

}