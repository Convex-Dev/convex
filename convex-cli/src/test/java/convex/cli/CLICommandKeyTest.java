package convex.cli;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class CLICommandKeyTest {

	private static final String KEYSTORE_PASSWORD = "testPassword";

	@Test
	public void testKeyGenerateList() throws IOException {
		File f=File.createTempFile("tempKeystore", ".dat");
		f.delete();
		String KEYSTORE_FILENAME =f.getCanonicalPath();
		
		// command key.generate
		CommandLineTester tester =  new CommandLineTester("key", "generate", "--password", KEYSTORE_PASSWORD, "--keystore", KEYSTORE_FILENAME);
		assertEquals(0,tester.getResult());
		tester.assertOutputMatch("^Index Public Key\\s+0");

		File fp = new File(KEYSTORE_FILENAME);
		assertTrue(fp.exists());

		// command key.list
		tester =  new CommandLineTester("key", "list", "--password", KEYSTORE_PASSWORD, "--keystore", KEYSTORE_FILENAME);
		tester.assertOutputMatch("^Index Public Key\\s+1");

	}
}
