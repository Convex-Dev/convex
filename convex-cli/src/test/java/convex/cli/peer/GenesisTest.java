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
import convex.core.crypto.SLIP10;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.cvm.Keywords;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;


public class GenesisTest {
	private static final char[] KEYSTORE_PASSWORD = "localStorePassword".toCharArray();
	private static final char[] KEY_PASSWORD = "localKeyPassword".toCharArray();

	private static final File TEMP_FILE;
	private static final File TEMP_ETCH;
	private static final String KEYSTORE_FILENAME;
	
	private static final String bip39="miracle source lizard gun neutral year dust recycle drama nephew infant enforce";
	private static final String bipPassphrase="thisIsNotSecure";
	private static final AccountKey expectedKey=AccountKey.parse("7e965F07c3A2051e01399D545749102CcF30c731CF8C40e73a0B03b5C37bE34F");
	
	
	static {
		try {
			TEMP_FILE=Helpers.createTempFile("tempLocalKeystore", ".pfx");
			TEMP_ETCH=Helpers.createTempFile("tempEtchDatabase", ".db");
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
		String pubKey=expectedKey.toString();
		assertEquals(expectedKey.toHexString(),importTester.getOutput().trim());
		
		CLTester tester =  CLTester.run(
				"peer", "genesis", "-n", "-v1",
				"--peer-key", pubKey,
				"--peer-keypass", new String(KEY_PASSWORD),
				"--etch", TEMP_ETCH.getCanonicalPath(), 
				"--key", pubKey,
				"--keypass", new String(KEY_PASSWORD),
				"--keystore", KEYSTORE_FILENAME
		);
		tester.assertExitCode(ExitCodes.SUCCESS);
		
		AKeyPair kp=SLIP10.deriveKeyPair(BIP39.getSeed(bip39, bipPassphrase),new int[] {44,864,0,0,0});
		assertEquals(expectedKey,kp.getAccountKey());
		
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.STORE, TEMP_ETCH);
		config.put(Keywords.KEYPAIR, kp);
		Server s=API.launchPeer(config); 
		assertEquals(expectedKey,s.getPeerKey());
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
