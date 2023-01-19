package convex.core.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


/**
 * Utility class for working with Java Key Stores
 */
public class PFXTools {

	public static final String KEYSTORE_TYPE="PKCS12";

	private static char[] password(String passPhrase) {
		return (passPhrase == null) ? (new char[0]) : passPhrase.toCharArray();
	}

	/**
	 * Creates a new PKCS12 key store.
	 * @param keyFile File to use for creating the key store
	 * @param passPhrase Passphrase used to protect the key store, may be null
	 * @return New KeyStore instance
	 */
	@SuppressWarnings("javadoc")
	public static KeyStore createStore(File keyFile, String passPhrase) throws GeneralSecurityException, IOException {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);

		// need to load in bouncy castle crypto providers to set/get keys from the keystore
		Providers.init();

		char[] pwdArray = password(passPhrase);
		ks.load(null, pwdArray); // create empty keystore

		ks=saveStore(ks,keyFile,passPhrase);
		return ks;
	}

	/**
	 * Loads an existing PKCS12 Key store.
	 * @param keyFile File for the existing key store
	 * @param passPhrase Passphrase for decrypting the key store. May be blank or null if not encrypted.
	 * @return Found key store
	 * @throws IOException If an IO error occurs
	 * @throws GeneralSecurityException If a security error occurs
	 */
	public static KeyStore loadStore(File keyFile, String passPhrase) throws IOException,GeneralSecurityException {
		// Need to load in bouncy castle crypto providers to set/get keys from the keystore.
		Providers.init();

		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);

		char[] pwdArray = password(passPhrase);
		try (FileInputStream fis = new FileInputStream(keyFile)) {
			ks.load(fis, pwdArray);
		} 
		return ks;
	}

	/**
	 * Saves a PKCS12 Key store to disk.
	 * @param ks Key store to save
	 * @param keyFile Target file
	 * @param passPhrase Passphrase for encrypting the key store. May be blank or null if not need for encryption.
	 * @return Same key store instance.
	 * @throws IOException If an IO error occurs accessing the key store
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore saveStore(KeyStore ks, File keyFile, String passPhrase) throws GeneralSecurityException, IOException {

		char[] pwdArray = password(passPhrase);

		File parent = keyFile.getParentFile();
		if (parent != null) parent.mkdirs();

		try (FileOutputStream fos = new FileOutputStream(keyFile)) {
		    ks.store(fos, pwdArray);
		}

		return ks;
	}

	/**
	 * Retrieves a key pair from a key store.
	 * @param ks Key store
	 * @param alias Alias used for finding the key pair in the store
	 * @param passphrase Passphrase used for decrypting the key pair. Mandatory.
	 * @return Found key pair
	 * @throws UnrecoverableKeyException If key cannot be recovered
	 * @throws KeyStoreException If a general key store exception occurs
	 * @throws NoSuchAlgorithmException If crypto algorithm is not available
	 */
	public static AKeyPair getKeyPair(KeyStore ks, String alias, String passphrase) throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException {
		char[] pwdArray = passphrase.toCharArray();

		Key sk=ks.getKey(alias,pwdArray);
		return AKeyPair.create(sk.getEncoded());
	}

	/**
	 * Adds a key pair to a key store.
	 * @param ks Key store
	 * @param kp Key pair
	 * @param passPhrase Passphrase for encrypting the key pair. Mandatory.
	 * @return Updated key store.
	 * @throws IOException If an IO error occurs accessing the key store
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore setKeyPair(KeyStore ks, AKeyPair kp, String passPhrase) throws IOException, GeneralSecurityException {

		return setKeyPair(ks, kp.getAccountKey().toHexString(), kp, passPhrase);
	}

	/**
	 * Adds a key pair to a key store.
	 * @param ks Key store
	 * @param alias Alias entry for keystore
	 * @param kp Key pair
	 * @param passPhrase Passphrase for encrypting the key pair. Mandatory.
	 * @return Updated key store.
	 * @throws IOException If an IO error occurs accessing the key store
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore setKeyPair(KeyStore ks, String alias, AKeyPair kp, String passPhrase) throws IOException, GeneralSecurityException {

		if (passPhrase == null) throw new IllegalArgumentException("Password is mandatory for private key");
		char[] pwdArray = passPhrase.toCharArray();

		byte[] bs=((AKeyPair)kp).getSeed().getBytes();
		SecretKey secretKeyPrivate = new SecretKeySpec(bs, "Ed25519");
		ks.setKeyEntry(alias, secretKeyPrivate, pwdArray, null);

		return ks;
	}

}
