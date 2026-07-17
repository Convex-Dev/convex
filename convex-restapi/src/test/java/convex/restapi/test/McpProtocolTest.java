package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.restapi.mcp.McpProtocol;

/** Unit tests for MCP tool result protocol helpers. */
public class McpProtocolTest {

	@Test
	public void testToolSuccessWithMapIncludesStructuredContent() {
		AMap<AString, ACell> payload = Maps.of("answer", CVMLong.create(42));
		AMap<AString, ACell> result = resultOf(payload);

		assertEquals(payload, result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertEquals(JSON.print(payload), textOf(result));
		assertEquals(CVMBool.FALSE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	@Test
	public void testToolSuccessWithNullRetainsEmptyStructuredContent() {
		AMap<AString, ACell> result = resultOf(null);

		assertEquals(McpProtocol.EMPTY_MAP, result.get(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertEquals(JSON.print(McpProtocol.EMPTY_MAP), textOf(result));
		assertEquals(CVMBool.FALSE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	@Test
	public void testToolSuccessWithStringPreservesTextWithoutStructuredContent() {
		assertTextOnlyResult(Strings.create("hello MCP"));
	}

	@Test
	public void testToolSuccessWithNumberPreservesTextWithoutStructuredContent() {
		assertTextOnlyResult(CVMLong.create(42));
	}

	@Test
	public void testToolSuccessWithVectorPreservesTextWithoutStructuredContent() {
		assertTextOnlyResult(Vectors.of(CVMLong.create(1), Strings.create("two")));
	}

	private static void assertTextOnlyResult(ACell value) {
		AMap<AString, ACell> result = resultOf(value);

		assertFalse(result.containsKey(McpProtocol.FIELD_STRUCTURED_CONTENT));
		assertEquals(JSON.print(value), textOf(result));
		assertEquals(CVMBool.FALSE, result.get(McpProtocol.FIELD_IS_ERROR));
	}

	private static AMap<AString, ACell> resultOf(ACell value) {
		AMap<AString, ACell> response = McpProtocol.toolSuccess(value);
		AMap<AString, ACell> result = RT.ensureMap(response.get(McpProtocol.FIELD_RESULT));
		assertNotNull(result);
		return result;
	}

	private static AString textOf(AMap<AString, ACell> result) {
		AVector<ACell> content = RT.ensureVector(result.get(McpProtocol.FIELD_CONTENT));
		assertNotNull(content);
		assertEquals(1, content.count());
		AMap<AString, ACell> textContent = RT.ensureMap(content.get(0));
		assertNotNull(textContent);
		assertEquals(Strings.create("text"), textContent.get(McpProtocol.FIELD_TYPE));
		AString text = RT.ensureString(textContent.get(McpProtocol.FIELD_TEXT));
		assertNotNull(text);
		return text;
	}
}