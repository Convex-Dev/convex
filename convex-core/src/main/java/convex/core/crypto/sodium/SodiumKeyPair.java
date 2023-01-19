package convex.core.crypto.sodium;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.util.Utils;

/**
 * Class representing an Ed25519 Key Pair using Sodium library
 */
public class SodiumKeyPair extends AKeyPair {
	private static final int SECRET_LENGTH=64;

	private final AccountKey publicKey;
	private KeyPair keyPair=null;
	private final Blob seed;
	
	private final byte[] secretKeyBytes;

	private SodiumKeyPair(AccountKey pk, Blob seed, byte[] skBytes) {
		this.publicKey=pk;
		this.seed=seed;
		this.secretKeyBytes=skBytes;
	}
	
	public static SodiumKeyPair create(Blob seed) {
		if (seed.count() != SEED_LENGTH) throw new IllegalArgumentException("256 bit private key material expected as seed!");

		byte[] secretKeyBytes=new byte[SECRET_LENGTH];
		byte[] pkBytes=new byte[AccountKey.LENGTH];
		SodiumProvider.SODIUM_SIGN.cryptoSignSeedKeypair(pkBytes, secretKeyBytes, seed.getBytes());
		AccountKey publicKey=AccountKey.wrap(pkBytes);
		return new SodiumKeyPair(publicKey,seed,secretKeyBytes);
	}

	/**
	 * Generates a new, secure random key pair. Uses a Java SecureRandom instance.
	 *
	 * @return New Key Pair instance.
	 */
	public static SodiumKeyPair generate() {
		return generate(new SecureRandom());
	}

	/**
	 * Create a KeyPair from a JCA KeyPair
	 * @param keyPair JCA KeyPair
	 * @return AKeyPair instance
	 */
	protected static SodiumKeyPair create(KeyPair keyPair) {
		Blob seed=extractSeed(keyPair.getPrivate());
		return create(seed);
	}

	private static Blob extractSeed(PrivateKey private1) {
		byte[] data=private1.getEncoded();
		int n=data.length;
		Blob seed=Blob.wrap(data,n-SEED_LENGTH,SEED_LENGTH);
		return seed;
	}

	/**
	 * Creates an Ed25519 Key Pair with the specified keys
	 * @param publicKey Public key
	 * @param privateKey Private key
	 * @return Key Pair instance
	 */
	public static SodiumKeyPair create(PublicKey publicKey, PrivateKey privateKey) {
		KeyPair keyPair=new KeyPair(publicKey,privateKey);
		return create(keyPair);
	}

	/**
	 * Create a key pair given a public AccountKey and a encoded Blob
	 * @param accountKey Public Key
	 * @param encodedPrivateKey Encoded PKCS8 Private key
	 * @return AKeyPair instance
	 */
	public static SodiumKeyPair create(AccountKey accountKey, Blob encodedPrivateKey) {
		PublicKey publicKey= publicKeyFromBytes(accountKey.getBytes());
		PrivateKey privateKey=privateKeyFromBlob(encodedPrivateKey);
		return create(publicKey,privateKey);
	}

	@Override
	public Blob getSeed() {
		return seed;
	}
	
	/**
	 * Generates a secure random key pair
	 * @param random A secure random instance
	 * @return New key pair
	 */
	public static SodiumKeyPair generate(SecureRandom random) {
		Blob seed=Blob.createRandom(random, 32);
		return create(seed);
	}

	/**
	 * Create a KeyPair from given private key. Public key is generated
	 * automatically from the private key
	 *
	 * @param privateKey An PrivateKey item for private key
	 * @return A new key pair using the given private key
	 */
	public static SodiumKeyPair create(PrivateKey privateKey) {
		Ed25519PrivateKeyParameters privateKeyParam = new Ed25519PrivateKeyParameters(privateKey.getEncoded(), 16);
		Ed25519PublicKeyParameters publicKeyParam = privateKeyParam.generatePublicKey();
		PublicKey generatedPublicKey = publicKeyFromBytes(publicKeyParam.getEncoded());
		// PrivateKey generatedPrivateKey = privateFromBytes(privateKeyParam.getEncoded());
		return create(generatedPublicKey, privateKey);
	}

	/**
	 * Extracts an Address from an Ed25519 public key
	 * @param publicKey Public key
	 * @return
	 */
	static AccountKey extractAccountKey(PublicKey publicKey) {
		byte[] bytes=publicKey.getEncoded();
		int n=bytes.length;
		// take the bytes at the end of the encoding
		return AccountKey.wrap(bytes,n-AccountKey.LENGTH);
	}

	/**
	 * Gets a Ed25519 Private Key from a 32-byte array.
	 * @param privKey
	 * @return
	 */
	static PrivateKey privateFromBytes(byte[] privKey) {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			PrivateKeyInfo privKeyInfo = new PrivateKeyInfo(new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), new DEROctetString(privKey));

			var pkcs8KeySpec = new PKCS8EncodedKeySpec(privKeyInfo.getEncoded());

	        PrivateKey result = keyFactory.generatePrivate(pkcs8KeySpec);
	        return result;
		} catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@Override
	public Blob getEncodedPrivateKey() {
		return extractPrivateKey(getPrivate());
	}

	/**
	 * Extracts an Blob containing the private key data from an Ed25519 private key
	 *
	 * SECURITY: Be careful with this Blob!
	 *
	 * @param publicKey Public key
	 * @return
	 */
	static Blob extractPrivateKey(PrivateKey privateKey) {
		byte[] bytes=privateKey.getEncoded();
		return Blob.wrap(bytes);
	}

	private static PrivateKey privateKeyFromBlob(Blob encodedKey) {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(encodedKey.getBytes());
			PrivateKey privateKey = keyFactory.generatePrivate(pkcs8KeySpec);
			return privateKey;
		} catch (Exception e) {
			throw Utils.sneakyThrow(e);
		}
	}

	/**
	 * Creates a private key using the given raw bytes.
	 * @param key 32 bytes private key data
	 * @return Ed25519 Private Key instance
	 */
	public static PrivateKey privateKeyFromBytes(byte[] key) {
		try {
			KeyFactory keyFactory = KeyFactory.getInstance(ED25519);
			PrivateKeyInfo privKeyInfo = new PrivateKeyInfo(new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519),
					new DEROctetString(key));
			PKCS8EncodedKeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(privKeyInfo.getEncoded());
			PrivateKey privateKey = keyFactory.generatePrivate(pkcs8KeySpec);
			return privateKey;
		} catch (Exception e) {
			throw new Error(e);
		}
	}



	@Override
	public KeyPair getJCAKeyPair() {
		if (keyPair==null) {
			PublicKey pub=publicKeyFromBytes(publicKey.getBytes());
			PrivateKey priv=privateKeyFromBytes(seed.getBytes());
			keyPair=new KeyPair(pub,priv);
		}
		return keyPair;
	}

	@Override
	public PrivateKey getPrivate() {
		return getJCAKeyPair().getPrivate();
	}

	@Override
	public AccountKey getAccountKey() {
		return publicKey;
	}


	@Override
	public <R extends ACell> SignedData<R> signData(R value) {
		return SignedData.create(this, value);
	}

	@Override
	public ASignature sign(Hash hash) {
		byte[] signature=new byte[Ed25519Signature.SIGNATURE_LENGTH];
		if (SodiumProvider.SODIUM_SIGN.cryptoSignDetached(
				signature,
				hash.getBytes(),
				Hash.LENGTH,
				secretKeyBytes)) {;
				return Ed25519Signature.wrap(signature);
		} else {
			throw new Error("Signing failed!");
		}

//		try {
//			Signature signer = Signature.getInstance(ED25519);
//			signer.initSign(getPrivate());
//			signer.update(hash.getInternalArray(), hash.getInternalOffset(), Hash.LENGTH);
//			byte[] signature = signer.sign();
//			return Ed25519Signature.wrap(signature);
//		} catch (GeneralSecurityException e) {
//			throw new Error(e);
//		}
	}

	@Override
	public boolean equals(AKeyPair kp) {
		if (!(kp instanceof SodiumKeyPair)) return false;
		return equals((SodiumKeyPair) kp);
	}

	public boolean equals(SodiumKeyPair other) {
		if (!this.seed.equals(other.seed)) return false;
		if (!this.publicKey.equals(other.publicKey)) return false;
		return true;
	}

}
