package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.crypto.AKeyPair;
import convex.core.data.SignedData;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.api.McpAPI;

/**
 * Integration tests for the MCP HTTP endpoint.
 *
 * <p>The tests here exercise the JSON-RPC interface exposed at {@code /mcp}.
 * Each scenario issues real HTTP requests against the embedded REST server and
 * inspects the JSON structure that comes back. The focus is on validating the
 * high-level contract for the minimal set of MCP methods and tools that we
 * support.</p>
 */
public class McpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";

	/**
	 * Happy-path sanity check that the MCP server exposes the required tool list.
	 * The initialize call is special because it bootstraps protocol features.
	 */
	@Test
	public void testInitialize() throws IOException, InterruptedException {
		String request = "{\n"
			+ "  \"jsonrpc\": \"2.0\",\n"
			+ "  \"method\": \"initialize\",\n"
			+ "  \"params\": {},\n"
			+ "  \"id\": \"init-1\"\n"
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));

		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		assertEquals(Strings.create("init-1"), responseMap.get(McpAPI.FIELD_ID));

		ACell resultCell = responseMap.get(McpAPI.FIELD_RESULT);
		assertNotNull(resultCell, "initialize should return result");
		assertTrue(resultCell instanceof AMap);

		AMap<AString, ACell> result = RT.ensureMap(resultCell);
		AVector<ACell> tools = RT.ensureVector(result.get(Strings.create("tools")));
		assertNotNull(tools, "initialize should include tools");
		assertTrue(tools.count() >= 5);
	}

	/**
	 * Unknown JSON-RPC methods must return the standard -32601 error response.
	 */
	@Test
	public void testUnknownMethod() throws IOException, InterruptedException {
		String request = "{\n"
			+ "  \"jsonrpc\": \"2.0\",\n"
			+ "  \"method\": \"does/not/exist\",\n"
			+ "  \"params\": {},\n"
			+ "  \"id\": \"bad-1\"\n"
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));

		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		assertEquals(Strings.create("bad-1"), responseMap.get(McpAPI.FIELD_ID));

		ACell errorCell = responseMap.get(McpAPI.FIELD_ERROR);
		assertNotNull(errorCell, "Unknown method should return error object");
		assertTrue(errorCell instanceof AMap);

		AMap<AString, ACell> error = RT.ensureMap(errorCell);
		ACell codeCell = error.get(Strings.create("code"));
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	/**
	 * Basic smoke test that the {@code query} tool executes a form and returns a
	 * structured result payload.
	 */
	@Test
	public void testToolCallQuery() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("query", "{ \"source\": \"*balance*\" }");
		AMap<AString, ACell> structured = expectResult(responseMap);
		assertNotNull(structured.get(Strings.create("value")));
	}

	/**
	 * Attempting to call an unregistered tool should surface a protocol error
	 * (same as unknown method) rather than a tool-level error payload.
	 */
	@Test
	public void testToolCallUnknownTool() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("unknown-tool", "{}");
		assertEquals(Strings.create("test-unknown-tool"), responseMap.get(McpAPI.FIELD_ID));
		ACell errorCell = responseMap.get(McpAPI.FIELD_ERROR);
		assertNotNull(errorCell, "Unknown tool should return a JSON-RPC error");
		assertTrue(errorCell instanceof AMap);

		AMap<AString, ACell> error = RT.ensureMap(errorCell);
		ACell codeCell = error.get(Strings.create("code"));
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	/**
	 * The sign tool should accept hex-encoded data, sign it with the caller-provided
	 * Ed25519 seed, and return signature and public key information.
	 */
	@Test
	public void testSignWithSeed() throws IOException, InterruptedException {
		String seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
		String valueHex = "68656c6c6f"; // "hello" in hex
		String arguments = "{ \"value\": \"" + valueHex + "\", \"seed\": \"" + seedHex + "\" }";

		AMap<AString, ACell> responseMap = makeToolCall("sign", arguments);
		AMap<AString, ACell> structured = expectResult(responseMap);

		Blob seedBlob = Blob.fromHex(seedHex);
		AKeyPair keyPair = AKeyPair.create(seedBlob);
		Blob payload = Blob.fromHex(valueHex);
		SignedData<Blob> expected = keyPair.signData(payload);

		String signature = toString(structured.get(Strings.create("signature")));
		String accountKey = toString(structured.get(Strings.create("accountKey")));
		String signedValue = toString(structured.get(Strings.create("value")));

		assertEquals(expected.getSignature().toHexString(), signature);
		assertEquals(keyPair.getAccountKey().toHexString(), accountKey);
		assertEquals(valueHex, signedValue);
	}

	/**
	 * Missing seed argument should produce a tool-level error with a helpful
	 * message, keeping the JSON-RPC envelope successful.
	 */
	@Test
	public void testSignMissingSeed() throws IOException, InterruptedException {
		AMap<AString, ACell> responseMap = makeToolCall("sign", "{ \"value\": \"68656c6c6f\" }");
		AMap<AString, ACell> structured = expectError(responseMap);
		String message = toString(structured.get(Strings.create("message")));
		assertNotNull(message);
		assertTrue(message.contains("seed"));
	}

	/**
	 * Missing payload should similarly surface as a tool-level error, ensuring
	 * clients know they need to provide the hex data to sign.
	 */
	@Test
	public void testSignMissingValue() throws IOException, InterruptedException {
		String seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
		AMap<AString, ACell> responseMap = makeToolCall("sign", "{ \"seed\": \"" + seedHex + "\" }");
		AMap<AString, ACell> structured = expectError(responseMap);
		String message = toString(structured.get(Strings.create("message")));
		assertNotNull(message);
		assertTrue(message.contains("value"));
	}

	/**
	 * Utility to issue an MCP tools/call request and get the parsed response as a
	 * Convex map.
	 */
	private AMap<AString, ACell> makeToolCall(String toolName, String argumentsJson) throws IOException, InterruptedException {
		String args;
		if (argumentsJson == null || argumentsJson.isBlank()) {
			args = "{}";
		} else {
			args = argumentsJson;
		}
		String id = "test-" + toolName;
		String request = "{\n"
			+ "  \"jsonrpc\": \"2.0\",\n"
			+ "  \"method\": \"tools/call\",\n"
			+ "  \"params\": {\n"
			+ "    \"name\": \"" + toolName + "\",\n"
			+ "    \"arguments\": " + args + "\n"
			+ "  },\n"
			+ "  \"id\": \"" + id + "\"\n"
			+ "}";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, ()->"Expected map response but got " + RT.getType(parsed));
		AMap<AString, ACell> responseMap = RT.ensureMap(parsed);
		return responseMap;
	}

	/**
	 * Common assertion path for successful tool calls. Ensures the result wrapper
	 * is present, marks {@code isError == false}, checks that a text payload was
	 * produced for backward compatibility, and returns the structured content map
	 * for further inspection.
	 */
	private AMap<AString, ACell> expectResult(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result, "RPC result missing");
		assertEquals(CVMBool.FALSE, result.get(McpAPI.FIELD_IS_ERROR));

		AVector<ACell> content = RT.ensureVector(result.get(McpAPI.FIELD_CONTENT));
		assertNotNull(content);
		assertTrue(content.count() > 0);
		AMap<AString, ACell> textEntry = RT.ensureMap(content.get(0));
		assertNotNull(textEntry.get(McpAPI.FIELD_TEXT));
		AMap<AString, ACell> structured =RT.ensureMap(result.get(McpAPI.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	/**
	 * Common assertion path for tool failures. Ensures the JSON-RPC call succeeded
	 * but the structured content indicates an error payload that tests can read.
	 */
	private AMap<AString, ACell> expectError(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		AMap<AString, ACell> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpAPI.FIELD_IS_ERROR));
		AMap<AString, ACell> structured = RT.ensureMap(result.get(McpAPI.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	/**
	 * Safely converts an {@link ACell} to a Java string if it represents a Convex
	 * string value.
	 */
	private String toString(ACell cell) {
		AString str = RT.ensureString(cell);
		return (str == null) ? null : str.toString();
	}
}
