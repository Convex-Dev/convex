package convex.cli;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.crypto.PEMTools;

public class CLICommandKeyImportTest {

	private static final String KEYSTORE_FILENAME = "/tmp/tempKeystore.dat";
	private static final String KEYSTORE_PASSWORD = "testPassword";
	private static final String IMPORT_PASSWORD = "testImportPassword";

	@Test
	public void testKeyImport() {
	
		AKeyPair keyPair = Ed25519KeyPair.generate();
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), IMPORT_PASSWORD.toCharArray());

		// command key.list
		CommandLineTester tester =  new CommandLineTester(
			"key", 
			"import", 
			"--password", KEYSTORE_PASSWORD, 
			"--keystore", KEYSTORE_FILENAME, 
			"--import-text", pemText, 
			"--import-password", IMPORT_PASSWORD
		);

		tester.assertOutputMatch("public key: " + keyPair.getAccountKey().toHexString());

	}
}
