package convex.cli.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.security.KeyStore;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.Keyword;
import convex.core.cvm.Keywords;
import convex.core.store.MemoryStore;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;

public class ClientTest {
	private static final char[] KEYSTORE_PASSWORD = "genesisStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "genesisKeyPassword".toCharArray();

	private static final File TEMP_KEYSTORE;
	private static final String KEYSTORE_FILENAME;

	static final AKeyPair kp=AKeyPair.createSeeded(124564);
	static KeyStore keystore=null;

	static {
		try {
			TEMP_KEYSTORE=Helpers.createTempFile("tempClientKeystore", ".pfx");
			keystore=PFXTools.createStore(TEMP_KEYSTORE, KEYSTORE_PASSWORD);
			PFXTools.setKeyPair(keystore, kp, KEY_PASSWORD);
			PFXTools.saveStore(keystore, TEMP_KEYSTORE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_KEYSTORE.getCanonicalPath();
		} catch (Exception t) {
			throw Utils.sneakyThrow(t);
		}
	}

	@Test public void testClientCommands() throws Exception {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keywords.PORT, 0);
		// The server borrows the store, so it closes first
		try (MemoryStore store=new MemoryStore()) {
			config.put(Keywords.STORE, store);
			try (Server s=API.launchPeer(config)) {
				String port=Integer.toString(s.getPort());

				CLTester tester;

				tester =  CLTester.run(
						"status",
						"--host", "localhost",
						"--port",port
					);
				tester.assertExitCode(ExitCodes.SUCCESS);
				assertTrue(tester.getOutput().contains(Keywords.GENESIS.toString()));

				tester=  CLTester.run(
						"query", "-a", "#11",
						"--host", "localhost",
						"--port",port,
						"--keystore", KEYSTORE_FILENAME,
						"--keypass",new String(KEY_PASSWORD),
						"*balance*"
					);
				tester.assertExitCode(ExitCodes.SUCCESS);

				tester =  CLTester.run(
						"transact", "-a", "11",
						"--host", "localhost",
						"--port",port,
						"--keystore", KEYSTORE_FILENAME,
						"--keypass",new String(KEY_PASSWORD),
						"*balance*"
					);
				tester.assertExitCode(ExitCodes.SUCCESS);
			}
		}
	}

}
