package convex.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;

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

	// ========== Config.checkStore tests ==========

	@Test
	public void testCheckStoreMemory() throws IOException {
		HashMap<Keyword, Object> config = new HashMap<>();
		config.put(Keywords.STORE, "memory");
		AStore store = Config.checkStore(config);
		assertInstanceOf(MemoryStore.class, store);
	}

	@Test
	public void testCheckStoreNull() throws IOException {
		HashMap<Keyword, Object> config = new HashMap<>();
		AStore store = Config.checkStore(config);
		assertNull(store);
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
	public void testToLegacyStoreMemory() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"store\": \"memory\"}}");
		assertEquals("memory", config.getStorePath());
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("memory", legacy.get(Keywords.STORE));
	}

	@Test
	public void testToLegacySource() {
		PeerConfig config = PeerConfig.parse("{\"peer\": {\"source\": \"convex.world:18888\"}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("convex.world:18888", legacy.get(Keywords.SOURCE));
	}

	@Test
	public void testToLegacyNoRestKeys() {
		// PeerConfig.toLegacy() should NOT include REST keys
		PeerConfig config = PeerConfig.parse(
			"{\"rest\": {\"baseUrl\": \"https://example.com\", \"faucet\": true}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertNull(legacy.get(Keywords.BASE_URL));
		assertNull(legacy.get(Keywords.FAUCET));
	}

	@Test
	public void testJson5Comments() {
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
			}
			""");
		assertEquals(18888, config.getPeerPort());
		assertTrue(config.isRestore());
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

		// Auth
		assertEquals(7200L, config.getTokenExpiry());
		assertFalse(config.isPublicAccess());

		// Legacy conversion
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals(18888, legacy.get(Keywords.PORT));
		assertInstanceOf(AKeyPair.class, legacy.get(Keywords.KEYPAIR));
		assertEquals("peer.example.com", legacy.get(Keywords.URL));
	}
}
