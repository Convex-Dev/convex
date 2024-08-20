package convex.peer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import convex.core.store.AStore;

public class ConfigTest {
	
	@Test public void testStoreSetup() {
		HashMap<Keyword,Object> config=new HashMap<>();
		AStore store=Config.ensureStore(config);
		assertNotNull(store);
	}

}
