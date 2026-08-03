package convex.cli.key;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.crypto.BIP39;
import convex.core.crypto.PFXTools;
import convex.core.util.Utils;

public class KeyTest {

	private static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;
	private static final String KEYSTORE_PASSWORD = "testPassword";
	private static final String KEY_PASSWORD = "testKeyPassword";
	
	static {
		try {
			TEMP_FILE=File.createTempFile("tempKeystore", ".pfx");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD.toCharArray());
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (IOException | GeneralSecurityException e) {
			throw Utils.sneakyThrow(e);
		}
		TEMP_FILE.deleteOnExit();
	}

	
	@Test
	public void testKeySign() throws IOException {
		// Import a seed from Ed25519 test case
		CLTester tester =  CLTester.run(
				"key", 
				"import", 
				"--type","seed",
				"--keystore", KEYSTORE_FILENAME, 
				"--text", "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", 
				"--keypass", KEY_PASSWORD);
		tester.assertExitCode(ExitCodes.SUCCESS);
		
		tester =  CLTester.run(
				"key", 
				"sign", 
				"--key","fc51cd8e", // fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025 from test case
				"--keystore",KEYSTORE_FILENAME,
				"--keypass", KEY_PASSWORD, 
				"--hex", "af82");
		tester.assertExitCode(ExitCodes.SUCCESS);
		String out=tester.getOutput().trim();
		
		assertEquals("6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",out);
		

	}
	
	@Test
	public void testKeyListNoFile() throws IOException {
		CLTester tester =  CLTester.run(
				"key", 
				"list", 
				"--keystore", "foo.bar");
		tester.assertExitCode(ExitCodes.NOINPUT);
	}

	@Test
	public void testKeyGenerateMnemonicExplicitStdoutAtV0() {
		CLTester tester = CLTester.run(
				"key",
				"generate",
				"-v0",
				"-p", KEY_PASSWORD,
				"--type", "bip39",
				"--mnemonic-file", "-",
				"--passphrase", "testBIP39pass",
				"--storepass", KEYSTORE_PASSWORD,
				"--keystore", KEYSTORE_FILENAME);
		tester.assertExitCode(ExitCodes.SUCCESS);
		String[] lines=tester.getOutput().strip().split("\\R");
		assertEquals(2,lines.length);
		assertNull(BIP39.checkMnemonic(lines[0]));
		assertEquals(64,lines[1].length());
		assertFalse(tester.getError().contains(lines[0]),"mnemonic must not be written to stderr");
	}

	@Test
	public void testKeyGenerateMnemonicFile() throws IOException {
		Path mnemonicFile=newMnemonicPath();
		CLTester tester = CLTester.run(
				"key", "generate",
				"-p", KEY_PASSWORD,
				"--type", "bip39",
				"--mnemonic-file", mnemonicFile.toString(),
				"--passphrase", "testBIP39pass",
				"--storepass", KEYSTORE_PASSWORD,
				"--keystore", KEYSTORE_FILENAME);
		tester.assertExitCode(ExitCodes.SUCCESS);

		String mnemonic=Files.readString(mnemonicFile).strip();
		assertNull(BIP39.checkMnemonic(mnemonic));
		assertEquals(64,tester.getOutput().trim().length());
		assertFalse(tester.getError().contains(mnemonic),"mnemonic must not be written to stderr");

		PosixFileAttributeView posix=Files.getFileAttributeView(mnemonicFile,PosixFileAttributeView.class);
		if (posix!=null) {
			assertEquals(PosixFilePermissions.fromString("rw-------"),posix.readAttributes().permissions());
		} else {
			AclFileAttributeView acl=Files.getFileAttributeView(mnemonicFile,AclFileAttributeView.class);
			assertEquals(1,acl.getAcl().size());
			assertEquals(Files.getOwner(mnemonicFile),acl.getAcl().get(0).principal());
		}
	}

	@Test
	public void testKeyGenerateMnemonicFileDoesNotOverwrite() throws IOException {
		Path mnemonicFile=Files.createTempFile("convex-mnemonic-existing", ".txt");
		mnemonicFile.toFile().deleteOnExit();
		Files.writeString(mnemonicFile,"keep me");

		CLTester tester = CLTester.run(
				"key", "generate",
				"-p", KEY_PASSWORD,
				"--type", "bip39",
				"--mnemonic-file", mnemonicFile.toString(),
				"--passphrase", "testBIP39pass",
				"--storepass", KEYSTORE_PASSWORD,
				"--keystore", KEYSTORE_FILENAME);
		tester.assertExitCode(ExitCodes.IOERR);
		assertEquals("keep me",Files.readString(mnemonicFile));
	}

	@Test
	public void testNonInteractiveBIP39RequiresMnemonicTarget() {
		CLTester tester = CLTester.run(
				"key", "generate", "-n",
				"-p", KEY_PASSWORD,
				"--type", "bip39",
				"--passphrase", "testBIP39pass",
				"--storepass", KEYSTORE_PASSWORD,
				"--keystore", KEYSTORE_FILENAME);
		tester.assertExitCode(ExitCodes.USAGE);
		assertTrue(tester.getError().contains("--mnemonic-file"));
	}

	@Test
	public void testKeyGenerateAndUse() throws IOException {
		File f=TEMP_FILE;
		f.delete();
		String fileName =KEYSTORE_FILENAME;
		Path mnemonicFile=newMnemonicPath();
		
		// command key.generate
		CLTester tester =  CLTester.run(
				"key", 
				"generate", 
				"-p", KEY_PASSWORD, 
				"--type","bip39",
				"--mnemonic-file",mnemonicFile.toString(),
				"--passphrase","testBIP39pass",
				"--storepass", KEYSTORE_PASSWORD, 
				"--keystore", fileName);
		tester.assertExitCode(ExitCodes.SUCCESS);
		String key = tester.getOutput().trim();
		assertEquals(64,key.length());
		

		File fp = new File(fileName);
		assertTrue(fp.exists());

		// command key.list
		tester =  CLTester.run("key", "list", "--keystore", fileName);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertTrue(tester.getOutput().contains(key));
		
		// command key.list with wrong store password
		tester =  CLTester.run("key", "list", "--keystore", fileName, "--storepass","thisisBAD");
		tester.assertExitCode(ExitCodes.NOPERM);

		// command key.list with non-existant keystore
		tester =  CLTester.run("key", "list", "--storepass", KEYSTORE_PASSWORD, "--keystore","bad-keystore.pfx");
		assertNotEquals(ExitCodes.SUCCESS,tester.getResult());

		// command key.list
		tester =  CLTester.run("key", "delete", "--storepass", KEYSTORE_PASSWORD, "--keystore", fileName, "+");
		tester.assertExitCode(ExitCodes.SUCCESS);

		// command key.list, no keys left
		tester =  CLTester.run("key", "list", "-v0", "--keystore", fileName);
		tester.assertExitCode(ExitCodes.SUCCESS);
		assertFalse(tester.getOutput().contains(key));

	}

	private static Path newMnemonicPath() throws IOException {
		Path path=Files.createTempFile("convex-mnemonic", ".txt");
		Files.delete(path);
		path.toFile().deleteOnExit();
		return path;
	}
}
