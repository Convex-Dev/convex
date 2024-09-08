package convex.core.crypto;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PKCS8Generator;
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

import convex.core.exceptions.BadFormatException;

public class PEMTools {
	/**
	 * Default iteration count for PBE. TODO: is this sane?
	 */
	private static final int PBE_ITERATIONS=65536;
	
	static {
		// Ensure we have BC provider initialised etc.
		Providers.init();
	}

	/**
	 * Encrypt a private key into a PEM formated text
	 *
	 * @param keyPair Key pair containing private key to encrypt
	 * @param password Password to use for encryption
	 * @return PEM text that can be saved or sent to another keystore
	 * @throws GeneralSecurityException Any encryption error that occurs
	 */
	public static String encryptPrivateKeyToPEM(AKeyPair keyPair, char[] password) throws GeneralSecurityException {
		PrivateKey privateKey=keyPair.getPrivate();
		StringWriter stringWriter = new StringWriter();
		JcaPEMWriter writer = new JcaPEMWriter(stringWriter);
		
		try {
			JcePKCSPBEOutputEncryptorBuilder builder = new JcePKCSPBEOutputEncryptorBuilder(PKCS8Generator.PBE_SHA1_RC2_128);
			builder.setIterationCount(PBE_ITERATIONS); // TODO: double check requirements here?
			OutputEncryptor encryptor = builder.build(password);
			JcaPKCS8Generator generator = new JcaPKCS8Generator(privateKey, encryptor);
			writer.writeObject(generator);
			writer.close();
		} catch (IOException | OperatorCreationException e) {
			throw new GeneralSecurityException("cannot encrypt private key to PEM: " + e);
		} 
		return stringWriter.toString();
	}

	/**
	 * Decrypt a PEM string to a key pair. The PEM string must contain the "ENCRYPTED PRIVATE KEY" type.
	 *
	 * @param pemText PEM string to decode
	 * @param password Password that was used to encrypt the private key
	 * @return Key pair as stored in the PEM
	 * @throws Error on reading the PEM, decryption and decoding the private key
	 */
	public static AKeyPair decryptPrivateKeyFromPEM(String pemText, char[] password) throws BadFormatException {
		PemObject pemObject = readPEMObject(pemText,"ENCRYPTED PRIVATE KEY");
		
		if (pemObject == null) {
			throw new Error("no encrypted private key found in pem text");
		}
		try {
			PKCS8EncryptedPrivateKeyInfo encryptedInfo = new PKCS8EncryptedPrivateKeyInfo(pemObject.getContent());

			JcePKCSPBEInputDecryptorProviderBuilder inputBuilder = new JcePKCSPBEInputDecryptorProviderBuilder();
			InputDecryptorProvider decryptor = inputBuilder.build(password);

			PrivateKeyInfo privateKeyInfo = encryptedInfo.decryptPrivateKeyInfo(decryptor);
			byte[] data=privateKeyInfo.getEncoded();
			AKeyPair kp=AKeyPair.create(data);
			return kp;
		} catch (IOException | PKCSException e) {
			throw new BadFormatException("cannot decrypt password from PEM ", e);
		}
	}

	private static PemObject readPEMObject(String pemText, String type) throws BadFormatException {
		StringReader stringReader = new StringReader(pemText);
		try (PEMParser pemParser = new PEMParser(stringReader)) {
			PemObject pemObject = pemParser.readPemObject();
			// read objects until we find an object of the right type
			while (pemObject != null) {
				if (pemObject.getType().equals(type)) {
					return pemObject;
				}
				pemObject = pemParser.readPemObject();
			}
			return null;
		} catch (IOException e) {
			throw new BadFormatException("cannot read PEM",e);
		}
	}

}
