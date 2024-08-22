package convex.cli.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.PFXTools;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.Server;
import etch.EtchStore;

public class ClientTest {
	private static final char[] KEYSTORE_PASSWORD = "genesisStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "genesisKeyPassword".toCharArray();

	private static final File TEMP_KEYSTORE;
	private static final File TEMP_ETCH;
	private static final String KEYSTORE_FILENAME;
	
	static final AKeyPair kp=AKeyPair.createSeeded(124564);
	static KeyStore keystore=null;
	
	static {
		try {
			TEMP_KEYSTORE=Helpers.createTempFile("tempClientKeystore", ".pfx");
			TEMP_ETCH=Helpers.createTempFile("tempEtchClientDatabase", ".db");
			keystore=PFXTools.createStore(TEMP_KEYSTORE, KEYSTORE_PASSWORD);
			PFXTools.setKeyPair(keystore, kp, KEY_PASSWORD);
			PFXTools.saveStore(keystore, TEMP_KEYSTORE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_KEYSTORE.getCanonicalPath();
		} catch (Exception t) {
			throw Utils.sneakyThrow(t);
		} 
	}
	
	@Test public void testKey() {
		assertEquals("4a12d868487648cd7a206f6d4879a7941463d80e185ccf6bb4e951429c4f4e37",kp.getAccountKey().toHexString());
	}
 	
	@Test public void testClientCommands() throws IOException, TimeoutException {
		
		try (EtchStore store = EtchStore.createTemp(TEMP_ETCH.getCanonicalPath())) {

			HashMap<Keyword,Object> config=new HashMap<>();
			config.put(Keywords.KEYPAIR, kp);
			config.put(Keywords.STORE, store);
			Server s=API.launchPeer(config);
		
			CLTester tester =  CLTester.run(
					"query", "-a", "#11",
					"--keystore", KEYSTORE_FILENAME, 
					"--keypass",new String(KEY_PASSWORD),
					"*balance*"
				);
			tester.assertExitCode(ExitCodes.SUCCESS);
			
			tester =  CLTester.run(
					"transact", "-a", "11",
					"--keystore", KEYSTORE_FILENAME, 
					"--keypass",new String(KEY_PASSWORD),
					"*balance*"
				);
			tester.assertExitCode(ExitCodes.SUCCESS);

		
			s.close();
		}
	}

}
