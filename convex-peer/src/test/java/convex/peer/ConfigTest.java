package convex.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.etch.EtchStore;

public class ConfigTest {
	
	@Test public void testStoreSetup() throws ConfigException {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
		EtchStore store=Config.ensureStore(config);
		assertNotNull(store);
		
		assertTrue(store.getFile().exists());
	}
	
	@Test public void testKeypair() throws ConfigException {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
	
		assertThrows(ConfigException.class, ()->Config.ensurePeerKey(config));
		
		AKeyPair kp=AKeyPair.generate();
		config.put(Keywords.KEYPAIR,kp);
		assertSame(kp,Config.ensurePeerKey(config));
		
		// Corrupt the key
		config.put(Keywords.KEYPAIR,1L);
		assertThrows(ConfigException.class, ()->Config.ensurePeerKey(config));
	}
	
	@Test public void testNullLaunch() {
		assertThrows(ConfigException.class,()->API.launchPeer(new HashMap<>()));
		assertThrows(NullPointerException.class,()->API.launchPeer(null));
	}
	
	@Test public void testMinimalLaunch() throws InterruptedException, PeerException {
		AKeyPair kp=AKeyPair.generate();
		AccountKey peerKey=kp.getAccountKey();
		
		{ // just a peer keypair
			Map<Keyword,Object> config=Config.of(Keywords.KEYPAIR,kp);
			Server s=API.launchPeer(config);
			assertSame(kp,s.getKeyPair());
			Peer p=s.getPeer();
			assertTrue(p.getConsensusState().getPeers().containsKey(peerKey));
			assertEquals(0,p.getFinalityPoint());
			s.close();
		}
	}

}
