package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.json.JWT;
import convex.core.data.AccountKey;
import convex.core.lang.RT;
import convex.peer.auth.PeerAuth;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Tests for signing MCP tools using the official MCP SDK client.
 *
 * Validates that the signing tools work correctly through the full MCP
 * protocol stack (initialize, listTools, callTool) rather than raw HTTP.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class SigningMcpClientTest extends ARESTTest {

	private static final String MCP_URL = HOST_PATH + "/mcp";

	// Identities
	private static final AString ALICE = Strings.create("did:web:test.example.com:user:alice-sdk");
	private static final PeerAuth PEER_AUTH = new PeerAuth(KP);
	private static final String ALICE_JWT = PEER_AUTH.issuePeerToken(ALICE, 3600).toString();

	private McpSyncClient mcpNoAuth;
	private McpSyncClient mcpAlice;

	@BeforeAll
	public void setupClients() {
		// Client without auth
		McpClientTransport noAuthTransport = HttpClientStreamableHttpTransport.builder(MCP_URL)
				.build();
		mcpNoAuth = McpClient.sync(noAuthTransport)
				.requestTimeout(Duration.ofSeconds(10))
				.build();
		mcpNoAuth.initialize();

		// Client with Alice's bearer token
		McpClientTransport aliceTransport = HttpClientStreamableHttpTransport.builder(MCP_URL)
				.customizeRequest(b -> b.header("Authorization", "Bearer " + ALICE_JWT))
				.build();
		mcpAlice = McpClient.sync(aliceTransport)
				.requestTimeout(Duration.ofSeconds(10))
				.build();
		mcpAlice.initialize();
	}

	// ===== Protocol-level tests =====

	@Test
	public void testPing() {
		mcpNoAuth.ping();
		mcpAlice.ping();
	}

	@Test
	public void testListToolsContainsSigningTools() {
		ListToolsResult lr = mcpAlice.listTools();
		List<Tool> tools = lr.tools();
		assertNotNull(tools);
		assertTrue(tools.size() > 0, "Should have tools");

		// Check that all signing tools are present
		String[] expectedTools = {
			"signingServiceInfo", "signingCreateKey", "signingListKeys",
			"signingSign", "signingGetJWT"
		};
		for (String expected : expectedTools) {
			boolean found = tools.stream().anyMatch(t -> expected.equals(t.name()));
			assertTrue(found, "Should find tool: " + expected);
		}
	}

	@Test
	public void testSigningToolsHaveSchemas() {
		ListToolsResult lr = mcpAlice.listTools();
		List<Tool> tools = lr.tools();

		for (Tool tool : tools) {
			if (tool.name().startsWith("signing")) {
				assertNotNull(tool.description(), tool.name() + " should have description");
				assertNotNull(tool.inputSchema(), tool.name() + " should have input schema");
			}
		}
	}

	// ===== signingServiceInfo via SDK =====

	@Test
	public void testServiceInfoViaSDK() {
		CallToolResult result = mcpNoAuth.callTool(CallToolRequest.builder()
				.name("signingServiceInfo")
				.arguments(Map.of())
				.build());

		assertNotNull(result);
		Map<String, Object> structured = result.structuredContent();
		assertNotNull(structured, "Should have structured content");
		assertEquals(true, structured.get("available"));
	}

	// ===== Create key and list keys via SDK =====

	@Test
	public void testCreateKeyAndListKeysViaSDK() {
		// Create a key
		CallToolResult createResult = mcpAlice.callTool(CallToolRequest.builder()
				.name("signingCreateKey")
				.arguments(Map.of("passphrase", "sdk-test-pass"))
				.build());

		assertNotNull(createResult);
		assertFalse(createResult.isError() != null && createResult.isError(), "createKey should not be error");
		Map<String, Object> createData = createResult.structuredContent();
		assertNotNull(createData);
		String publicKey = (String) createData.get("publicKey");
		assertNotNull(publicKey, "Should return publicKey");
		assertTrue(publicKey.startsWith("0x"), "Public key should be hex");

		// List keys — should include the created key
		CallToolResult listResult = mcpAlice.callTool(CallToolRequest.builder()
				.name("signingListKeys")
				.arguments(Map.of())
				.build());

		assertNotNull(listResult);
		assertFalse(listResult.isError() != null && listResult.isError(), "listKeys should not be error");
		Map<String, Object> listData = listResult.structuredContent();
		assertNotNull(listData);

		@SuppressWarnings("unchecked")
		List<String> keys = (List<String>) listData.get("keys");
		assertNotNull(keys, "Should return keys list");
		assertTrue(keys.contains(publicKey), "Listed keys should include the created key");
	}

	// ===== Sign and verify via SDK =====

	@Test
	public void testSignAndVerifyViaSDK() {
		// Create a key
		CallToolResult createResult = mcpAlice.callTool(CallToolRequest.builder()
				.name("signingCreateKey")
				.arguments(Map.of("passphrase", "sdk-sign-pass"))
				.build());
		String publicKey = (String) createResult.structuredContent().get("publicKey");

		// Sign some data
		CallToolResult signResult = mcpAlice.callTool(CallToolRequest.builder()
				.name("signingSign")
				.arguments(Map.of(
					"publicKey", publicKey,
					"passphrase", "sdk-sign-pass",
					"value", "68656c6c6f"
				))
				.build());

		assertNotNull(signResult);
		assertFalse(signResult.isError() != null && signResult.isError(), "sign should not be error");
		Map<String, Object> signData = signResult.structuredContent();
		String signature = (String) signData.get("signature");
		assertNotNull(signature, "Should return signature");
		assertEquals(publicKey, signData.get("publicKey"), "Should echo back publicKey");
	}

	// ===== JWT via SDK =====

	@Test
	public void testGetJWTViaSDK() {
		// Create a key
		CallToolResult createResult = mcpAlice.callTool(CallToolRequest.builder()
				.name("signingCreateKey")
				.arguments(Map.of("passphrase", "sdk-jwt-pass"))
				.build());
		String publicKey = (String) createResult.structuredContent().get("publicKey");

		// Get JWT
		CallToolResult jwtResult = mcpAlice.callTool(CallToolRequest.builder()
				.name("signingGetJWT")
				.arguments(Map.of(
					"publicKey", publicKey,
					"passphrase", "sdk-jwt-pass",
					"audience", "https://sdk-test.example.com",
					"lifetime", 300
				))
				.build());

		assertNotNull(jwtResult);
		assertFalse(jwtResult.isError() != null && jwtResult.isError(), "getJWT should not be error");
		Map<String, Object> jwtData = jwtResult.structuredContent();
		String jwt = (String) jwtData.get("jwt");
		assertNotNull(jwt, "Should return JWT");

		// Verify the JWT using Convex JWT utilities
		AccountKey pk = AccountKey.parse(publicKey);
		AMap<AString, ACell> claims = JWT.verifyPublic(Strings.create(jwt), pk);
		assertNotNull(claims, "JWT should verify with signing key");

		// Check audience claim
		AString aud = RT.ensureString(claims.get(Strings.create("aud")));
		assertEquals("https://sdk-test.example.com", aud.toString());
	}

	// ===== Auth enforcement via SDK =====

	@Test
	public void testCreateKeyNoAuthViaSDK() {
		CallToolResult result = mcpNoAuth.callTool(CallToolRequest.builder()
				.name("signingCreateKey")
				.arguments(Map.of("passphrase", "no-auth-pass"))
				.build());

		assertNotNull(result);
		assertTrue(result.isError() != null && result.isError(),
				"createKey without auth should return error");
	}

	@Test
	public void testListKeysNoAuthViaSDK() {
		CallToolResult result = mcpNoAuth.callTool(CallToolRequest.builder()
				.name("signingListKeys")
				.arguments(Map.of())
				.build());

		assertNotNull(result);
		assertTrue(result.isError() != null && result.isError(),
				"listKeys without auth should return error");
	}

	@Test
	public void testSignNoAuthViaSDK() {
		CallToolResult result = mcpNoAuth.callTool(CallToolRequest.builder()
				.name("signingSign")
				.arguments(Map.of(
					"publicKey", "0x0000000000000000000000000000000000000000000000000000000000000000",
					"passphrase", "test",
					"value", "68656c6c6f"
				))
				.build());

		assertNotNull(result);
		assertTrue(result.isError() != null && result.isError(),
				"sign without auth should return error");
	}
}
