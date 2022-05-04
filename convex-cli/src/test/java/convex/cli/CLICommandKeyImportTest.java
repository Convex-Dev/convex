package convex.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.crypto.PEMTools;
import convex.core.util.Utils;

public class CLICommandKeyImportTest {

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		TEMP_FILE=Helpers.createTempFile("tempKeystore", ".pfx");
		KEYSTORE_FILENAME = TEMP_FILE.getAbsolutePath();
	}	
	private static final String KEYSTORE_PASSWORD = "testPassword";
	private static final String IMPORT_PASSWORD = "testImportPassword";

	@Test
	public void testKeyImport() {
	
		AKeyPair keyPair = Ed25519KeyPair.generate();
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), IMPORT_PASSWORD.toCharArray());
 
		// command key.list
		CLTester tester =  CLTester.run(
			"key", 
			"import", 
			"--password", KEYSTORE_PASSWORD, 
			"--keystore", KEYSTORE_FILENAME, 
			"--import-text", pemText, 
			"--import-password", IMPORT_PASSWORD
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());

		//tester.assertOutputMatch("public key: " + keyPair.getAccountKey().toHexString());

	}
}
