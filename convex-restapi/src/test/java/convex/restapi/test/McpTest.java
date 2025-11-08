package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;

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

public class McpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";

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

		AMap<?, ?> responseMap = (AMap<?, ?>) parsed;
		assertEquals(Strings.create("init-1"), responseMap.get(McpAPI.FIELD_ID));

		ACell resultCell = responseMap.get(McpAPI.FIELD_RESULT);
		assertNotNull(resultCell, "initialize should return result");
		assertTrue(resultCell instanceof AMap);

		AMap<?, ?> result = (AMap<?, ?>) resultCell;
		AVector<?> tools = (AVector<?>) result.get(Strings.create("tools"));
		assertNotNull(tools, "initialize should include tools");
		assertTrue(tools.count() >= 5);
	}

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

		AMap<?, ?> responseMap = (AMap<?, ?>) parsed;
		assertEquals(Strings.create("bad-1"), responseMap.get(McpAPI.FIELD_ID));

		ACell errorCell = responseMap.get(McpAPI.FIELD_ERROR);
		assertNotNull(errorCell, "Unknown method should return error object");
		assertTrue(errorCell instanceof AMap);

		AMap<?, ?> error = (AMap<?, ?>) errorCell;
		ACell codeCell = error.get(Strings.create("code"));
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	@Test
	public void testToolCallQuery() throws IOException, InterruptedException {
		AMap<?, ?> responseMap = makeToolCall("query", "{ \"source\": \"*balance*\" }");
		Map<String, Object> valueMap = expectResult(responseMap);
		assertTrue(valueMap.containsKey("value"));
	}

	@Test
	public void testToolCallUnknownTool() throws IOException, InterruptedException {
		AMap<?, ?> responseMap = makeToolCall("unknown-tool", "{}");
		assertEquals(Strings.create("test-unknown-tool"), responseMap.get(McpAPI.FIELD_ID));
		ACell errorCell = responseMap.get(McpAPI.FIELD_ERROR);
		assertNotNull(errorCell, "Unknown tool should return a JSON-RPC error");
		assertTrue(errorCell instanceof AMap);

		AMap<?, ?> error = (AMap<?, ?>) errorCell;
		ACell codeCell = error.get(Strings.create("code"));
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	@Test
	public void testSignWithSeed() throws IOException, InterruptedException {
		String seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
		String arguments = "{ \"value\": \"hello\", \"seed\": \"" + seedHex + "\" }";

		AMap<?, ?> responseMap = makeToolCall("sign", arguments);
		Map<String, Object> valueMap = expectResult(responseMap);

		Blob seedBlob = Blob.fromHex(seedHex);
		AKeyPair keyPair = AKeyPair.create(seedBlob);
		SignedData<AString> expected = keyPair.signData(Strings.create("hello"));

		assertEquals(expected.getSignature().toHexString(), valueMap.get("signature"));
		assertEquals(keyPair.getAccountKey().toHexString(), valueMap.get("accountKey"));
		assertEquals("hello", valueMap.get("value"));
	}

	@Test
	public void testSignMissingSeed() throws IOException, InterruptedException {
		AMap<?, ?> responseMap = makeToolCall("sign", "{ \"value\": \"hello\" }");
		expectError(responseMap);
	}

	@Test
	public void testSignMissingValue() throws IOException, InterruptedException {
		String seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
		AMap<?, ?> responseMap = makeToolCall("sign", "{ \"seed\": \"" + seedHex + "\" }");
		expectError(responseMap);
	}

	private AMap<?, ?> makeToolCall(String toolName, String argumentsJson) throws IOException, InterruptedException {
		String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
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
		return (AMap<?, ?>) parsed;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> expectResult(AMap<?, ?> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		AMap<?, ?> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result, "RPC result missing");
		assertEquals(CVMBool.FALSE, result.get(McpAPI.FIELD_IS_ERROR));

		AVector<?> content = (AVector<?>) result.get(McpAPI.FIELD_CONTENT);
		assertNotNull(content);
		AMap<?, ?> textEntry = (AMap<?, ?>) content.get(0);
		return RT.jvm(JSON.parse(textEntry.get(McpAPI.FIELD_TEXT).toString()));
	}

	private void expectError(AMap<?, ?> responseMap) {
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		AMap<?, ?> result = RT.ensureMap(responseMap.get(McpAPI.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpAPI.FIELD_IS_ERROR));
	}
}
