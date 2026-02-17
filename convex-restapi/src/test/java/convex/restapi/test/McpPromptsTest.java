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
import convex.restapi.mcp.McpProtocol;

/**
 * Tests for MCP Prompts: prompts/list, prompts/get,
 * conditional registration, message quality, and error handling.
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

	/** Extract the messages vector from a prompts/get result */
	private AVector<ACell> getMessages(AMap<AString, ACell> response) {
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, "Should have result");
		AVector<ACell> messages = RT.ensureVector(result.get(Strings.create("messages")));
		assertNotNull(messages, "Should have messages");
		return messages;
	}

	/** Extract text content from a message at given index */
	private String getMessageText(AVector<ACell> messages, int index) {
		AMap<AString, ACell> msg = RT.ensureMap(messages.get(index));
		AMap<AString, ACell> content = RT.ensureMap(msg.get(Strings.create("content")));
		return RT.ensureString(content.get(Strings.create("text"))).toString();
	}

	/** Get the role of a message at given index */
	private String getMessageRole(AVector<ACell> messages, int index) {
		AMap<AString, ACell> msg = RT.ensureMap(messages.get(index));
		return RT.ensureString(msg.get(Strings.create("role"))).toString();
	}

	/** Concatenate all message texts for content assertions */
	private String getAllText(AVector<ACell> messages) {
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < messages.count(); i++) {
			sb.append(getMessageText(messages, (int)i)).append('\n');
		}
		return sb.toString();
	}

	// ===== prompts/list =====

	@Test
	public void testPromptsListReturnsPrompts() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, "Should have result");

		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));
		assertNotNull(prompts, "Should have prompts array");
		assertTrue(prompts.count() >= 3, "Should have at least 3 prompts, got " + prompts.count());
	}

	@Test
	public void testPromptsMetadataHasRequiredFields() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		for (long i = 0; i < prompts.count(); i++) {
			AMap<AString, ACell> prompt = RT.ensureMap(prompts.get(i));
			assertNotNull(RT.ensureString(prompt.get(Strings.create("name"))),
				"Prompt " + i + " should have name");
			assertNotNull(RT.ensureString(prompt.get(Strings.create("description"))),
				"Prompt " + i + " should have description");
			assertNotNull(prompt.get(Strings.create("arguments")),
				"Prompt " + i + " should have arguments");
		}
	}

	@Test
	public void testPromptsHaveTitles() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		for (long i = 0; i < prompts.count(); i++) {
			AMap<AString, ACell> prompt = RT.ensureMap(prompts.get(i));
			AString title = RT.ensureString(prompt.get(Strings.create("title")));
			assertNotNull(title, "Prompt '" + prompt.get(Strings.create("name")) + "' should have a title");
		}
	}

	@Test
	public void testListExcludesMessages() throws IOException, InterruptedException {
		// prompts/list should NOT include message templates (those are only in prompts/get)
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		for (long i = 0; i < prompts.count(); i++) {
			AMap<AString, ACell> prompt = RT.ensureMap(prompts.get(i));
			assertNull(prompt.get(Strings.create("messages")),
				"prompts/list should not include messages array");
		}
	}

	@Test
	public void testAlwaysAvailablePrompts() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));

		boolean hasExploreAccount = false, hasNetworkStatus = false, hasConvexGuide = false;
		for (long i = 0; i < prompts.count(); i++) {
			String name = RT.ensureString(RT.ensureMap(prompts.get(i)).get(Strings.create("name"))).toString();
			if ("explore-account".equals(name)) hasExploreAccount = true;
			if ("network-status".equals(name)) hasNetworkStatus = true;
			if ("convex-guide".equals(name)) hasConvexGuide = true;
		}
		assertTrue(hasExploreAccount, "explore-account should always be available");
		assertTrue(hasNetworkStatus, "network-status should always be available");
		assertTrue(hasConvexGuide, "convex-guide should always be available");
	}

	@Test
	public void testSigningPromptsPresent() throws IOException, InterruptedException {
		assertNotNull(server.getSigningService(), "Test server should have signing service");

		AMap<AString, ACell> response = mcpCall("prompts/list", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		AVector<ACell> prompts = RT.ensureVector(result.get(Strings.create("prompts")));
		assertEquals(6, prompts.count(), "Should have exactly 6 prompts");
	}

	// ===== prompts/get — message structure =====

	@Test
	public void testGetExploreAccount() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"explore-account\",\"arguments\":{\"address\":\"#42\"}}");

		assertNotNull(RT.ensureString(
			RT.ensureMap(response.get(McpProtocol.FIELD_RESULT)).get(Strings.create("description"))));

		AVector<ACell> messages = getMessages(response);
		assertTrue(messages.count() >= 3, "Should have persona + request + assistant messages");

		// Persona message teaches about Convex accounts
		String persona = getMessageText(messages, 0);
		assertTrue(persona.contains("Actor"), "Persona should explain account types");
		assertTrue(persona.contains("#9"), "Persona should reference system accounts");
		assertTrue(persona.contains("describeAccount"), "Persona should list available tools");

		// Request message substitutes the address argument
		String allText = getAllText(messages);
		assertTrue(allText.contains("#42"), "Should substitute address argument");

		// Last message is assistant prefill
		int last = (int)(messages.count() - 1);
		assertEquals("assistant", getMessageRole(messages, last));
		assertTrue(getMessageText(messages, last).contains("#42"), "Prefill should reference address");
	}

	@Test
	public void testGetNetworkStatus() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"network-status\"}");

		AVector<ACell> messages = getMessages(response);
		assertTrue(messages.count() >= 3);

		String persona = getMessageText(messages, 0);
		assertTrue(persona.contains("peerStatus"), "Should list peerStatus tool");
		assertTrue(persona.contains("Convergent Proof of Stake"), "Should explain CPoS consensus");
		assertTrue(persona.contains("#7"), "Should explain memory exchange");
	}

	@Test
	public void testGetConvexGuide() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"actors\"}}");

		AVector<ACell> messages = getMessages(response);
		assertTrue(messages.count() >= 3);

		// Persona teaches Convex Lisp
		String persona = getMessageText(messages, 0);
		assertTrue(persona.contains("immutable"), "Should teach immutable data");
		assertTrue(persona.contains("deploy"), "Should teach deployment");
		assertTrue(persona.contains("docs.convex.world"), "Should include doc references");

		// Request substitutes topic
		String allText = getAllText(messages);
		assertTrue(allText.contains("actors"), "Should substitute topic argument");

		// Assistant prefill
		int last = (int)(messages.count() - 1);
		assertEquals("assistant", getMessageRole(messages, last));
		assertTrue(getMessageText(messages, last).contains("actors"));
	}

	@Test
	public void testGetConvexGuideDocReferences() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"data types\"}}");

		String persona = getMessageText(getMessages(response), 0);
		assertTrue(persona.contains("docs.convex.world/docs/cad/lisp"), "Should reference Lisp spec");
		assertTrue(persona.contains("docs.convex.world/docs/cad/accounts"), "Should reference accounts docs");
		assertTrue(persona.contains("convex.world/sandbox"), "Should reference sandbox");
	}

	@Test
	public void testGetCreateAccount() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"create-account\",\"arguments\":{\"passphrase\":\"secret123\"}}");

		AVector<ACell> messages = getMessages(response);
		assertTrue(messages.count() >= 3);

		String persona = getMessageText(messages, 0);
		assertTrue(persona.contains("signingCreateAccount"), "Should reference tool");
		assertTrue(persona.contains("Ed25519"), "Should explain key type");
		// Passphrase value should NOT appear in persona text
		assertFalse(persona.contains("secret123"), "Should NOT echo passphrase in persona");
	}

	@Test
	public void testGetDeployContract() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"deploy-contract\",\"arguments\":{\"source\":\"(do (defn greet [x] (str \\\"Hello \\\" x)) (export greet))\",\"address\":\"#42\",\"passphrase\":\"mypass\"}}");

		AVector<ACell> messages = getMessages(response);
		assertTrue(messages.count() >= 3);

		String persona = getMessageText(messages, 0);
		assertTrue(persona.contains("deploy"), "Persona should explain deployment");
		assertTrue(persona.contains("export"), "Persona should explain exports");
		assertTrue(persona.contains("signingTransact"), "Should list tools");

		String allText = getAllText(messages);
		assertTrue(allText.contains("#42"), "Should substitute address");
		assertTrue(allText.contains("greet"), "Should include the source code");
	}

	@Test
	public void testGetTransferFunds() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"transfer-funds\",\"arguments\":{\"from\":\"#42\",\"to\":\"#13\",\"amount\":\"5000\",\"passphrase\":\"pass\"}}");

		AVector<ACell> messages = getMessages(response);
		assertTrue(messages.count() >= 3);

		String allText = getAllText(messages);
		assertTrue(allText.contains("#42"), "Should substitute sender");
		assertTrue(allText.contains("#13"), "Should substitute recipient");
		assertTrue(allText.contains("5000"), "Should substitute amount");
		assertTrue(allText.contains("juice"), "Should mention transaction costs");
	}

	// ===== Message quality =====

	@Test
	public void testAllPromptsHavePersonaAndPrefill() throws IOException, InterruptedException {
		String[][] testCases = {
			{"explore-account", "{\"name\":\"explore-account\",\"arguments\":{\"address\":\"#11\"}}"},
			{"network-status",  "{\"name\":\"network-status\"}"},
			{"convex-guide",    "{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"data types\"}}"},
		};

		for (String[] tc : testCases) {
			AMap<AString, ACell> response = mcpCall("prompts/get", tc[1]);
			AVector<ACell> messages = getMessages(response);
			assertTrue(messages.count() >= 3, tc[0] + " should have >= 3 messages (persona + request + assistant)");
			assertEquals("user", getMessageRole(messages, 0), tc[0] + " first message should be user (persona)");
			int last = (int)(messages.count() - 1);
			assertEquals("assistant", getMessageRole(messages, last), tc[0] + " last message should be assistant");
		}
	}

	@Test
	public void testPersonaTeachesConvex() throws IOException, InterruptedException {
		// The persona message should teach the LLM about Convex, not just say "use tool X"
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"actors\"}}");
		String persona = getMessageText(getMessages(response), 0);
		assertTrue(persona.length() > 500, "Persona should be substantial (got " + persona.length() + " chars)");
		assertTrue(persona.contains("Convex"), "Should teach about Convex");
		assertTrue(persona.contains("CVM"), "Should mention the CVM");
	}

	@Test
	public void testAllPromptsHaveDocLinks() throws IOException, InterruptedException {
		// Every prompt persona should include at least one link to Convex documentation
		String[][] testCases = {
			{"explore-account", "{\"name\":\"explore-account\",\"arguments\":{\"address\":\"#11\"}}"},
			{"network-status",  "{\"name\":\"network-status\"}"},
			{"convex-guide",    "{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"data types\"}}"},
			{"create-account",  "{\"name\":\"create-account\",\"arguments\":{\"passphrase\":\"test\"}}"},
			{"deploy-contract", "{\"name\":\"deploy-contract\",\"arguments\":{\"source\":\"(do nil)\",\"address\":\"#1\",\"passphrase\":\"test\"}}"},
			{"transfer-funds",  "{\"name\":\"transfer-funds\",\"arguments\":{\"from\":\"#1\",\"to\":\"#2\",\"amount\":\"100\",\"passphrase\":\"test\"}}"},
		};

		for (String[] tc : testCases) {
			AMap<AString, ACell> response = mcpCall("prompts/get", tc[1]);
			String allText = getAllText(getMessages(response));
			assertTrue(allText.contains("docs.convex.world") || allText.contains("convex.world"),
				tc[0] + " should include at least one Convex documentation link");
		}
	}

	@Test
	public void testToolsDescribedNotAssumed() throws IOException, InterruptedException {
		// Prompts should describe available tools, not assume the LLM has them
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"convex-guide\",\"arguments\":{\"topic\":\"data types\"}}");
		String persona = getMessageText(getMessages(response), 0);
		assertTrue(persona.contains("check which you have access to") || persona.contains("provides these tools"),
			"Should frame tools as available rather than assumed");
	}

	// ===== Error cases =====

	@Test
	public void testGetUnknownPrompt() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get",
			"{\"name\":\"nonexistent-prompt\"}");
		AMap<AString, ACell> error = RT.ensureMap(response.get(McpProtocol.FIELD_ERROR));
		assertNotNull(error, "Should return error for unknown prompt");
		assertEquals(-32601L, RT.ensureLong(error.get(McpProtocol.FIELD_CODE)).longValue());
	}

	@Test
	public void testGetMissingName() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("prompts/get", "{}");
		AMap<AString, ACell> error = RT.ensureMap(response.get(McpProtocol.FIELD_ERROR));
		assertNotNull(error, "Should return error for missing name");
		assertEquals(-32602L, RT.ensureLong(error.get(McpProtocol.FIELD_CODE)).longValue());
	}

	// ===== Initialize capability =====

	@Test
	public void testInitializeIncludesPromptsCapability() throws IOException, InterruptedException {
		AMap<AString, ACell> response = mcpCall("initialize", null);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result);

		AMap<AString, ACell> capabilities = RT.ensureMap(result.get(Strings.create("capabilities")));
		assertNotNull(capabilities, "Should have capabilities");
		assertTrue(capabilities.containsKey(Strings.create("prompts")));
		assertTrue(capabilities.containsKey(Strings.create("tools")));
	}
}
