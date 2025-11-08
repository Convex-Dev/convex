package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

public class McpTest extends ARESTTest {

	private static final String MCP_PATH = HOST_PATH + "/mcp";

	@Test
	public void testInitialize() throws IOException, InterruptedException {
		String request = """
			{
				"jsonrpc": "2.0",
				"method": "initialize",
				"params": {},
				"id": "init-1"
			}
			""";

		HttpResponse<String> response = post(MCP_PATH, request);
		assertEquals(200, response.statusCode());

		ACell parsed = JSON.parse(response.body());
		assertTrue(parsed instanceof AMap, "Expected map response but got " + RT.getType(parsed));

		AMap<?, ?> responseMap = (AMap<?, ?>) parsed;
		ACell resultCell = responseMap.get(Strings.create("result"));
		assertNotNull(resultCell, "initialize should return result");
		assertTrue(resultCell instanceof AMap);

		AMap<?, ?> result = (AMap<?, ?>) resultCell;
		assertNotNull(result.get(Strings.create("protocolVersion")), "protocolVersion missing");
		assertEquals(Strings.create("init-1"), responseMap.get(Strings.create("id")));
	}

	@Test
	public void testUnknownMethod() throws IOException, InterruptedException {
		String request = """
			{
				"jsonrpc": "2.0",
				"method": "does/not/exist",
				"params": {},
				"id": "bad-1"
			}
			""";

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
}

