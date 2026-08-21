package convex.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.SecretKeyEntry;
import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import convex.core.Constants;
import convex.core.data.Blob;
import convex.core.init.InitTest;
import convex.core.lang.RT;

public class PFXTest {

	private static final char[] STORE_PASS="test".toCharArray();
	private static final char[] KEY_PASS="thehero".toCharArray();

	/**
	 * Length in bytes of the PBKDF2 salt written for each key entry
	 */
	private static final int SALT_LENGTH=20;

	/**
	 * DER encoding of the PBKDF2 OID (1.2.840.113549.1.5.12). In a PKCS12 file this is
	 * immediately followed by the PBKDF2-params SEQUENCE, which starts with the salt.
	 */
	private static final Blob PBKDF2_OID=Blob.fromHex("06092A864886F70D01050C");

	@Test public void testNewStore() throws IOException, GeneralSecurityException {
		File f=File.createTempFile("temp-keystore", "pfx");
		char[] PASS="test".toCharArray();
		char[] KEYPASS="thehero".toCharArray();
		
		PFXTools.createStore(f, PASS);

		// check password is being applied
		assertThrows(IOException.class,()->PFXTools.loadStore(f,"foobar".toCharArray()));

		// don't throw, no integrity checking on null?
		//assertThrows(IOException.class,()->PFXUtils.loadStore(f,null));

		KeyStore ks=PFXTools.loadStore(f, PASS);
		AKeyPair kp=InitTest.HERO_KEYPAIR;
		PFXTools.setKeyPair(ks, kp, KEYPASS);
		PFXTools.saveStore(ks, f, PASS);

		String alias=InitTest.HERO_KEYPAIR.getAccountKey().toHexString();
		KeyStore ks2=PFXTools.loadStore(f, PASS);
		assertEquals(alias,ks2.aliases().asIterator().next());

		AKeyPair kp2=PFXTools.getKeyPair(ks2,alias, KEYPASS);
		assertEquals(kp.signData(RT.cvm(1L)).getEncoding(),kp2.signData(RT.cvm(1L)).getEncoding());
	}
	
	@Test public void testBadStore() {
		assertThrows(FileNotFoundException.class,()->PFXTools.loadStore(new File("bloooooobiug"), null));
	}

	@Test public void testKeySaltIsRandom() throws IOException, GeneralSecurityException {
		ArrayList<Blob> salts=pbkdf2Salts(storeWithHeroKey());
		assertEquals(1,salts.size());

		Blob salt=salts.get(0);
		assertEquals(SALT_LENGTH,salt.count());
		assertNotEquals(Blob.create(new byte[SALT_LENGTH]),salt);

		// A second store for the same key and passphrases must not reuse the salt, otherwise
		// one precomputed PBKDF2 table would attack every key store we write
		ArrayList<Blob> salts2=pbkdf2Salts(storeWithHeroKey());
		assertEquals(1,salts2.size());
		assertNotEquals(salt,salts2.get(0));
	}

	@Test public void testLegacyZeroSaltStore() throws IOException, GeneralSecurityException {
		// Key stores written before the salt fix used a fixed all-zero salt. The salt is
		// stored in the file itself, so such stores must still open.
		File f=File.createTempFile("temp-keystore", "pfx");
		KeyStore ks=PFXTools.createStore(f, STORE_PASS);

		AKeyPair kp=InitTest.HERO_KEYPAIR;
		String alias=kp.getAccountKey().toHexString();
		SecretKey seed=new SecretKeySpec(kp.getSeed().getBytes(), "Ed25519");
		ks.setEntry(alias, new SecretKeyEntry(seed), new PasswordProtection(KEY_PASS,
				"PBEWithHmacSHA512AndAES_128",
				new PBEParameterSpec(new byte[SALT_LENGTH], Constants.PBE_ITERATIONS)));
		PFXTools.saveStore(ks, f, STORE_PASS);

		assertEquals(Blob.create(new byte[SALT_LENGTH]),pbkdf2Salts(f).get(0));
		assertEquals(kp.getSeed(),PFXTools.getKeyPair(PFXTools.loadStore(f, STORE_PASS), alias, KEY_PASS).getSeed());
	}

	/**
	 * Creates a key store containing the hero key pair.
	 */
	private static File storeWithHeroKey() throws IOException, GeneralSecurityException {
		File f=File.createTempFile("temp-keystore", "pfx");
		KeyStore ks=PFXTools.createStore(f, STORE_PASS);
		PFXTools.setKeyPair(ks, InitTest.HERO_KEYPAIR, KEY_PASS);
		PFXTools.saveStore(ks, f, STORE_PASS);
		return f;
	}

	/**
	 * Extracts the PBKDF2 salts protecting the key entries in a PKCS12 file.
	 */
	private static ArrayList<Blob> pbkdf2Salts(File f) throws IOException {
		byte[] bs=Files.readAllBytes(f.toPath());
		int oidLength=(int)PBKDF2_OID.count();
		ArrayList<Blob> salts=new ArrayList<>();
		for (int i=0; i+oidLength<=bs.length; i++) {
			if (!PBKDF2_OID.equals(Blob.wrap(bs,i,oidLength))) continue;
			int p=i+oidLength;
			assertEquals(0x30,bs[p]&0xFF); // PBKDF2-params SEQUENCE
			assertEquals(0x04,bs[p+2]&0xFF); // salt OCTET STRING
			salts.add(Blob.create(bs,p+4,bs[p+3]&0xFF));
		}
		return salts;
	}
}
