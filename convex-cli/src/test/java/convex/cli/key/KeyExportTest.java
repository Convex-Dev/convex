package convex.cli.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PEMTools;
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

		// export publicKey as pem
		tester =  CLTester.run(
			"key",
			"export",
			"--keystore-password", new String(KEYSTORE_PASSWORD),
			"--password", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--public-key", publicKey,
			"--export-password", new String(EXPORT_PASSWORD)
		);
		String s=tester.getOutput();
		assertEquals("",tester.getError());
		assertEquals(ExitCodes.SUCCESS,tester.getResult());
		AKeyPair kp=AKeyPair.create(PEMTools.decryptPrivateKeyFromPEM(s, EXPORT_PASSWORD));
		assertEquals(ak,kp.getAccountKey());
		
		// export publicKey as pem
		tester =  CLTester.run(
			"key",
			"export",
			"seed",
			"--keystore-password", new String(KEYSTORE_PASSWORD),
			"--password", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--public-key", publicKey
		);
		String s2=tester.getOutput();
		assertEquals(ExitCodes.SUCCESS,tester.getResult());
		assertTrue(s2.contains(kp.getSeed().toHexString()));
	}
}

