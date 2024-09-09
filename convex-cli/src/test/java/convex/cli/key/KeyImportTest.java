package convex.cli.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import convex.core.data.AccountKey; 

public class KeyImportTest {

	private static final char[] KEYSTORE_PASSWORD = "testPassword".toCharArray();
	private static final char[] IMPORT_PASSPHRASE = "testImportPassphrase".toCharArray();
	
	private static final String KEY_PASSWORD="testPass";
	
	private static final File KEYSTORE_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		try {
			KEYSTORE_FILE=Helpers.createTempKeystore("tempKeystore", KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = KEYSTORE_FILE.getAbsolutePath();
		} catch (IOException | GeneralSecurityException e) {
			throw new Error(e);
		}
	}	

	@Test
	public void testKeyImportPEM() throws GeneralSecurityException {
	 
		AKeyPair keyPair = AKeyPair.generate();
		AccountKey accountKey=keyPair.getAccountKey();
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair, IMPORT_PASSPHRASE);
 
		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"--type","pem",
			"-n",
			"--storepass", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--text", pemText, 
			"--passphrase",new String(IMPORT_PASSPHRASE),
			"--keypass", new String(KEY_PASSWORD)
		);
		tester.assertExitCode(ExitCodes.SUCCESS);

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertTrue(t2.getOutput().contains(accountKey.toHexString()));
	}
	
	@Test
	public void testKeyImportSeed() {
		AKeyPair keyPair = AKeyPair.createSeeded(101);
		AccountKey accountKey=keyPair.getAccountKey();

		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"--type","seed",
			"--keystore", KEYSTORE_FILENAME, 
			"--text", keyPair.getSeed().toString(), 
			"--keypass", KEY_PASSWORD, 
			"--passphrase", new String("") // BIP39 password, ignored
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		
		// Should give Ed25519 Seed: 616421a4ea27c65919faa5555e923f6005d76695c7d9ba0fe2a484b90e23de89

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		tester.assertExitCode(ExitCodes.SUCCESS);
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
			"--type","bip39",
			"--storepass", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--keypass",KEY_PASSWORD,
			"--text", "elder mail trick garage hour enjoy attack fringe problem motion poem security caught false penalty", 
			"--passphrase", new String("")
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		
		// Should give Ed25519 Seed: 616421a4ea27c65919faa5555e923f6005d76695c7d9ba0fe2a484b90e23de89

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		assertEquals(ExitCodes.SUCCESS,t2.getResult());
		String output=t2.getOutput();
		// System.out.println(output);
		assertTrue(output.contains("359562fef6063132699e5e51aa741943c712712be1c2783b61aa2d6f3b42aa44".toLowerCase()));
	}
}
