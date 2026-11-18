package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.io.TempDir;

import com.beust.jcommander.Strings;

import convex.api.Convex;
import convex.api.ConvexRemote;
import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.PFXTools;
import convex.core.crypto.SLIP10;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.util.Utils;

@TestInstance(Lifecycle.PER_CLASS)
@Execution(ExecutionMode.CONCURRENT)
public class LocalTest {
	@TempDir
	static Path processTempDirectory;

	private static final char[] KEYSTORE_PASSWORD = "localStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "localKeyPassword".toCharArray();

	static final File TEMP_FILE;
	private static final String KEYSTORE_FILENAME;

	/**
	 * Actual port of the local test network peer, parsed from the "Peer ports:"
	 * line printed by `local start`. Ports are auto-assigned (no fixed port) to
	 * avoid bind collisions on busy CI runners.
	 */
	private static volatile int actualPort = -1;

	private static final String bip39 = "miracle source lizard gun neutral year dust recycle drama nephew infant enforce";
	private static final String bipPassphrase = "thisIsNotSecure";
	private static final String expectedKey = "09a5528c53579e1ee76a327ab8bc9db7b2853dd17391a6e3fe7f3052c6e8686a";

	static {
		try {
			TEMP_FILE = Helpers.createTempFile("tempLocalKeystore", ".pfx");
			
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (Exception t) {
			throw Utils.sneakyThrow(t);
		}
	}

	static Process process = null;
	static Thread checker;

	@BeforeAll
	public static void setupLocalNet() throws IOException, InterruptedException, ExecutionException, TimeoutException {
		CLTester importTester = CLTester.run("key", "import", 
				"--type", "bip39", 
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME, 
				"--keypass", new String(KEY_PASSWORD),
				"--path", "m",
				"--text", bip39, 
				"--passphrase", bipPassphrase,"-v0");
		importTester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(expectedKey, importTester.getOutput().trim());

		Blob MASTER_KEY=BIP39.getSeed(bip39, bipPassphrase);
		AKeyPair GENESIS_KP=SLIP10.deriveKeyPair(MASTER_KEY);
		assertEquals(expectedKey,GENESIS_KP.getAccountKey().toHexString());
		
		String javaHome = System.getProperty("java.home");
		String javaCmd = javaHome + File.separator + "bin" + File.separator + "java";
		String classpath = System.getProperty("java.class.path");
		String modpath=System.getProperty("jdk.module.path");
		String className = convex.cli.Main.class.getName();

		List<String> cmd = new ArrayList<>();
		cmd.add(javaCmd);
		// The test force-terminates this child on Windows, where JVM shutdown hooks
		// cannot run. Isolate all child temp files so JUnit removes them afterwards.
		cmd.add("-Djava.io.tmpdir="+processTempDirectory.toAbsolutePath());
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
		cmd.add("--no-tray");
		// No --ports: peers take auto-assigned ports, reported via "Peer ports:"

		ProcessBuilder builder = new ProcessBuilder(cmd);
		process = builder.start();

		CompletableFuture<List<String>> started=new CompletableFuture<>();
		checker = new Thread(() -> {
			ArrayList<String> lines=new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
				String line;
				while ((line=reader.readLine())!=null) {
					if (!started.isDone()) {
						lines.add(line);
						if (line.contains("Started: 1")) started.complete(List.copyOf(lines));
					}
					// Keep draining stderr after startup so the child cannot block on a full pipe.
				}
				started.completeExceptionally(new IOException("Local network exited before startup: "+lines));
			} catch (Throwable e) {
				started.completeExceptionally(e);
			}
		},"local-peer-startup");
		checker.setDaemon(true);
		checker.start();
		List<String> outputList=started.get(10,TimeUnit.SECONDS);
		assertTrue(process.isAlive(),"Local network exited after startup");

		// Parse the actual auto-assigned port from the "Peer ports:" line
		// (printed before "Started: 1", so already collected by the checker)
		for (String line : outputList) {
			Matcher m = PEER_PORTS_PATTERN.matcher(line);
			if (m.find()) {
				actualPort = Integer.parseInt(m.group(1));
				break;
			}
		}
		if (actualPort < 0) {
			fail("Could not parse peer port from output:\n"+Strings.join("\n", outputList));
		}
	}

	private static final Pattern PEER_PORTS_PATTERN = Pattern.compile("Peer ports: (\\d+)");

	
	@Test
	public void testSync() throws IOException, TimeoutException, InterruptedException, ExecutionException {
		Blob seed=Blobs.createRandom(32);
		AKeyPair nkp=AKeyPair.create(seed);

		CLTester newKeyTester = CLTester.run("key", "import", "--type", "seed", "--storepass",
				new String(KEYSTORE_PASSWORD), "--keystore", KEYSTORE_FILENAME, "--keypass", new String(KEY_PASSWORD),
				"--text", seed.toString(), "--passphrase", bipPassphrase,"-v0");
		newKeyTester.assertExitCode(ExitCodes.SUCCESS);
		
		try (CLTester syncTester = CLTester.runAsync(
				"peer", 
				"start",
				"--peer-key",nkp.getAccountKey().toHexString(),
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME, 
				"--etch=temp",
				"--norest",
				"--no-tray",
				"--peer-keypass",new String(KEY_PASSWORD),
				"--host", "localhost",
				"--port",Integer.toString(actualPort)
				// no --peer-port: the syncing peer takes a random port (default 0)
			)) {
			syncTester.waitForStart(5000);
			syncTester.interrupt();
			syncTester.assertExitCode(ExitCodes.INTERRUPT);
		}
	}

	@Test
	public void testProcess() throws IOException, TimeoutException, InterruptedException {
		assertTrue(process.isAlive());

		InetSocketAddress sa=new InetSocketAddress("localhost",actualPort);
		try (Convex convex = ConvexRemote.connect(sa)) {
			Result r=convex.requestStatusSync();
			assertFalse(r.isError());
		}
	}

	@AfterAll
	public static void tearDown() {
		assertAll("Stop the local network and its reader",
			() -> {
				if (process!=null) {
					process.destroy();
					if (!process.waitFor(5,TimeUnit.SECONDS)) {
						process.destroyForcibly();
						assertTrue(process.waitFor(5,TimeUnit.SECONDS),"Local peer process did not stop");
					}
				}
			},
			() -> {
				if (checker!=null) {
					checker.join(5000);
					assertFalse(checker.isAlive(),"Startup reader did not stop");
				}
			});
	}
}
