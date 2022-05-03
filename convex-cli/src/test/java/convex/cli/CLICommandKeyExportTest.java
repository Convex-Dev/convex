package convex.cli;

import java.io.File;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import convex.core.crypto.PFXTools;
import convex.core.data.AccountKey;
import convex.core.util.Utils;

public class CLICommandKeyExportTest {
	private static final String KEYSTORE_PASSWORD = "testPassword";
	private static final String EXPORT_PASSWORD = "testExportPassword";

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		try {
			TEMP_FILE=File.createTempFile("tempKeystore", ".pfx");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
			// TEMP_FILE.deleteOnExit();
		} catch (Throwable t) {
			throw Utils.sneakyThrow(t);
		} 
		
	}

	@Test
	public void testKeyGenerateList() {

		// command key.generate
		CLTester tester =  CLTester.run(
			"key", "generate",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME
		);
		assertEquals(ExitCodes.SUCCESS,tester.getResult());

		File fp = TEMP_FILE;
		assertTrue(fp.exists());
		String output=tester.getOutput().trim();
		assertEquals(64,output.length());
		
		AccountKey ak=AccountKey.fromHex(output);
		assertNotNull(ak);
		String publicKey=output;

		// command key.export index
		tester =  CLTester.run(
			"key",
			"export",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME,
			"--index-key", "1",
			"--export-password", EXPORT_PASSWORD
		);
		// TODO test generated output

		// command key.export publicKey
		tester =  CLTester.run(
			"key",
			"export",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME,
			"--public-key", publicKey,
			"--export-password", EXPORT_PASSWORD
		);
		// TODO test generated output

		// command key.export publicKey with leading 0x
		tester =  CLTester.run(
			"key",
			"export",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME,
			"--public-key", "0x" + publicKey,
			"--export-password", EXPORT_PASSWORD
		);
		// TODO test generated output


	}
}

