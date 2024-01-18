package convex.cli.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.PFXTools;
import convex.core.data.AccountKey;
import convex.core.util.Utils;

public class KeyExportTest {
	private static final char[] KEYSTORE_PASSWORD = "testPassword".toCharArray();
	private static final char[] KEY_PASSWORD = "testKeytPassword".toCharArray();
	private static final char[] EXPORT_PASSWORD = "testExportPassword".toCharArray();

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		try {
			TEMP_FILE=Helpers.createTempFile("tempKeystore", ".pfx");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		} 
		
	}

	@Test
	public void testKeyGenerateAndExport() {

		// command key.generate
		CLTester tester =  CLTester.run(
			"key", "generate",
			"--keystore-password", new String(KEYSTORE_PASSWORD),
			"--password", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());

		File fp = TEMP_FILE;
		assertTrue(fp.exists());
		
		// Check output is hex key
		String output=tester.getOutput().trim();
		assertEquals(64,output.length());
		
		AccountKey ak=AccountKey.fromHex(output);
		assertNotNull(ak);
		String publicKey=output;

		// command key.export publicKey
		tester =  CLTester.run(
			"key",
			"export",
			"--keystore-password", new String(KEYSTORE_PASSWORD),
			"--p", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey,
			"--export-password", new String(EXPORT_PASSWORD)
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());
		// TODO test generated output

		// command key.export publicKey with leading 0x
		tester =  CLTester.run(
			"key",
			"export",
			"--keystore-password", new String(KEYSTORE_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", "0x" + publicKey,
			"--export-password", new String(EXPORT_PASSWORD)
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());
		// TODO test generated output


	}
}

