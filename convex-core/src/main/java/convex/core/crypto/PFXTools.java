package convex.core.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.SecretKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

import javax.crypto.SecretKey;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import convex.core.Constants;
import convex.core.util.FileUtils;


/**
 * Utility class for working with Java Key Stores
 */
public class PFXTools {

	public static final String KEYSTORE_TYPE="PKCS12";

	/**
	 * Creates a new PKCS12 key store.
	 * @param keyFile File to use for creating the key store
	 * @param storePassword Passphrase used to protect the key store, may be null
	 * @return New KeyStore instance
	 */
	public static KeyStore createStore(File keyFile, char[] storePassword) throws GeneralSecurityException, IOException {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);

		// need to load in bouncy castle crypto providers to set/get keys from the keystore
		Providers.init();

		ks.load(null, storePassword); // create empty keystore

		ks=saveStore(ks,keyFile,storePassword);
		return ks;
	}

	/**
	 * Loads an existing PKCS12 Key store.
	 * @param keyFile File for the existing key store
	 * @param storePassword Passphrase for integrity check of the key store. May be blank. If null, no integrity check is performed.
	 * @return Found key store
	 * @throws IOException If an IO error occurs
	 * @throws GeneralSecurityException If a security error occurs
	 */
	public static KeyStore loadStore(File keyFile, char[] storePassword) throws IOException,GeneralSecurityException {
		// Need to load in bouncy castle crypto providers to set/get keys from the keystore.
		Providers.init();

		if (!keyFile.exists()) throw new FileNotFoundException();
		
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);

		try (FileInputStream fis = new FileInputStream(keyFile)) {
			ks.load(fis, storePassword);
		} 
		return ks;
	}

	/**
	 * Saves a PKCS12 Key store to disk.
	 * @param ks Key store to save
	 * @param keyFile Target file
	 * @param storePassword Passphrase for encrypting the key store. May be blank or null if not need for encryption.
	 * @return Same key store instance.
	 * @throws IOException If an IO error occurs accessing the key store
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore saveStore(KeyStore ks, File keyFile, char[] storePassword) throws GeneralSecurityException, IOException {
		FileUtils.ensureFilePath(keyFile);

		try (FileOutputStream fos = new FileOutputStream(keyFile)) {
		    ks.store(fos, storePassword);
		}

		return ks;
	}

	/**
	 * Retrieves a key pair from a key store.
	 * @param ks Key store
	 * @param alias Alias used for finding the key pair in the store
	 * @param keyPassword Passphrase used for decrypting the key pair. Mandatory.
	 * @return Found key pair
	 * @throws UnrecoverableKeyException If key cannot be recovered
	 * @throws KeyStoreException If a general key store exception occurs
	 * @throws NoSuchAlgorithmException If crypto algorithm is not available
	 */
	public static AKeyPair getKeyPair(KeyStore ks, String alias, char[] keyPassword) throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException {

		Key sk=ks.getKey(alias,keyPassword);
		return AKeyPair.create(sk.getEncoded());
	}

	/**
	 * Adds a key pair to a key store.
	 * @param ks Key store
	 * @param kp Key pair
	 * @param keyPassword Passphrase for encrypting the key pair. Mandatory.
	 * @return Updated key store.
	 * @throws IOException If an IO error occurs accessing the key store
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore setKeyPair(KeyStore ks, AKeyPair kp, char[] keyPassword) throws IOException, GeneralSecurityException {

		return setKeyPair(ks, kp.getAccountKey().toHexString(), kp, keyPassword);
	}

	/**
	 * Adds a key pair to a key store.
	 * @param ks Key store
	 * @param alias Alias entry for keystore
	 * @param kp Key pair
	 * @param keyPassword Password for encrypting the key pair. Mandatory.
	 * @return Updated key store.
	 * @throws IOException If an IO error occurs accessing the key store
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore setKeyPair(KeyStore ks, String alias, AKeyPair kp, char[] keyPassword) throws IOException, GeneralSecurityException {

		if (keyPassword == null) throw new IllegalArgumentException("Password is mandatory for private key");

		byte[] bs=((AKeyPair)kp).getSeed().getBytes();
		SecretKey secretKeySeed = new SecretKeySpec(bs, "Ed25519");
		
		// See https://neilmadden.blog/2017/11/17/java-keystores-the-gory-details/
		SecretKeyEntry keyEntry=new SecretKeyEntry(secretKeySeed);
		byte[] salt=new byte[20];
		
		PasswordProtection protection= new PasswordProtection(keyPassword,
                "PBEWithHmacSHA512AndAES_128",
                new PBEParameterSpec(salt, Constants.PBE_ITERATIONS));
		ks.setEntry(alias, keyEntry, protection);
		// ks.setKeyEntry(alias, secretKeySeed, keyPassword, null);

		return ks;
	}

}
