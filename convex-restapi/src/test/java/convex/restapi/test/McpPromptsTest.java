package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.mcp.McpAPI;

/**
 * Tests for MCP Prompts (Stage 15): prompts/list, prompts/get,
 * conditional registration, and error handling.
 */
public class McpPromptsTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";

	private AMap<AString, ACell> mcpCall(String method, String paramsJson) throws IOException, InterruptedException {
		String request = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\","
			+ "\"params\":" + (paramsJson != null ? paramsJson : "{}") + ","
			+ "\"id\":\"test-1\"}";
		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());
		return RT.ensureMap(JSON.parse(response.body()));
	}

	// ===== prompts/list =====

	@Test
	public void testPromptsListReturnsPrompts() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		assertNotNull(result, "Should have result");

		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));
		assertNotNull(prompts, "Should have prompts array");
		assertTrue(prompts.count() >= 3, "Should have at least 3 prompts (always-available), got " + prompts.count());
	}

	@Test
	public void testPromptsMetadataHasRequiredFields() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		for (long i = 0; i < prompts.count(); i++) {
			AMap<AString, ACell> prompt = RT.ensureMap(prompts.get(i));
			assertNotNull(prompt, "Prompt at index " + i + " should be a map");
			assertNotNull(RT.ensureString(prompt.get(Strings.create("name"))),
				"Prompt at index " + i + " should have name");
			assertNotNull(RT.ensureString(prompt.get(Strings.create("description"))),
				"Prompt at index " + i + " should have description");
			// arguments field should be present (may be empty vector)
			assertNotNull(prompt.get(Strings.create("arguments")),
				"Prompt at index " + i + " should have arguments");
		}
	}

	@Test
	public void testAlwaysAvailablePrompts() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		// Collect prompt names
		boolean hasExploreAccount = false;
		boolean hasNetworkStatus = false;
		boolean hasConvexGuide = false;
		for (long i = 0; i < prompts.count(); i++) {
			AMap<AString, ACell> prompt = RT.ensureMap(prompts.get(i));
			String name = RT.ensureString(prompt.get(Strings.create("name"))).toString();
			if ("explore-account".equals(name)) hasExploreAccount = true;
			if ("network-status".equals(name)) hasNetworkStatus = true;
			if ("convex-guide".equals(name)) hasConvexGuide = true;
		}
		assertTrue(hasExploreAccount, "explore-account prompt should always be available");
		assertTrue(hasNetworkStatus, "network-status prompt should always be available");
		assertTrue(hasConvexGuide, "convex-guide prompt should always be available");
	}

	@Test
	public void testSigningPromptsPresent() throws IOException, InterruptedException {
		// Test server has signing service available, so signing prompts should be registered
		assertNotNull(server.getSigningService(), "Test server should have signing service");

		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		boolean hasCreateAccount = false;
		boolean hasDeployContract = false;
		boolean hasTransferFunds = false;
		for (long i = 0; i < prompts.count(); i++) {
			AMap<AString, ACell> prompt = RT.ensureMap(prompts.get(i));
			String name = RT.ensureString(prompt.get(Strings.create("name"))).toString();
			if ("create-account".equals(name)) hasCreateAccount = true;
			if ("deploy-contract".equals(name)) hasDeployContract = true;
			if ("transfer-funds".equals(name)) hasTransferFunds = true;
		}
		assertTrue(hasCreateAccount, "create-account prompt should be present when signing service available");
		assertTrue(hasDeployContract, "deploy-contract prompt should be present when signing service available");
		assertTrue(hasTransferFunds, "transfer-funds prompt should be present when signing service available");

		// Total should be 6
		assertEquals(6, prompts.count(), "Should have exactly 6 prompts");
	}

	// ===== prompts/get =====

	@Test
	public void testGetExploreAccount() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"explore-account\",\"arguments\":{\"address\":\"#42\"}}");
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		assertNotNull(result, "Should have result");

		// Should have description
		assertNotNull(RT.ensureString(result.get(Strings.create("description"))));

		// Should have messages
		AVector<ACell> messages = RT.ensureVector(result.get(Strings.create("messages")));
		assertNotNull(messages, "Should have messages");
		assertEquals(1, messages.count(), "Should have one message");

		// Check message structure
		AMap<AString, ACell> msg = RT.ensureMap(messages.get(0));
		assertEquals(Strings.create("user"), msg.get(Strings.create("role")));

		AMap<AString, ACell> content = RT.ensureMap(msg.get(Strings.create("content")));
		assertNotNull(content);
		assertEquals(Strings.create("text"), content.get(Strings.create("type")));

		String text = RT.ensureString(content.get(Strings.create("text"))).toString();
		assertTrue(text.contains("#42"), "Message should reference the address argument");
		assertTrue(text.contains("describeAccount"), "Message should reference describeAccount tool");
	}

	@Test
	public void testGetNetworkStatus() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"network-status\"}");
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);

		AVector<ACell> messages = RT.ensureVector(result.get(Strings.create("messages")));
		assertNotNull(messages);
		assertEquals(1, messages.count());

		AMap<AString, ACell> msg = RT.ensureMap(messages.get(0));
		AMap<AString, ACell> content = RT.ensureMap(msg.get(Strings.create("content")));
		String text = RT.ensureString(content.get(Strings.create("text"))).toString();
		assertTrue(text.contains("peerStatus"), "Message should reference peerStatus tool");
	}

	@Test
	public void testGetConvexGuide() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"actors\"}}");
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);

		AVector<ACell> messages = RT.ensureVector(result.get(Strings.create("messages")));
		AMap<AString, ACell> msg = RT.ensureMap(messages.get(0));
		AMap<AString, ACell> content = RT.ensureMap(msg.get(Strings.create("content")));
		String text = RT.ensureString(content.get(Strings.create("text"))).toString();
		assertTrue(text.contains("actors"), "Message should reference the topic argument");
		assertTrue(text.contains("query"), "Message should reference the query tool");
	}

	@Test
	public void testGetCreateAccount() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"create-account\",\"arguments\":{\"passphrase\":\"secret123\"}}");
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);

		AVector<ACell> messages = RT.ensureVector(result.get(Strings.create("messages")));
		AMap<AString, ACell> msg = RT.ensureMap(messages.get(0));
		AMap<AString, ACell> content = RT.ensureMap(msg.get(Strings.create("content")));
		String text = RT.ensureString(content.get(Strings.create("text"))).toString();
		assertTrue(text.contains("secret123"), "Message should include passphrase");
		assertTrue(text.contains("signingCreateAccount"), "Message should reference signingCreateAccount tool");
	}

	// ===== Error cases =====

	@Test
	public void testGetUnknownPrompt() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"nonexistent-prompt\"}");
		AMap<AString, ACell> error = RT.ensureMap(response.get(McpAPI.FIELD_ERROR));
		assertNotNull(error, "Should return error for unknown prompt");
		assertEquals(-32601L, RT.ensureLong(error.get(McpAPI.FIELD_CODE)).longValue());
	}

	@Test
	public void testGetMissingName() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get", "{}");
		AMap<AString, ACell> error = RT.ensureMap(response.get(McpAPI.FIELD_ERROR));
		assertNotNull(error, "Should return error for missing name");
		assertEquals(-32602L, RT.ensureLong(error.get(McpAPI.FIELD_CODE)).longValue());
	}

	// ===== Initialize capability =====

	@Test
	public void testInitializeIncludesPromptsCapability() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("initialize", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);

		AMap<AString, ACell> capabilities = RT.ensureMap(result.get(Strings.create("capabilities")));
		assertNotNull(capabilities, "Should have capabilities");
		assertTrue(capabilities.containsKey(Strings.create("prompts")),
			"Capabilities should include 'prompts'");
		assertTrue(capabilities.containsKey(Strings.create("tools")),
			"Capabilities should still include 'tools'");
	}
}
