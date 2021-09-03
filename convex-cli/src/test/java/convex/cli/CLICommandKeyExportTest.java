package convex.cli;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class CLICommandKeyExportTest {

	private static final String KEYSTORE_FILENAME = "/tmp/tempKeystore.dat";
	private static final String KEYSTORE_PASSWORD = "testPassword";
	private static final String EXPORT_PASSWORD = "testExportPassword";

	@Test
	public void testKeyGenerateList() {

		// command key.generate
		CommandLineTester tester =  new CommandLineTester(
			"key", "generate",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME
		);
		tester.assertOutputMatch("^Index Public Key\\s+0");
		String publicKey = tester.getField("0 ");
		assertFalse(publicKey.isEmpty());
		publicKey = publicKey.stripLeading();

		File fp = new File(KEYSTORE_FILENAME);
		assertTrue(fp.exists());

		// command key.export index
		tester =  new CommandLineTester(
			"key",
			"export",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME,
			"--index-key", "1",
			"--export-password", EXPORT_PASSWORD
		);
		tester.assertOutputMatch("ENCRYPTED PRIVATE KEY");

		// command key.export publicKey
		tester =  new CommandLineTester(
			"key",
			"export",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME,
			"--public-key", publicKey,
			"--export-password", EXPORT_PASSWORD
		);
		tester.assertOutputMatch("ENCRYPTED PRIVATE KEY");

		// command key.export publicKey with leading 0x
		tester =  new CommandLineTester(
			"key",
			"export",
			"--password", KEYSTORE_PASSWORD,
			"--keystore", KEYSTORE_FILENAME,
			"--public-key", "0x" + publicKey,
			"--export-password", EXPORT_PASSWORD
		);
		tester.assertOutputMatch("ENCRYPTED PRIVATE KEY");


	}
}

