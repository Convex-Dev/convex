package convex.core.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

public class PFXUtils {
	public static final String KEYSTORE_TYPE="PKCS12";

	/**
	 * Creates a new PKCS12 Key store
	 * @param keyFile
	 * @param passPhrase
	 * @throws KeyStoreException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 * @throws CertificateException
	 */
	public static void createStore(File keyFile, String passPhrase) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
		
		char[] pwdArray = (passPhrase==null)?null:passPhrase.toCharArray();
		ks.load(null, pwdArray);
		
		try (FileOutputStream fos = new FileOutputStream(keyFile)) {
		    ks.store(fos, pwdArray);
		}
	}
	
	/**
	 * Loads an existing PKCS12 Key store
	 */
	public static KeyStore loadStore(File keyFile, String passPhrase) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
		
		char[] pwdArray = (passPhrase==null)?null:passPhrase.toCharArray();
		ks.load(new FileInputStream(keyFile), pwdArray);
		
		return ks;
	}
}
