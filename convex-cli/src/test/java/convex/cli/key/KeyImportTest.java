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
	
	private static final File KEYSTORE_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		KEYSTORE_FILE=Helpers.createTempKeystore("tempKeystore", KEYSTORE_PASSWORD);
		KEYSTORE_FILENAME = KEYSTORE_FILE.getAbsolutePath();
	}	

	@Test
	public void testKeyImport() {
	 
		AKeyPair keyPair = SodiumKeyPair.generate();
		AccountKey accountKey=keyPair.getAccountKey();
		String pemText = PEMTools.encryptPrivateKeyToPEM(keyPair.getPrivate(), IMPORT_PASSWORD);
 
		// command key.list
		CLTester tester =  CLTester.run(
			"key", 
			"import",
			"-n",
			"--keystore-password", new String(KEYSTORE_PASSWORD), 
			"--keystore", KEYSTORE_FILENAME, 
			"--pem-text", pemText, 
			"--pem-password", new String(IMPORT_PASSWORD)
		);
		assertEquals("",tester.getError());
		assertEquals(ExitCodes.SUCCESS,tester.getResult());

		CLTester t2=CLTester.run(
				"key" , 
				"list",
				"--keystore-password", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME);
		
		assertEquals(ExitCodes.SUCCESS,t2.getResult());
		assertTrue(t2.getOutput().contains(accountKey.toHexString()));
	}
}
