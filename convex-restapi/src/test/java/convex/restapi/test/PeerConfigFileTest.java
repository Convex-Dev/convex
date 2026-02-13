package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.data.Keyword;
import convex.peer.PeerConfig;

/**
 * Tests that the example JSON5 config file parses correctly and produces
 * valid configuration with expected defaults.
 */
public class PeerConfigFileTest {

	private PeerConfig loadExampleConfig() throws IOException {
		try (InputStream is = getClass().getResourceAsStream("/config-example.json5")) {
			assertNotNull(is, "config-example.json5 must be on test classpath");
			String json5 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			return PeerConfig.parse(json5);
		}
	}

	@Test
	public void testExampleConfigParses() throws IOException {
		PeerConfig config = loadExampleConfig();
		assertNotNull(config);
		assertNotNull(config.getMap());
	}

	@Test
	public void testExampleConfigPeerDefaults() throws IOException {
		PeerConfig config = loadExampleConfig();
		// Example config has booleans set explicitly
		assertTrue(config.isRestore());
		assertTrue(config.isPersist());
		assertTrue(config.isAutoManage());
		// Port and keypair are commented out
		assertNull(config.getPeerPort());
		assertNull(config.getKeypairSeed());
	}

	@Test
	public void testExampleConfigRest() throws IOException {
		PeerConfig config = loadExampleConfig();
		assertEquals(8080, config.getRestPort());
		assertFalse(config.isFaucetEnabled());
		assertNull(config.getBaseUrl());
	}

	@Test
	public void testExampleConfigMcp() throws IOException {
		PeerConfig config = loadExampleConfig();
		assertTrue(config.isMcpEnabled());
		assertFalse(config.isSigningEnabled());
		assertFalse(config.isElevatedEnabled()); // follows signing default
	}

	@Test
	public void testExampleConfigAuth() throws IOException {
		PeerConfig config = loadExampleConfig();
		assertEquals(86400L, config.getTokenExpiry());
		assertTrue(config.isPublicAccess());
	}

	@Test
	public void testExampleConfigToLegacy() throws IOException {
		PeerConfig config = loadExampleConfig();
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertNotNull(legacy);
		// Peer port is commented out in example config, so not in legacy
		assertNull(legacy.get(convex.core.cvm.Keywords.PORT));
		// Faucet is false in example config
		assertEquals(false, legacy.get(convex.core.cvm.Keywords.FAUCET));
		// Booleans from peer section
		assertEquals(true, legacy.get(convex.core.cvm.Keywords.RESTORE));
		assertEquals(true, legacy.get(convex.core.cvm.Keywords.PERSIST));
		assertEquals(true, legacy.get(convex.core.cvm.Keywords.AUTO_MANAGE));
	}

	@Test
	public void testExampleConfigSections() throws IOException {
		PeerConfig config = loadExampleConfig();
		assertNotNull(config.getSection(PeerConfig.PEER));
		assertNotNull(config.getSection(PeerConfig.REST));
		assertNotNull(config.getSection(PeerConfig.MCP));
		assertNotNull(config.getSection(PeerConfig.AUTH));
	}
}
