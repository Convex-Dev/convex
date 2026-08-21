package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.SecureRandom;

import org.bouncycastle.asn1.pkcs.PBES2Parameters;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.PBKDF2Params;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder;
import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.crypto.bc.BCProvider;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.util.Utils;

public class PEMToolsTest {

	String generateRandomHex(int size) {
		SecureRandom random = new SecureRandom();
		byte password[] = new byte[size];
		random.nextBytes(password);
		return Utils.toHexString(password);
	}
	
	static AKeyPair KP = AKeyPair.createSeeded(156858);

	private static final char[] PASS="test-password".toCharArray();

	@Test
	public void testPEMPrivateKey() throws Exception {
		AKeyPair keyPair = KP;

		String testPassword = "test-password";
		String pemText = null;
		pemText = PEMTools.encryptPrivateKeyToPEM(keyPair, testPassword.toCharArray());

		assertTrue(pemText != null);

		AKeyPair importKeyPair = PEMTools.decryptPrivateKeyFromPEM(pemText, testPassword.toCharArray());
		AString data = Strings.create(generateRandomHex(1024));
		ASignature leftSignature = keyPair.sign(data.getHash());
		ASignature rightSignature = importKeyPair.sign(data.getHash());
		assertTrue(leftSignature.equals(rightSignature));

	    Blob seed1 = keyPair.getSeed();
		Blob seed2 = importKeyPair.getSeed();
		assertEquals(seed1,seed2);
	}

	@Test
	public void testPEMEncryptionScheme() throws Exception {
		PBES2Parameters pbes2=pbes2Params(PEMTools.encryptPrivateKeyToPEM(KP, PASS));

		assertEquals(PKCS8Generator.AES_256_CBC,pbes2.getEncryptionScheme().getAlgorithm());
		assertEquals(PKCSObjectIdentifiers.id_PBKDF2,pbes2.getKeyDerivationFunc().getAlgorithm());

		PBKDF2Params kdf=PBKDF2Params.getInstance(pbes2.getKeyDerivationFunc().getParameters());
		assertEquals(PKCS8Generator.PRF_HMACSHA512,kdf.getPrf());
		assertEquals(Constants.PBE_ITERATIONS,kdf.getIterationCount().intValue());

		// Salt must be random per export, so one precomputed table cannot attack every
		// exported key
		Blob salt=Blob.create(kdf.getSalt());
		assertNotEquals(Blob.create(new byte[(int)salt.count()]),salt);
		assertNotEquals(salt,Blob.create(pbkdf2Params(PEMTools.encryptPrivateKeyToPEM(KP, PASS)).getSalt()));
	}

	@Test
	public void testLegacyRC2PEM() throws Exception {
		// Keys exported before the AES upgrade used PKCS#5 v1.5 with RC2. The scheme is
		// recorded in the PEM, so those exports must still import.
		Providers.init();
		JcePKCSPBEOutputEncryptorBuilder builder=new JcePKCSPBEOutputEncryptorBuilder(PKCS8Generator.PBE_SHA1_RC2_128);
		builder.setIterationCount(65536);

		StringWriter sw=new StringWriter();
		try (JcaPEMWriter writer=new JcaPEMWriter(sw)) {
			writer.writeObject(new JcaPKCS8Generator(KP.getPrivate(),builder.build(PASS)));
		}
		String pemText=sw.toString();

		AlgorithmIdentifier alg=encryptedInfo(pemText).getEncryptionAlgorithm();
		assertEquals(PKCS8Generator.PBE_SHA1_RC2_128,alg.getAlgorithm());
		assertEquals(KP.getSeed(),PEMTools.decryptPrivateKeyFromPEM(pemText, PASS).getSeed());
	}

	private static PBKDF2Params pbkdf2Params(String pemText) throws Exception {
		return PBKDF2Params.getInstance(pbes2Params(pemText).getKeyDerivationFunc().getParameters());
	}

	private static PBES2Parameters pbes2Params(String pemText) throws Exception {
		AlgorithmIdentifier alg=encryptedInfo(pemText).getEncryptionAlgorithm();
		assertEquals(PKCSObjectIdentifiers.id_PBES2,alg.getAlgorithm());
		return PBES2Parameters.getInstance(alg.getParameters());
	}

	private static PKCS8EncryptedPrivateKeyInfo encryptedInfo(String pemText) throws Exception {
		try (PEMParser parser=new PEMParser(new StringReader(pemText))) {
			return new PKCS8EncryptedPrivateKeyInfo(parser.readPemObject().getContent());
		}
	}

	public static void main(String... args) throws Exception {
		AKeyPair kp = KP;
		System.out.println(kp.getSeed());
		System.out.println(PEMTools.encryptPrivateKeyToPEM(kp,"foo".toCharArray()));
	}

	@Test
	public void testImportPKCS8v2PEM() throws Exception {
		// RFC 8410 allows an optional public key field. Its bytes then sit at the end of
		// the encoding, where a naive reader would look for the seed.
		Providers.init();
		PrivateKeyInfo v2=new PrivateKeyInfo(
				new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519),
				new DEROctetString(KP.getSeed().getBytes()),
				null,
				KP.getAccountKey().getBytes());

		JcePKCSPBEOutputEncryptorBuilder builder=new JcePKCSPBEOutputEncryptorBuilder(PKCS8Generator.AES_256_CBC);
		builder.setProvider(BCProvider.BC);
		StringWriter sw=new StringWriter();
		try (JcaPEMWriter writer=new JcaPEMWriter(sw)) {
			writer.writeObject(new PKCS8Generator(v2,builder.build(PASS)));
		}

		AKeyPair imported=PEMTools.decryptPrivateKeyFromPEM(sw.toString(),PASS);
		assertEquals(KP.getSeed(),imported.getSeed());
		assertEquals(KP.getAccountKey(),imported.getAccountKey());
	}

}
