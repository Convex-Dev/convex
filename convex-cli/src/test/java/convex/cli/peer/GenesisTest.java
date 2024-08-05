package convex.cli.peer;

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
		CLTester tester =  CLTester.run(
				"peer", "genesis",
				"--storepass", new String(KEYSTORE_PASSWORD),
				"--keypass", new String(KEY_PASSWORD),
				"--keystore", KEYSTORE_FILENAME
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
	}
}
