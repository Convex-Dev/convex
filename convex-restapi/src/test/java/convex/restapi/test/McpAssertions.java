package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.restapi.mcp.McpProtocol;

/** Assertions for structured MCP tool results, including the JSON-RPC envelope. */
final class McpAssertions {
	private McpAssertions() {}

	static AMap<AString, ACell> expectResult(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpProtocol.FIELD_ERROR), () -> "Unexpected protocol error: " + responseMap);
		AMap<AString, ACell> result = RT.castMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result, () -> "RPC result missing in: " + responseMap);
		assertEquals(CVMBool.FALSE, result.get(McpProtocol.FIELD_IS_ERROR), () -> "Unexpected failure: " + responseMap);
		AMap<AString, ACell> structured = RT.castMap(result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}

	static AMap<AString, ACell> expectError(AMap<AString, ACell> responseMap) {
		assertNull(responseMap.get(McpProtocol.FIELD_ERROR), () -> "Unexpected protocol error: " + responseMap);
		AMap<AString, ACell> result = RT.castMap(responseMap.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result);
		assertEquals(CVMBool.TRUE, result.get(McpProtocol.FIELD_IS_ERROR));
		AMap<AString, ACell> structured = RT.castMap(result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertNotNull(structured);
		return structured;
	}
}
