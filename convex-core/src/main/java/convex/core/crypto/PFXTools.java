package convex.core.crypto;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Date;

import org.bouncycastle.jce.X509Principal;
import org.bouncycastle.x509.X509V3CertificateGenerator;

@SuppressWarnings("deprecation")
public class PFXTools {
	public static final String KEYSTORE_TYPE="PKCS12";

	public static final String CERTIFICATE_ALGORITHM = "RSA";

	/**
	 * Creates a new PKCS12 Key store.
	 * @param keyFile File to use for key store
	 * @param passPhrase Passphrase for key store
	 * @return New KeyStore instance
	 */
	@SuppressWarnings("javadoc")
	public static KeyStore createStore(File keyFile, String passPhrase) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);

		// need to load in bouncy castle crypto providers to set/get keys from the keystore
		Providers.init();

		char[] pwdArray = (passPhrase==null)?null:passPhrase.toCharArray();
		ks.load(null, pwdArray);

		try (FileOutputStream fos = new FileOutputStream(keyFile)) {
		    ks.store(fos, pwdArray);
		}
		return ks;
	}

	/**
	 * Loads an existing PKCS12 Key store. Passphrase optional, may be blank or null.
	 * @param keyFile File for existing KeyStore
	 * @param passPhrase Passphrase for KeyStore. May be blank or null.
	 * @return KeyStore instance
	 * @throws IOException If an IO error occurs
	 * @throws GeneralSecurityException If a security error occurs
	 */
	public static KeyStore loadStore(File keyFile, String passPhrase) throws IOException,GeneralSecurityException {

		// need to load in bouncy castle crypto providers to set/get keys from the keystore
		Providers.init();

		KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);

		char[] pwdArray = (passPhrase==null)?null:passPhrase.toCharArray();
		try (FileInputStream fis = new FileInputStream(keyFile)) {
			ks.load(fis, pwdArray);
		}
		return ks;
	}

	/**
	 * Saves a PKCS12 Key store to disk. Passphrase optional, may be blank or null.
	 * @param ks KeyStore to save
	 * @param keyFile Target KeyStore file
	 * @param passPhrase Passphrase for KeyStore. May be blank or null.
	 * @return KeyStore instance
	 * @throws IOException If an IO error occurs accessing the Keystore
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore saveStore(KeyStore ks, File keyFile, String passPhrase) throws GeneralSecurityException, IOException {

		char[] pwdArray = (passPhrase==null)?null:passPhrase.toCharArray();
		try (FileOutputStream fos = new FileOutputStream(keyFile)) {
		    ks.store(fos, pwdArray);
		}

		return ks;
	}

	/**
	 * @param kpToSign Key Pair
	 * @return New Certificate
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static Certificate createSelfSignedCertificate(AKeyPair kpToSign) throws GeneralSecurityException {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(CERTIFICATE_ALGORITHM);
		KeyPair kp=keyPairGenerator.generateKeyPair();

		X509V3CertificateGenerator v3CertGen = new X509V3CertificateGenerator();

		String domainName="convex.world";
		v3CertGen.setSerialNumber(BigInteger.valueOf(new SecureRandom().nextInt(Integer.MAX_VALUE)));
        v3CertGen.setIssuerDN(new X509Principal("CN=" + domainName + ", OU=None, O=None L=None, C=None"));
        v3CertGen.setNotBefore(new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30));
        v3CertGen.setNotAfter(new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365*10)));
        v3CertGen.setSubjectDN(new X509Principal("CN=" + domainName + ", OU=None, O=None L=None, C=None"));

        v3CertGen.setPublicKey(kpToSign.getPublic());
        v3CertGen.setSignatureAlgorithm("SHA256WithRSAEncryption");

        Certificate cert = v3CertGen.generateX509Certificate(kp.getPrivate());
		return cert;
	}

	/**
	 * Adds a key to a key store
	 * @param ks Keystore
	 * @param kp Key pair
	 * @param passPhrase Passphrase for key
	 * @return Updated KeyStore
	 * @throws IOException If an IO error occurs accessing the Keystore
	 * @throws GeneralSecurityException if a security exception occurs
	 */
	public static KeyStore saveKey(KeyStore ks, AKeyPair kp, String passPhrase) throws IOException, GeneralSecurityException {
		if (passPhrase==null) throw new IllegalArgumentException("Password is mandatory for private key");
		char[] pwdArray = passPhrase.toCharArray();

		String alias = kp.getAccountKey().toHexString();
		Certificate cert =createSelfSignedCertificate(kp);
		ks.setKeyEntry(alias, kp.getPrivate(), pwdArray, new Certificate[] {cert});

		return ks;
	}

	public static AKeyPair getKeyPair(KeyStore ks, String alias, String passPhrase) throws UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException {
		char[] pwdArray = passPhrase.toCharArray();

		Certificate cert = ks.getCertificate(alias);
		Key sk=ks.getKey(alias,pwdArray);
		return Ed25519KeyPair.create(cert.getPublicKey(),(PrivateKey) sk);
	}
}
