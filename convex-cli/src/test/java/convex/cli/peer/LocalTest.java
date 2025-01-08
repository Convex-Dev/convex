package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.Result;
import convex.core.crypto.PFXTools;
import java.util.List;
import java.util.Scanner;

import convex.core.util.Utils;
import convex.peer.ConfigException;
import convex.peer.LaunchException;

@TestInstance(Lifecycle.PER_CLASS)
public class LocalTest {
	private static final char[] KEYSTORE_PASSWORD = "localStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "localKeyPassword".toCharArray();

	static final File TEMP_FILE;
	static final File TEMP_ETCH;
	private static final String KEYSTORE_FILENAME;

	private static final int TEST_PORT = 28888; // avoid clash with something on 18888

	private static final String bip39 = "miracle source lizard gun neutral year dust recycle drama nephew infant enforce";
	private static final String bipPassphrase = "thisIsNotSecure";
	private static final String expectedKey = "09a5528c53579e1ee76a327ab8bc9db7b2853dd17391a6e3fe7f3052c6e8686a";

	static {
		try {
			TEMP_FILE = Helpers.createTempFile("tempLocalKeystore", ".pfx");
			TEMP_ETCH = Helpers.createTempFile("tempLocalEtchDatabase", ".db");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (Exception t) {
			throw Utils.sneakyThrow(t);
		}
	}

	static Process process = null;

	@BeforeAll
	public static void setupLocalNet() throws IOException, InterruptedException {
		CLTester importTester = CLTester.run("key", "import", "--type", "bip39", "--storepass",
				new String(KEYSTORE_PASSWORD), "--keystore", KEYSTORE_FILENAME, "--keypass", new String(KEY_PASSWORD),
				"--text", bip39, "--passphrase", bipPassphrase,"-v0");
		importTester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(expectedKey, importTester.getOutput().trim());

		
		String javaHome = System.getProperty("java.home");
		String javaCmd = javaHome + File.separator + "bin" + File.separator + "java";
		String classpath = System.getProperty("java.class.path");
		String modpath=System.getProperty("jdk.module.path");
		String className = convex.cli.Main.class.getName();

		List<String> cmd = new ArrayList<>();
		cmd.add(javaCmd);
		// Could add extra JVM arguments here
		if (classpath!=null) {
			cmd.add("-cp");
			cmd.add(classpath);
		}
		if (modpath!=null) {
			cmd.add("--module-path");
			cmd.add(modpath);
		}
		cmd.add(className);
		cmd.add("local");
		cmd.add("start");
		cmd.add("--count=1");
		cmd.add("--key=09a5");
		cmd.add("--ports=" + TEST_PORT);

		ProcessBuilder builder = new ProcessBuilder(cmd);
		process = builder.start();

		// We need to wait until the peer started message is seen
		Thread checker = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
				try (Scanner s = new Scanner(reader)) {
					while (true) {
						String output = s.nextLine();
						// System.err.println(output);
						if (output.contains("Started: 1")) break;
					}
				}
			} catch (Exception e) {
			}
			return;
		});
		checker.start();
		checker.join(5000);
		assertTrue(process.isAlive());
	}

	@AfterAll
	public static void tearDown() {
		process.destroy();
		try {
			process.waitFor(3000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Test
	public void testProcess() throws IOException, TimeoutException, InterruptedException {
		assertTrue(process.isAlive());

		InetSocketAddress sa=new InetSocketAddress("localhost",TEST_PORT);
		Convex convex = ConvexRemote.connect(sa);
		Result r=convex.requestStatusSync();
		assertFalse(r.isError());
	}

	@Test
	public void testGenesisPeer()
			throws TimeoutException, InterruptedException, IOException, LaunchException, ConfigException {

//		tester =  CLTester.run(
//				"peer", "start", "-n",
//              "-v2",		
//				"--peer-key", expectedKey,
//				"--keystore", KEYSTORE_FILENAME, 
//				"--peer-keypass", new String(KEY_PASSWORD),
//				"--etch", TEMP_ETCH.getCanonicalPath()
//		);
//		tester.assertExitCode(ExitCodes.SUCCESS);
	}
}
