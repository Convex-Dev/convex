package convex.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;

public class PeerConfigTest {

	@Test
	public void testParseMinimal() {
		PeerConfig config = PeerConfig.parse("{}");
		assertNotNull(config);
		assertNotNull(config.getMap());
	}

	@Test
	public void testParseNull() {
		PeerConfig config = PeerConfig.create(null);
		assertNotNull(config.getMap());
		assertEquals(Maps.empty(), config.getMap());
	}

	@Test
	public void testPeerPortDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertNull(config.getPeerPort());
	}

	@Test
	public void testPeerPort() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"port\": 18888}}");
		assertEquals(18888, config.getPeerPort());
	}

	@Test
	public void testKeypairSeed() {
		String seed = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"keypair\": \"" + seed + "\"}}");
		assertEquals(seed, config.getKeypairSeed());
	}

	@Test
	public void testKeypairSeedDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertNull(config.getKeypairSeed());
	}

	@Test
	public void testStorePathDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertNull(config.getStorePath());
	}

	@Test
	public void testStorePath() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"store\": \"/tmp/peer.etch\"}}");
		assertEquals("/tmp/peer.etch", config.getStorePath());
	}

	@Test
	public void testPeerUrl() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"url\": \"peer.convex.live\"}}");
		assertEquals("peer.convex.live", config.getPeerUrl());
	}

	@Test
	public void testBooleanDefaults() {
		PeerConfig config = PeerConfig.parse("{}");
		assertTrue(config.isRestore());
		assertTrue(config.isPersist());
		assertTrue(config.isAutoManage());
	}

	@Test
	public void testBooleanOverrides() {
		PeerConfig config = PeerConfig.parse(
			"{\"peer\": {\"restore\": false, \"persist\": false, \"autoManage\": false}}");
		assertFalse(config.isRestore());
		assertFalse(config.isPersist());
		assertFalse(config.isAutoManage());
	}

	@Test
	public void testOutgoingConnections() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"outgoingConnections\": 20}}");
		assertEquals(20, config.getOutgoingConnections());
	}

	@Test
	public void testRestPortDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertNull(config.getRestPort());
	}

	@Test
	public void testRestPort() {
		PeerConfig config = PeerConfig.parse("{\"rest\": {\"port\": 9090}}");
		assertEquals(9090, config.getRestPort());
	}

	@Test
	public void testBaseUrl() {
		PeerConfig config = PeerConfig.parse("{\"rest\": {\"baseUrl\": \"https://peer.example.com\"}}");
		assertEquals("https://peer.example.com", config.getBaseUrl());
	}

	@Test
	public void testFaucetDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertFalse(config.isFaucetEnabled());
	}

	@Test
	public void testFaucetEnabled() {
		PeerConfig config = PeerConfig.parse("{\"rest\": {\"faucet\": true}}");
		assertTrue(config.isFaucetEnabled());
	}

	@Test
	public void testMcpEnabledDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertTrue(config.isMcpEnabled());
	}

	@Test
	public void testMcpDisabled() {
		PeerConfig config = PeerConfig.parse("{\"mcp\": {\"enabled\": false}}");
		assertFalse(config.isMcpEnabled());
	}

	@Test
	public void testSigningDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertFalse(config.isSigningEnabled());
	}

	@Test
	public void testSigningEnabled() {
		PeerConfig config = PeerConfig.parse("{\"mcp\": {\"signing\": true}}");
		assertTrue(config.isSigningEnabled());
	}

	@Test
	public void testElevatedDefaultFollowsSigning() {
		PeerConfig off = PeerConfig.parse("{\"mcp\": {\"signing\": false}}");
		assertFalse(off.isElevatedEnabled());

		PeerConfig on = PeerConfig.parse("{\"mcp\": {\"signing\": true}}");
		assertTrue(on.isElevatedEnabled());
	}

	@Test
	public void testElevatedExplicit() {
		PeerConfig config = PeerConfig.parse("{\"mcp\": {\"signing\": true, \"elevated\": false}}");
		assertTrue(config.isSigningEnabled());
		assertFalse(config.isElevatedEnabled());
	}

	@Test
	public void testTokenExpiryDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertEquals(86400L, config.getTokenExpiry());
	}

	@Test
	public void testTokenExpiry() {
		PeerConfig config = PeerConfig.parse("{\"auth\": {\"tokenExpiry\": 3600}}");
		assertEquals(3600L, config.getTokenExpiry());
	}

	@Test
	public void testPublicAccessDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertTrue(config.isPublicAccess());
	}

	@Test
	public void testPublicAccessDisabled() {
		PeerConfig config = PeerConfig.parse("{\"auth\": {\"publicAccess\": false}}");
		assertFalse(config.isPublicAccess());
	}

	@Test
	public void testGetSection() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"port\": 18888}}");
		AMap<AString, ACell> peer = config.getSection(PeerConfig.PEER);
		assertNotNull(peer);
		assertFalse(peer.isEmpty());

		// Missing section returns empty map
		AMap<AString, ACell> auth = config.getSection(PeerConfig.AUTH);
		assertNotNull(auth);
		assertTrue(auth.isEmpty());
	}

	@Test
	public void testToolsConfig() {
		PeerConfig config = PeerConfig.parse(
			"{\"mcp\": {\"tools\": {\"transact\": {\"enabled\": true}}}}");
		AMap<AString, ACell> tools = config.getToolsConfig();
		assertFalse(tools.isEmpty());
	}

	@Test
	public void testToolsConfigDefault() {
		PeerConfig config = PeerConfig.parse("{}");
		assertTrue(config.getToolsConfig().isEmpty());
	}

	// ========== Legacy bridge tests ==========

	@Test
	public void testToLegacyEmpty() {
		PeerConfig config = PeerConfig.parse("{}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertNotNull(legacy);
		assertTrue(legacy.isEmpty());
	}

	@Test
	public void testToLegacyPeerPort() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"port\": 18888}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals(18888, legacy.get(Keywords.PORT));
	}

	@Test
	public void testToLegacyKeypair() {
		String seed = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"keypair\": \"" + seed + "\"}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		Object kp = legacy.get(Keywords.KEYPAIR);
		assertInstanceOf(AKeyPair.class, kp);
		assertEquals(seed, ((AKeyPair) kp).getSeed().toHexString());
	}

	@Test
	public void testToLegacyBooleans() {
		PeerConfig config = PeerConfig.parse(
			"{\"peer\": {\"restore\": false, \"persist\": false, \"autoManage\": false}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals(false, legacy.get(Keywords.RESTORE));
		assertEquals(false, legacy.get(Keywords.PERSIST));
		assertEquals(false, legacy.get(Keywords.AUTO_MANAGE));
	}

	@Test
	public void testToLegacyRestKeys() {
		PeerConfig config = PeerConfig.parse(
			"{\"rest\": {\"baseUrl\": \"https://example.com\", \"faucet\": true}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("https://example.com", legacy.get(Keywords.BASE_URL));
		assertEquals(true, legacy.get(Keywords.FAUCET));
	}

	@Test
	public void testToLegacyUrl() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"url\": \"peer.convex.live\"}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("peer.convex.live", legacy.get(Keywords.URL));
	}

	@Test
	public void testToLegacyStore() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"store\": \"temp\"}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("temp", legacy.get(Keywords.STORE));
	}

	@Test
	public void testToLegacySource() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"source\": \"convex.world:18888\"}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("convex.world:18888", legacy.get(Keywords.SOURCE));
	}

	@Test
	public void testJson5Comments() {
		// JSON5 supports comments — verify they parse correctly
		PeerConfig config = PeerConfig.parse("""
			{
				// Line comment
				"peer": {
					/* Block comment */
					"port": 18888,
				}
			}
			""");
		assertEquals(18888, config.getPeerPort());
	}

	@Test
	public void testJson5TrailingCommas() {
		PeerConfig config = PeerConfig.parse("""
			{
				"peer": {
					"port": 18888,
					"restore": true,
				},
				"rest": {
					"faucet": true,
				},
			}
			""");
		assertEquals(18888, config.getPeerPort());
		assertTrue(config.isFaucetEnabled());
	}

	@Test
	public void testFullConfig() {
		String seed = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
		PeerConfig config = PeerConfig.parse("""
			{
				"peer": {
					"port": 18888,
					"keypair": "%s",
					"url": "peer.example.com",
					"restore": true,
					"persist": true,
				},
				"rest": {
					"port": 8080,
					"baseUrl": "https://peer.example.com",
					"faucet": true,
				},
				"mcp": {
					"enabled": true,
					"signing": true,
					"elevated": true,
				},
				"auth": {
					"tokenExpiry": 7200,
					"publicAccess": false,
				},
			}
			""".formatted(seed));

		// Peer
		assertEquals(18888, config.getPeerPort());
		assertEquals(seed, config.getKeypairSeed());
		assertEquals("peer.example.com", config.getPeerUrl());
		assertTrue(config.isRestore());
		assertTrue(config.isPersist());

		// REST
		assertEquals(8080, config.getRestPort());
		assertEquals("https://peer.example.com", config.getBaseUrl());
		assertTrue(config.isFaucetEnabled());

		// MCP
		assertTrue(config.isMcpEnabled());
		assertTrue(config.isSigningEnabled());
		assertTrue(config.isElevatedEnabled());

		// Auth
		assertEquals(7200L, config.getTokenExpiry());
		assertFalse(config.isPublicAccess());

		// Legacy conversion
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals(18888, legacy.get(Keywords.PORT));
		assertInstanceOf(AKeyPair.class, legacy.get(Keywords.KEYPAIR));
		assertEquals("peer.example.com", legacy.get(Keywords.URL));
		assertEquals("https://peer.example.com", legacy.get(Keywords.BASE_URL));
		assertEquals(true, legacy.get(Keywords.FAUCET));
	}
}
