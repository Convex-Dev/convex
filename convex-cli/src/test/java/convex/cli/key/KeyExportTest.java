package convex.cli.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
import convex.core.crypto.PFXTools;
import convex.core.data.AccountKey;
import convex.core.util.FileUtils;

public class KeyExportTest {
	private static final char[] KEYSTORE_PASSWORD = "testPassword".toCharArray();
	private static final char[] KEY_PASSWORD = "testKeyPassword".toCharArray();
	private static final char[] EXPORT_PASSWORD = "testExportPassword".toCharArray();

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	static {
		try {
			TEMP_FILE=Helpers.createTempFile("tempKeystore", ".pfx");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (Exception t) {
			throw new Error(t);
		} 	
	}

	@Test
	public void testKeyGenerateAndExport() throws Exception {

		// command key.generate
		CLTester tester =  CLTester.run(
			"key", "generate",
			"--type", "random",
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME
		);
		tester.assertExitCode(ExitCodes.SUCCESS);

		File fp = TEMP_FILE;
		assertTrue(fp.exists());
		
		assertTrue(Files.exists(FileUtils.getFile(KEYSTORE_FILENAME).toPath()));
		
		// Check output is hex key
		String output=tester.getOutput().trim();
		assertEquals(64,output.length());
		
		AccountKey ak=AccountKey.fromHex(output);
		assertNotNull(ak);
		String publicKey=output;

		// export publicKey as pem
		tester =  CLTester.run(
			"key",
			"export",
			"--type","pem",
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey,
			"--export-password", new String(EXPORT_PASSWORD)
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		String s=tester.getOutput();
		assertEquals("",tester.getError());
		AKeyPair kp=PEMTools.decryptPrivateKeyFromPEM(s, EXPORT_PASSWORD);
		assertEquals(ak,kp.getAccountKey());
		
		// export publicKey as pem
		tester =  CLTester.run(
			"key",
			"export",
			"--type","seed",
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		String s2=tester.getOutput();
		assertTrue(s2.contains(kp.getSeed().toHexString()));
	}
}

