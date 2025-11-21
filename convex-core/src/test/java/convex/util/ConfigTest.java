package convex.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.util.ConfigUtils;

public class ConfigTest {

	
	@Test public void testConfigFiles() throws MalformedURLException, IOException, URISyntaxException {
		ACell cfg=ConfigUtils.readConfig(ConfigTest.class.getResource("/utils/config-test.json"));
		
		assertTrue(cfg instanceof AMap);
		

	}
}
