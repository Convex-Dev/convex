package convex.cli.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

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
			"--export-password", new String(EXPORT_PASSWORD),
			"--output-file", "-"
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
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey,
			"--output-file", "-"
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		String s2=tester.getOutput();
		assertEquals(kp.getSeed().toHexString(),s2.trim());

		Path exportFile=Files.createTempFile("convex-seed-export", ".txt");
		Files.delete(exportFile);
		exportFile.toFile().deleteOnExit();
		tester = CLTester.run(
			"key", "export", "-n",
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey,
			"--output-file", exportFile.toString());
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(kp.getSeed().toHexString(),Files.readString(exportFile).trim());

		PosixFileAttributeView posix=Files.getFileAttributeView(exportFile,PosixFileAttributeView.class);
		if (posix!=null) {
			assertEquals(PosixFilePermissions.fromString("rw-------"),posix.readAttributes().permissions());
		} else {
			AclFileAttributeView acl=Files.getFileAttributeView(exportFile,AclFileAttributeView.class);
			assertEquals(1,acl.getAcl().size());
			assertEquals(Files.getOwner(exportFile),acl.getAcl().get(0).principal());
		}

		// Secret export files are never overwritten.
		tester = CLTester.run(
			"key", "export", "-n",
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey,
			"--output-file", exportFile.toString());
		tester.assertExitCode(ExitCodes.IOERR);
		assertEquals(kp.getSeed().toHexString(),Files.readString(exportFile).trim());

		// Automation must choose a file or explicitly opt in to stdout.
		tester = CLTester.run(
			"key", "export", "-n",
			"--storepass", new String(KEYSTORE_PASSWORD),
			"--keypass", new String(KEY_PASSWORD),
			"--keystore", KEYSTORE_FILENAME,
			"--key", publicKey);
		tester.assertExitCode(ExitCodes.USAGE);
		assertTrue(tester.getError().contains("--output-file"));
	}
}

