package convex.peer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import etch.EtchStore;

public class ConfigTest {
	
	@Test public void testStoreSetup() {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
		EtchStore store=Config.ensureStore(config);
		assertNotNull(store);
		
		assertTrue(store.getFile().exists());
	}
	
	@Test public void testNoKeypair() {
		// Empty config should create a default new store
		HashMap<Keyword,Object> config=new HashMap<>();
	
		assertThrows(ConfigException.class, ()->Config.ensurePeerKey(config));
	}

}
