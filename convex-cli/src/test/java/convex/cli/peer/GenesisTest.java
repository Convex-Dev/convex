package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.PFXTools;
import convex.core.util.Utils;

public class GenesisTest {
	private static final char[] KEYSTORE_PASSWORD = "genesisStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "genesisKeyPassword".toCharArray();

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	
	private static final String bip39="miracle source lizard gun neutral year dust recycle drama nephew infant enforce";
	private static final String bipPassphrase="thisIsNotSecure";
	private static final String expectedKey="09a5528c53579e1ee76a327ab8bc9db7b2853dd17391a6e3fe7f3052c6e8686a";
	
	
	static {
		try {
			TEMP_FILE=Helpers.createTempFile("tempGenesisKeystore", ".pfx");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		} 
		
	}

	@Test public void testGenesisPeer() {
		CLTester importTester =  CLTester.run(
				"key", 
				"import",
				"--type","bip39",
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME, 
				"--keypass",new String(KEY_PASSWORD),
				"--text", bip39, 
				"--passphrase", bipPassphrase
			);
		importTester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(expectedKey,importTester.getOutput().trim());
		
//		CLTester tester =  CLTester.run(
//				"peer", "genesis",
//				"--storepass", new String(KEYSTORE_PASSWORD),
//				"--keypass", new String(KEY_PASSWORD),
//				"--keystore", KEYSTORE_FILENAME
//		);
//		tester.assertExitCode(ExitCodes.SUCCESS);
	}
}
