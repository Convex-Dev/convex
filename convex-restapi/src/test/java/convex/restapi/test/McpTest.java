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
		assertEquals(Strings.create("init-1"), responseMap.get(Strings.create("id")));

		ACell resultCell = responseMap.get(Strings.create("result"));
		assertNotNull(resultCell, "initialize should return result");
		assertTrue(resultCell instanceof AMap);

		AMap<?, ?> result = (AMap<?, ?>) resultCell;
		assertNotNull(result.get(Strings.create("protocolVersion")), "protocolVersion missing");

		ACell toolsCell = result.get(Strings.create("tools"));
		assertNotNull(toolsCell, "initialize should include tools metadata");
		assertTrue(toolsCell instanceof AVector, "tools should be a vector");
		assertTrue(((AVector<?>) toolsCell).count() >= 5, "expected default tools to be advertised");
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
		assertEquals(Strings.create("bad-1"), responseMap.get(Strings.create("id")));

		ACell errorCell = responseMap.get(Strings.create("error"));
		assertNotNull(errorCell, "Unknown method should return error object");
		assertTrue(errorCell instanceof AMap);

		AMap<?, ?> error = (AMap<?, ?>) errorCell;
		ACell codeCell = error.get(Strings.create("code"));
		assertEquals(CVMLong.create(-32601), codeCell);
	}

	@Test
	public void testToolCallQuery() throws IOException, InterruptedException {
		AMap<?, ?> responseMap = makeToolCall("query", "{ \"source\": \"*balance*\" }");
		assertEquals(Strings.create("test-query"), responseMap.get(Strings.create("id")));

		ACell resultCell = responseMap.get(Strings.create("result"));
		assertNotNull(resultCell);
		assertTrue(resultCell instanceof AMap);

		AMap<?, ?> result = (AMap<?, ?>) resultCell;
		assertNull(result.get(Strings.create("isError")), "Successful query should not set isError");

		ACell structuredCell = result.get(Strings.create("structured_content"));
		assertNotNull(structuredCell, "Query response should include structured content");
		assertTrue(structuredCell instanceof AMap);

		AMap<?, ?> structured = (AMap<?, ?>) structuredCell;
		assertNotNull(structured.get(Strings.create("value")), "Structured content should include value");
	}

	@Test
	public void testToolCallUnknownTool() throws IOException, InterruptedException {
		AMap<?, ?> responseMap = makeToolCall("unknown-tool", "{}");
		assertEquals(Strings.create("test-unknown-tool"), responseMap.get(Strings.create("id")));
		ACell errorCell = responseMap.get(Strings.create("error"));
		assertNotNull(errorCell, "Unknown tool should return a JSON-RPC error");
		assertTrue(errorCell instanceof AMap);

		AMap<?, ?> error = (AMap<?, ?>) errorCell;
		assertEquals(CVMLong.create(-32601), error.get(Strings.create("code")));
	}

	@Test
	public void testSignWithSeed() throws IOException, InterruptedException {
		String seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
		String arguments = "{ \"value\": \"hello\", \"seed\": \"" + seedHex + "\" }";

		AMap<?, ?> responseMap = makeToolCall("sign", arguments);
		assertEquals(Strings.create("test-sign"), responseMap.get(Strings.create("id")));
		assertNull(responseMap.get(Strings.create("error")));

		ACell resultCell = responseMap.get(Strings.create("result"));
		assertNotNull(resultCell);
		assertTrue(resultCell instanceof AMap);
		AMap<?, ?> result = (AMap<?, ?>) resultCell;
		assertNull(result.get(Strings.create("isError")));

		ACell structuredCell = result.get(Strings.create("structured_content"));
		assertNotNull(structuredCell);
		assertTrue(structuredCell instanceof AMap);
		AMap<?, ?> structured = (AMap<?, ?>) structuredCell;

		Blob seedBlob = Blob.fromHex(seedHex);
		AKeyPair keyPair = AKeyPair.create(seedBlob);
		SignedData<AString> expected = keyPair.signData(Strings.create("hello"));

		assertEquals(Strings.create(expected.getSignature().toHexString()),
			structured.get(Strings.create("signature")));
		assertEquals(Strings.create(keyPair.getAccountKey().toHexString()),
			structured.get(Strings.create("accountKey")));
	}
	
	@Test
	public void testSignMissingSeed() throws IOException, InterruptedException {
		String arguments = "{ \"value\": \"hello\" }";
		AMap<?, ?> responseMap = makeToolCall("sign", arguments);
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		
		AMap<?, ?> result = (AMap<?, ?>) responseMap.get(Strings.create("result"));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE,RT.getIn(responseMap, "result", "isError"));
		
		AVector<?> content = (AVector<?>) result.get(Strings.create("content"));
		assertNotNull(content);
		assertTrue(content.count() > 0);
		String msg = content.get(0).toString();
		assertTrue(msg.contains("seed"), "Expected message to mention missing seed but got: " + msg);
	}
	
	@Test
	public void testSignMissingValue() throws IOException, InterruptedException {
		String seedHex = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
		String arguments = "{ \"seed\": \"" + seedHex + "\" }";
		AMap<?, ?> responseMap = makeToolCall("sign", arguments);
		assertNull(responseMap.get(McpAPI.FIELD_ERROR));
		
		AMap<?, ?> result = (AMap<?, ?>) responseMap.get(Strings.create("result"));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE,RT.getIn(responseMap, "result", "isError"));
		
		AVector<?> content = (AVector<?>) result.get(Strings.create("content"));
		assertNotNull(content);
		assertTrue(content.count() > 0);
		String msg = content.get(0).toString();
		assertTrue(msg.contains("value"), "Expected message to mention missing value but got: " + msg);
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
}
