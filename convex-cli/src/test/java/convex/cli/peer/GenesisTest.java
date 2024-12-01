package convex.cli.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.cli.Helpers;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.BIP39;
import convex.core.crypto.PFXTools;
import convex.core.data.Keyword;
import convex.core.cvm.Keywords;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;

public class GenesisTest {
	private static final char[] KEYSTORE_PASSWORD = "genesisStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "genesisKeyPassword".toCharArray();

	private static final File TEMP_FILE;
	private static final File TEMP_ETCH;
	private static final String KEYSTORE_FILENAME;
	
	private static final String bip39="miracle source lizard gun neutral year dust recycle drama nephew infant enforce";
	private static final String bipPassphrase="thisIsNotSecure";
	private static final String expectedKey="09a5528c53579e1ee76a327ab8bc9db7b2853dd17391a6e3fe7f3052c6e8686a";
	
	
	static {
		try {
			TEMP_FILE=Helpers.createTempFile("tempGenesisKeystore", ".pfx");
			TEMP_ETCH=Helpers.createTempFile("tempEtxhDatabase", ".db");
			PFXTools.createStore(TEMP_FILE, KEYSTORE_PASSWORD);
			KEYSTORE_FILENAME = TEMP_FILE.getCanonicalPath();
		} catch (Exception t) {
			throw Utils.sneakyThrow(t);
		} 
	}

	@Test public void testGenesisPeer() throws TimeoutException, InterruptedException, IOException, LaunchException, ConfigException {
		CLTester importTester =  CLTester.run(
				"key", 
				"import",
				"--type","bip39",
				"--storepass", new String(KEYSTORE_PASSWORD), 
				"--keystore", KEYSTORE_FILENAME, 
				"--keypass",new String(KEY_PASSWORD),
				"--text", bip39, 
				"--passphrase", bipPassphrase
			);
		importTester.assertExitCode(ExitCodes.SUCCESS);
		assertEquals(expectedKey,importTester.getOutput().trim());
		
		CLTester tester =  CLTester.run(
				"peer", "genesis", "-n", "-v1",
				"--peer-key", expectedKey,
				"--peer-keypass", new String(KEY_PASSWORD),
				"--etch", TEMP_ETCH.getCanonicalPath(), 
				"--key", expectedKey,
				"--keypass", new String(KEY_PASSWORD),
				"--keystore", KEYSTORE_FILENAME
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		
		AKeyPair kp=BIP39.seedToKeyPair(BIP39.getSeed(bip39, bipPassphrase));
		assertEquals(expectedKey,kp.getAccountKey().toHexString());
		
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE, TEMP_ETCH);
		config.put(Keywords.KEYPAIR, kp);
		Server s=API.launchPeer(config); 
		assertEquals(expectedKey,s.getPeerKey() .toHexString());
		s.shutdown();
		
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
