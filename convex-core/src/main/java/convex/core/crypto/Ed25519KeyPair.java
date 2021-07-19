package convex.core.crypto;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

public class Ed25519KeyPair extends AKeyPair {

	private final AccountKey publicKey;
	private final KeyPair keyPair;
	
	public static final int PRIVATE_KEY_LENGTH=32;
	private static final String ED25519 = "Ed25519";

	private Ed25519KeyPair(KeyPair kp, AccountKey publicKey) {
		this.keyPair = kp;
		this.publicKey=publicKey;
	}

	/**
	 * Generates a new, secure random key pair. Uses a Java SecureRandom instance.
	 * 
	 * @return New Key Pair instance.
	 */
	public static Ed25519KeyPair generate() {
		return generate(new SecureRandom());
	}
	
	public static Ed25519KeyPair create(KeyPair keyPair) {
		AccountKey address=extractAccountKey(keyPair.getPublic());
		return new Ed25519KeyPair(keyPair,address);
	}
	
	public static Ed25519KeyPair create(PublicKey publicKey, PrivateKey privateKey) {
		KeyPair keyPair=new KeyPair(publicKey,privateKey);
		return create(keyPair);
	}
	
	public static Ed25519KeyPair create(AccountKey accountKey, Blob encodedPrivateKey) {
		PublicKey publicKey= publicKeyFromBytes(accountKey.getBytes());
		PrivateKey privateKey=privateKeyFromBlob(encodedPrivateKey);
		return create(publicKey,privateKey);
	}

	public static Ed25519KeyPair generate(SecureRandom random) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance(ED25519);
			generator.initialize(256, random);
			KeyPair kp = generator.generateKeyPair();
			return create(kp);
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
	}

	public static Ed25519KeyPair createSeeded(long seed) {
		SecureRandom r = new InsecureRandom(seed);
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance(ED25519);
			generator.initialize(256, r);
			KeyPair kp = generator.generateKeyPair();
			return create(kp);
		} catch (NoSuchAlgorithmException e) {
			throw new Error(e);
		}
	}
	
	/**
	 * Create a SignKeyPair from given private key material. Public key is generated
	 * automatically from the private key
	 * 
	 * @param keyMaterial An array of 32 bytes of random material to use for private key
	 * @return A new key pair using the given private key
	 */
	public static Ed25519KeyPair create(byte[] keyMaterial) {
		if (keyMaterial.length != 32) throw new IllegalArgumentException("256 bit private key material expected!");
		throw new TODOException();
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

	public byte[] getPublicKeyBytes() {
		return getAccountKey().getBytes();
	}
	
	public static PrivateKey privateKeyFromBlob(Blob encodedKey) {
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

	@Override
	public PublicKey getPublic() {
		return keyPair.getPublic();
	}

	@Override
	public PrivateKey getPrivate() {
		return keyPair.getPrivate();
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
		try {
			Signature signer = Signature.getInstance(ED25519);
			signer.initSign(getPrivate());
			signer.update(hash.getInternalArray(), hash.getInternalOffset(), Hash.LENGTH);
			byte[] signature = signer.sign();
			return Ed25519Signature.wrap(signature);
		} catch (SignatureException | NoSuchAlgorithmException | InvalidKeyException e) {
			throw new Error(e);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Ed25519KeyPair)) return false;
		return equals((Ed25519KeyPair) o);
	}

	public boolean equals(Ed25519KeyPair other) {
		if (!keyPair.getPrivate().equals(other.keyPair.getPrivate())) return false;
		return keyPair.getPublic().equals(other.getPublic());
	}


}
