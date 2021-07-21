package convex.cli;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class CLICommandKeyTest {

	private static final String KEYSTORE_FILENAME = "/tmp/tempKeystore.dat";
	private static final String KEYSTORE_PASSWORD = "testPassword";

	@Test
	public void testKeyGenerate() {

		// command key.generate
		CommandLineTester tester =  new CommandLineTester("key", "generate", "--password", KEYSTORE_PASSWORD, "--keystore", KEYSTORE_FILENAME);
		System.out.println("result: " + tester.getOutput());
		tester.assertOutputMatch("generated #1 public key");

		File fp = new File(KEYSTORE_FILENAME);
		assertTrue(fp.exists());

		// command key.list
		tester =  new CommandLineTester("key", "list", "--password", KEYSTORE_PASSWORD, "--keystore", KEYSTORE_FILENAME);
		// tester.assertOutputMatch("lis");
		System.out.println(tester.getOutput());

	}
}
