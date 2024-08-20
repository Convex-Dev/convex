package convex.peer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import etch.EtchStore;

public class ConfigTest {
	
	@Test public void testStoreSetup() {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
		EtchStore store=Config.ensureStore(config);
		assertNotNull(store);
		
		assertTrue(store.getFile().exists());
	}
	
	@Test public void testKeypair() {
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

}
