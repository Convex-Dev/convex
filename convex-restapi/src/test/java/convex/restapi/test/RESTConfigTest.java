package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.peer.PeerConfig;
import convex.restapi.RESTConfig;

/**
 * Tests for {@link RESTConfig} — REST/MCP/OAuth accessors, toLegacy override,
 * and example config file loading.
 */
public class RESTConfigTest {

	// ========== REST accessors ==========

	@Test
	public void testRestPortDefault() {
		RESTConfig config = RESTConfig.parse("{}");
		assertNull(config.getRestPort());
	}

	@Test
	public void testRestPort() {
		RESTConfig config = RESTConfig.parse("{\"rest\": {\"port\": 9090}}");
		assertEquals(9090, config.getRestPort());
	}

	@Test
	public void testBaseUrlDefault() {
		RESTConfig config = RESTConfig.parse("{}");
		assertNull(config.getBaseUrl());
	}

	@Test
	public void testBaseUrl() {
		RESTConfig config = RESTConfig.parse("{\"rest\": {\"baseUrl\": \"https://peer.example.com\"}}");
		assertEquals("https://peer.example.com", config.getBaseUrl());
	}

	@Test
	public void testFaucetDefault() {
		RESTConfig config = RESTConfig.parse("{}");
		assertFalse(config.isFaucetEnabled());
	}

	@Test
	public void testFaucetEnabled() {
		RESTConfig config = RESTConfig.parse("{\"rest\": {\"faucet\": true}}");
		assertTrue(config.isFaucetEnabled());
	}

	// ========== MCP accessors ==========

	@Test
	public void testMcpEnabledDefault() {
		RESTConfig config = RESTConfig.parse("{}");
		assertTrue(config.isMcpEnabled());
	}

	@Test
	public void testMcpDisabled() {
		RESTConfig config = RESTConfig.parse("{\"mcp\": {\"enabled\": false}}");
		assertFalse(config.isMcpEnabled());
	}

	@Test
	public void testSigningDefault() {
		RESTConfig config = RESTConfig.parse("{}");
		assertFalse(config.isSigningEnabled());
	}

	@Test
	public void testSigningEnabled() {
		RESTConfig config = RESTConfig.parse("{\"mcp\": {\"signing\": true}}");
		assertTrue(config.isSigningEnabled());
	}

	@Test
	public void testElevatedDefaultFollowsSigning() {
		RESTConfig off = RESTConfig.parse("{\"mcp\": {\"signing\": false}}");
		assertFalse(off.isElevatedEnabled());

		RESTConfig on = RESTConfig.parse("{\"mcp\": {\"signing\": true}}");
		assertTrue(on.isElevatedEnabled());
	}

	@Test
	public void testElevatedExplicit() {
		RESTConfig config = RESTConfig.parse("{\"mcp\": {\"signing\": true, \"elevated\": false}}");
		assertTrue(config.isSigningEnabled());
		assertFalse(config.isElevatedEnabled());
	}

	@Test
	public void testToolsConfig() {
		RESTConfig config = RESTConfig.parse(
			"{\"mcp\": {\"tools\": {\"transact\": {\"enabled\": true}}}}");
		AMap<AString, ACell> tools = config.getToolsConfig();
		assertFalse(tools.isEmpty());
	}

	@Test
	public void testToolsConfigDefault() {
		RESTConfig config = RESTConfig.parse("{}");
		assertTrue(config.getToolsConfig().isEmpty());
	}

	// ========== OAuth accessors ==========

	@Test
	public void testOAuthNotConfigured() {
		RESTConfig config = RESTConfig.parse("{}");
		assertNull(config.getOAuthProvider("google"));
		assertNull(config.getOAuthClientId("google"));
		assertNull(config.getOAuthClientSecret("google"));
	}

	@Test
	public void testOAuthConfigured() {
		RESTConfig config = RESTConfig.parse("""
			{
				"auth": {
					"oauth": {
						"google": {
							"clientId": "test-client-id",
							"clientSecret": "test-secret"
						}
					}
				}
			}
			""");
		assertNotNull(config.getOAuthProvider("google"));
		assertEquals("test-client-id", config.getOAuthClientId("google"));
		assertEquals("test-secret", config.getOAuthClientSecret("google"));
		assertNull(config.getOAuthProvider("github"));
	}

	// ========== Peer accessors still work (inherited) ==========

	@Test
	public void testInheritedPeerAccessors() {
		RESTConfig config = RESTConfig.parse("{\"peer\": {\"port\": 18888}}");
		assertEquals(18888, config.getPeerPort());
		assertNotNull(config.getMap());
	}

	@Test
	public void testInheritedAuthAccessors() {
		RESTConfig config = RESTConfig.parse("{\"auth\": {\"tokenExpiry\": 3600}}");
		assertEquals(3600L, config.getTokenExpiry());
	}

	// ========== toLegacy ==========

	@Test
	public void testToLegacyIncludesRestKeys() {
		RESTConfig config = RESTConfig.parse(
			"{\"rest\": {\"baseUrl\": \"https://example.com\", \"faucet\": true}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals("https://example.com", legacy.get(Keywords.BASE_URL));
		assertEquals(true, legacy.get(Keywords.FAUCET));
	}

	@Test
	public void testToLegacyIncludesPeerKeys() {
		RESTConfig config = RESTConfig.parse(
			"{\"peer\": {\"port\": 18888, \"restore\": false}}");
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals(18888, legacy.get(Keywords.PORT));
		assertEquals(false, legacy.get(Keywords.RESTORE));
	}

	@Test
	public void testToLegacyFull() {
		String seed = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
		RESTConfig config = RESTConfig.parse("""
			{
				"peer": {
					"port": 18888,
					"keypair": "%s",
					"url": "peer.example.com",
				},
				"rest": {
					"baseUrl": "https://peer.example.com",
					"faucet": true,
				},
			}
			""".formatted(seed));

		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertEquals(18888, legacy.get(Keywords.PORT));
		assertEquals("peer.example.com", legacy.get(Keywords.URL));
		assertEquals("https://peer.example.com", legacy.get(Keywords.BASE_URL));
		assertEquals(true, legacy.get(Keywords.FAUCET));
		assertNotNull(legacy.get(Keywords.KEYPAIR));
	}

	// ========== Example config file ==========

	private RESTConfig loadExampleConfig() throws IOException {
		try (InputStream is = getClass().getResourceAsStream("/config-example.json5")) {
			assertNotNull(is, "config-example.json5 must be on test classpath");
			String json5 = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			return RESTConfig.parse(json5);
		}
	}

	@Test
	public void testExampleConfigParses() throws IOException {
		RESTConfig config = loadExampleConfig();
		assertNotNull(config);
		assertNotNull(config.getMap());
	}

	@Test
	public void testExampleConfigPeerDefaults() throws IOException {
		RESTConfig config = loadExampleConfig();
		assertTrue(config.isRestore());
		assertTrue(config.isPersist());
		assertTrue(config.isAutoManage());
		assertNull(config.getPeerPort());
		assertNull(config.getKeypairSeed());
	}

	@Test
	public void testExampleConfigRest() throws IOException {
		RESTConfig config = loadExampleConfig();
		assertEquals(8080, config.getRestPort());
		assertFalse(config.isFaucetEnabled());
		assertNull(config.getBaseUrl());
	}

	@Test
	public void testExampleConfigMcp() throws IOException {
		RESTConfig config = loadExampleConfig();
		assertTrue(config.isMcpEnabled());
		assertFalse(config.isSigningEnabled());
		assertFalse(config.isElevatedEnabled());
	}

	@Test
	public void testExampleConfigAuth() throws IOException {
		RESTConfig config = loadExampleConfig();
		assertEquals(86400L, config.getTokenExpiry());
		assertTrue(config.isPublicAccess());
	}

	@Test
	public void testExampleConfigToLegacy() throws IOException {
		RESTConfig config = loadExampleConfig();
		HashMap<Keyword, Object> legacy = config.toLegacy();
		assertNotNull(legacy);
		assertNull(legacy.get(Keywords.PORT));
		assertEquals(false, legacy.get(Keywords.FAUCET));
		assertEquals(true, legacy.get(Keywords.RESTORE));
		assertEquals(true, legacy.get(Keywords.PERSIST));
		assertEquals(true, legacy.get(Keywords.AUTO_MANAGE));
	}

	@Test
	public void testExampleConfigSections() throws IOException {
		RESTConfig config = loadExampleConfig();
		assertNotNull(config.getSection(PeerConfig.PEER));
		assertNotNull(config.getSection(RESTConfig.REST));
		assertNotNull(config.getSection(RESTConfig.MCP));
		assertNotNull(config.getSection(PeerConfig.AUTH));
	}
}
