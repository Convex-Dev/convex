package convex.cli.key;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.util.Utils;

public class KeyTest {

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		try {
			TEMP_FILE=File.createTempFile("tempKeystore", ".pfx");
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
		TEMP_FILE.deleteOnExit();
	}
	private static final String KEYSTORE_PASSWORD = "testPassword";
	private static final String KEY_PASSWORD = "testKeyPassword";

	@Test
	public void testKeyGenerateAndUse() throws IOException {
		File f=TEMP_FILE;
		f.delete();
		String fileName =KEYSTORE_FILENAME;
		
		// command key.generate
		CLTester tester =  CLTester.run(
				"key", 
				"generate", 
				"-p", KEY_PASSWORD, 
				"--storepass", KEYSTORE_PASSWORD, 
				"--keystore", fileName);
		tester.assertResult(0);
		String key = tester.getOutput().trim();
		assertEquals(64,key.length());
		

		File fp = new File(fileName);
		assertTrue(fp.exists());

		// command key.list
		tester =  CLTester.run("key", "list", "--storepass", KEYSTORE_PASSWORD, "--keystore", fileName);
		//tester.assertOutputMatch("^Index Public Key\\s+1");

		// command key.list with non-existant keystore
		tester =  CLTester.run("key", "list", "--storepass", KEYSTORE_PASSWORD, "--keystore","bad-keystore.pfx");
		assertNotEquals(ExitCodes.SUCCESS,tester.getResult());

	}
}
