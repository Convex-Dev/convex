package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import convex.core.crypto.sodium.SodiumKeyPair; 

public class CLICommandKeyImportTest {

	private static final char[] KEYSTORE_PASSWORD = "testPassword".toCharArray();
	private static final char[] IMPORT_PASSWORD = "testImportPassword".toCharArray();
	
	private static final File KEYSTORE_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		KEYSTORE_FILE=Helpers.createTempKeystore("tempKeystore", KEYSTORE_PASSWORD);
		KEYSTORE_FILENAME = KEYSTORE_FILE.getAbsolutePath();
	}	

	@Test
	public void testKeyImport() {
	 
		AKeyPair keyPair = SodiumKeyPair.generate();
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), IMPORT_PASSWORD);
 
		// command key.list
		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"-n",
			"--password", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--import-text", pemText, 
			"--import-password", new String(IMPORT_PASSWORD)
		);
		assertEquals("",tester.getError());
		assertEquals(ExitCodes.SUCCESS,tester.getResult());

		//tester.assertOutputMatch("public key: " + keyPair.getAccountKey().toHexString());

	}
}
