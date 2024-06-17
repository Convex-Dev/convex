package convex.core.crypto;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.bouncycastle.util.io.pem.PemObject;

public class PEMTools {
	// private static String encryptionAlgorithm="AES-128-CBC";
	
	static {
		// Ensure we have BC provider initialised etc.
		Providers.init();
	}

	/**
	 * Writes a key pair to a String
	 * @param kp Key pair to write
	 * @return PEM String representation of key pair
	 */
	public static String writePEM(AKeyPair kp) {

		PrivateKey priv=kp.getPrivate();
		// PublicKey pub=kp.getPublic();
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(priv.getEncoded());

		byte[] encoded=keySpec.getEncoded();
		String base64=Base64.getEncoder().encodeToString(encoded);

		StringBuilder sb=new StringBuilder();
		sb.append("-----BEGIN PRIVATE KEY-----");
		sb.append(System.lineSeparator());
		sb.append(base64);
		sb.append(System.lineSeparator());
		sb.append("-----END PRIVATE KEY-----");
		String pem=sb.toString();
		return pem;
	}

	/**
	 * Read a key pair from a PEM String
	 * @param pem PEM String
	 * @return Key pair instance
	 * @throws GeneralSecurityException If a security error occurs
	 */
	public static AKeyPair readPEM(String pem) throws GeneralSecurityException {
		String publicKeyPEM = pem.trim()
			      .replace("-----BEGIN PRIVATE KEY-----", "")
			      .replaceAll(System.lineSeparator(), "")
			      .replace("-----END PRIVATE KEY-----", "");

		byte[] bs = Base64.getDecoder().decode(publicKeyPEM);

		KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
		PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(bs);
		PrivateKey priv=keyFactory.generatePrivate(keySpec);
		PublicKey pub=keyFactory.generatePublic(keySpec);
		return AKeyPair.create(pub, priv);
	}

	/**
	 * Encrypt a priavte key into a PEM formated text
	 *
	 * @param privateKey Private key to encrypt
	 * @param password Password to use for encryption
	 * @return PEM text that can be saved or sent to another keystore
	 * @throws Error Any encryption error that occurs
	 */
	public static String encryptPrivateKeyToPEM(PrivateKey privateKey, char[] password) throws Error {
		StringWriter stringWriter = new StringWriter();
		JcaPEMWriter writer = new JcaPEMWriter(stringWriter);
		
		try {
			JcePKCSPBEOutputEncryptorBuilder builder = new JcePKCSPBEOutputEncryptorBuilder(PKCS8Generator.PBE_SHA1_RC2_128);
			builder.setIterationCount(4096); // TODO: double check requirements here?
			OutputEncryptor encryptor = builder.build(password);
			JcaPKCS8Generator generator = new JcaPKCS8Generator(privateKey, encryptor);
			writer.writeObject(generator);
			writer.close();
		} catch (IOException | OperatorCreationException e) {
			throw new Error("cannot encrypt private key to PEM: " + e);
		} 
		return stringWriter.toString();
	}

	/**
	 * Decrypt a PEM string to a private key. The PEM string must contain the "ENCRYPTED PRIVATE KEY" type.
	 *
	 * @param pemText PEM string to decode
	 * @param password Password that was used to encrypt the private key
	 * @return PrivateKey stored in the PEM
	 * @throws Error on reading the PEM, decryption and decoding the private key
	 */
	public static PrivateKey decryptPrivateKeyFromPEM(String pemText, char[] password) throws Error {
		PrivateKey privateKey = null;
		StringReader stringReader = new StringReader(pemText);
		PemObject pemObject = null;
		JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
		try (PEMParser pemParser = new PEMParser(stringReader)) {
			pemObject = pemParser.readPemObject();
			while (pemObject != null) {
				if (pemObject.getType().equals("ENCRYPTED PRIVATE KEY")) {
					break;
				}
				pemObject = pemParser.readPemObject();
			}

		} catch (IOException e) {
			throw new Error("cannot read PEM",e);
		}

		if (pemObject == null) {
			throw new Error("no encrypted private key found in pem text");
		}
		try {
			PKCS8EncryptedPrivateKeyInfo encryptedInfo = new PKCS8EncryptedPrivateKeyInfo(pemObject.getContent());

			JcePKCSPBEInputDecryptorProviderBuilder inputBuilder = new JcePKCSPBEInputDecryptorProviderBuilder();
			inputBuilder.setProvider("BC");
			InputDecryptorProvider decryptor = inputBuilder.build(password);

			PrivateKeyInfo privateKeyInfo = encryptedInfo.decryptPrivateKeyInfo(decryptor);
			privateKey = converter.getPrivateKey(privateKeyInfo);
		} catch (IOException | PKCSException e) {
			throw new Error("cannot decrypt password from PEM ", e);
		}
		return privateKey;
	}

	public static void main(String[] args) throws Exception {
		AKeyPair kp=AKeyPair.createSeeded(1337);
		String pem=writePEM(kp);
		System.out.println(pem);

		AKeyPair kp2=readPEM(pem);
		System.out.println(kp2);
	}

}
