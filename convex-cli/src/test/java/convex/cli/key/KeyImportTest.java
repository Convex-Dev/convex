package convex.cli.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import convex.core.crypto.sodium.SodiumKeyPair;
import convex.core.data.AccountKey; 

public class KeyImportTest {

	private static final char[] KEYSTORE_PASSWORD = "testPassword".toCharArray();
	private static final char[] IMPORT_PASSWORD = "testImportPassword".toCharArray();
	
	private static final String KEY_PASSWORD="testPass";
	
	private static final File KEYSTORE_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		KEYSTORE_FILE=Helpers.createTempKeystore("tempKeystore", KEYSTORE_PASSWORD);
		KEYSTORE_FILENAME = KEYSTORE_FILE.getAbsolutePath();
	}	

	@Test
	public void testKeyImportPEM() {
	 
		AKeyPair keyPair = SodiumKeyPair.generate();
		AccountKey accountKey=keyPair.getAccountKey();
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), IMPORT_PASSWORD);
 
		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"pem",
			"-n",
			"--keystore-password", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--text", pemText, 
			"--import-password", new String(IMPORT_PASSWORD)
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--keystore-password", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		assertEquals(ExitCodes.SUCCESS,t2.getResult());
		assertTrue(t2.getOutput().contains(accountKey.toHexString()));
	}
	
	@Test
	public void testKeyImportSeed() {
		AKeyPair keyPair = AKeyPair.createSeeded(101);
		AccountKey accountKey=keyPair.getAccountKey();

		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"seed",
			"--keystore-password", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--text", keyPair.getSeed().toString(), 
			"--password", KEY_PASSWORD, 
			"--import-password", new String("") // BIP39 password
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());
		
		// Should give Ed25519 Seed: 616421a4ea27c65919faa5555e923f6005d76695c7d9ba0fe2a484b90e23de89

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--password",KEY_PASSWORD,
				"--keystore-password", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		assertEquals(ExitCodes.SUCCESS,t2.getResult());
		assertTrue(t2.getOutput().contains(accountKey.toHexString()));
	}
	
	/**
	 * BIP39 Import test
	 * Example case generated using excellent tool: https://iancoleman.io/bip39/
	 */
	@Test
	public void testKeyImportBIP39() {

		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"bip39",
			"--keystore-password", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--password",KEY_PASSWORD,
			"--text", "elder mail trick garage hour enjoy attack fringe problem motion poem security caught false penalty", 
			"--import-password", new String("")
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());
		
		// Should give Ed25519 Seed: 616421a4ea27c65919faa5555e923f6005d76695c7d9ba0fe2a484b90e23de89

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--password",KEY_PASSWORD,
				"--keystore-password", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		assertEquals(ExitCodes.SUCCESS,t2.getResult());
		String output=t2.getOutput();
		// System.out.println(output);
		assertTrue(output.contains("359562fef6063132699e5e51aa741943c712712be1c2783b61aa2d6f3b42aa44".toLowerCase()));
	}
}
