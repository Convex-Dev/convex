package convex.restapi.mcp;

import java.io.IOException;
import java.io.PrintWriter;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import io.javalin.http.Context;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared MCP JSON-RPC protocol utilities for building standards-compliant
 * MCP servers on top of Javalin.
 *
 * <p>Contains protocol helpers for JSON-RPC responses, MCP tool results,
 * SSE event formatting, and common field constants. Used by both the Convex
 * MCP server and the Covia MCP server.</p>
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a>
 * @see <a href="https://modelcontextprotocol.io/specification/2025-06-18">MCP Specification</a>
 */
public class McpProtocol {

	// ===== JSON-RPC field constants =====

	public static final StringShort FIELD_ID = Strings.intern("id");
	public static final StringShort FIELD_METHOD = Strings.intern("method");
	public static final StringShort FIELD_PARAMS = Strings.intern("params");
	public static final StringShort FIELD_RESULT = Strings.intern("result");
	public static final StringShort FIELD_ERROR = Strings.intern("error");
	public static final StringShort FIELD_CODE = Strings.intern("code");
	public static final StringShort FIELD_MESSAGE = Strings.intern("message");

	// ===== MCP tool result field constants =====

	public static final StringShort FIELD_NAME = Strings.intern("name");
	public static final StringShort FIELD_ARGUMENTS = Strings.intern("arguments");
	public static final StringShort FIELD_CONTENT = Strings.intern("content");
	public static final StringShort FIELD_STRUCTURED_CONTENT = Strings.intern("structuredContent");
	public static final StringShort FIELD_TYPE = Strings.intern("type");
	public static final StringShort FIELD_TEXT = Strings.intern("text");
	public static final StringShort FIELD_IS_ERROR = Strings.intern("isError");

	// ===== MCP session header =====

	public static final String HEADER_SESSION_ID = "Mcp-Session-Id";

	// ===== SSE keep-alive interval =====

	public static final long SSE_KEEPALIVE_MS = 30000;

	// ===== Base response =====

	public static final AHashMap<AString, ACell> BASE_RESPONSE = Maps.of("jsonrpc", "2.0");
	public static final AMap<AString, ACell> EMPTY_MAP = Maps.empty();

	// ===== JSON-RPC response builders =====

	/**
	 * Construct a successful JSON-RPC result response.
	 * @param result Result value (the "result" field)
	 * @return JSON-RPC response map
	 */
	public static AMap<AString, ACell> protocolResult(AMap<? extends ACell, ? extends ACell> result) {
		return BASE_RESPONSE.assoc(FIELD_RESULT, result);
	}

	/**
	 * Construct a JSON-RPC error response.
	 * @param code JSON-RPC error code (e.g. -32600, -32601, -32602)
	 * @param message Error message
	 * @return JSON-RPC error response map
	 */
	public static AMap<AString, ACell> protocolError(int code, String message) {
		AMap<AString, ACell> error = Maps.of(
			FIELD_CODE, CVMLong.create(code),
			FIELD_MESSAGE, message
		);
		return BASE_RESPONSE.assoc(FIELD_ERROR, error);
	}

	/**
	 * Attach an id field to a JSON-RPC response if non-null.
	 * @param response The response map
	 * @param id The id value, or null
	 * @return Response with id attached, or unchanged if id is null
	 */
	public static AMap<AString, ACell> maybeAttachId(AMap<AString, ACell> response, ACell id) {
		if (id == null) return response;
		return response.assoc(FIELD_ID, id);
	}

	// ===== MCP tool result builders =====

	/**
	 * Build a standard MCP tool result with text content and structured content.
	 * @param structured The structured content map
	 * @param isError Whether this is an error result
	 * @return MCP result map with content, structuredContent, and isError fields
	 */
	public static AMap<AString, ACell> buildMcpResult(AMap<AString, ACell> structured, boolean isError) {
		AString jsonText = JSON.print(structured);
		AMap<AString, ACell> textContent = Maps.of(
			FIELD_TYPE, "text",
			FIELD_TEXT, jsonText
		);
		AVector<AMap<AString, ACell>> content = Vectors.of(textContent);
		return Maps.of(
			FIELD_CONTENT, content,
			FIELD_STRUCTURED_CONTENT, structured,
			FIELD_IS_ERROR, isError ? CVMBool.TRUE : CVMBool.FALSE
		);
	}

	/**
	 * Create a successful tool result wrapped in a JSON-RPC response.
	 * @param structuredResult The structured result (must be a map or null)
	 * @return JSON-RPC response with MCP tool result
	 */
	public static AMap<AString, ACell> toolSuccess(ACell structuredResult) {
		AMap<AString, ACell> payload = RT.ensureMap(structuredResult);
		if (payload == null) payload = EMPTY_MAP;
		return protocolResult(buildMcpResult(payload, false));
	}

	/**
	 * Create an error tool result wrapped in a JSON-RPC response.
	 *
	 * <p>Returns a successful JSON-RPC response containing an MCP tool result with
	 * {@code isError: true}. This is distinct from a JSON-RPC error (code -32602 etc.):
	 * the response is protocol-valid, but the tool signals failure in its result payload.</p>
	 *
	 * <p>Per MCP spec 2025-11-25: tool input validation errors (missing/invalid arguments)
	 * SHOULD be returned as tool results with {@code isError: true} rather than as JSON-RPC
	 * errors. This enables LLM clients to see the error message and self-correct, rather
	 * than having the SDK throw an exception that hides the details.</p>
	 *
	 * @param message Error message describing the validation failure
	 * @return JSON-RPC response with MCP tool error result
	 */
	public static AMap<AString, ACell> toolError(String message) {
		AMap<AString, ACell> payload = Maps.of(
			"message", message
		);
		return protocolResult(buildMcpResult(payload, true));
	}

	// ===== JSON-RPC request inspection =====

	/**
	 * Check if a JSON-RPC message is a notification (no "id" field).
	 * @param request The request map
	 * @return true if this is a notification
	 */
	public static boolean isNotification(AMap<?, ?> request) {
		return !request.containsKey(FIELD_ID);
	}

	/**
	 * Extract the method name from a JSON-RPC request.
	 * @param request The request map
	 * @return Method name string, or null if missing
	 */
	public static String getMethodName(AMap<?, ?> request) {
		AString methodCell = RT.ensureString(request.get(FIELD_METHOD));
		return methodCell != null ? methodCell.toString().trim() : null;
	}

	// ===== SSE helpers =====

	/**
	 * Check if the client exclusively wants SSE (text/event-stream) responses.
	 * When the client accepts both JSON and SSE, prefer JSON since tool calls
	 * return synchronously. SSE on POST is reserved for streaming scenarios.
	 * @param ctx Javalin context
	 * @return true if SSE is the preferred response format
	 */
	public static boolean acceptsEventStream(Context ctx) {
		String accept = ctx.header("Accept");
		if (accept == null) return false;
		if (accept.contains("application/json")) return false;
		return accept.contains("text/event-stream");
	}

	/**
	 * Format a single data value as an SSE event string.
	 * @param data The data to format as JSON
	 * @return SSE event string: "event: message\ndata: JSON\n\n"
	 */
	public static String formatSseEvent(ACell data) {
		StringBuilder sb = new StringBuilder();
		appendSseEvent(sb, data);
		return sb.toString();
	}

	/**
	 * Append an SSE event to a StringBuilder.
	 * @param sb StringBuilder to append to
	 * @param data The data to format as JSON
	 */
	public static void appendSseEvent(StringBuilder sb, ACell data) {
		sb.append("event: message\n");
		sb.append("data: ");
		sb.append(JSON.print(data).toString());
		sb.append("\n\n");
	}

	/**
	 * Write SSE content directly to the servlet response, bypassing Javalin's
	 * result mechanism to ensure Content-Type: text/event-stream is preserved.
	 * Commits the response via flushBuffer() so Javalin cannot override it.
	 * @param ctx Javalin context
	 * @param sseBody The SSE event body string
	 * @throws IOException if writing fails
	 */
	public static void writeSseToServletResponse(Context ctx, String sseBody) throws IOException {
		HttpServletResponse res = ctx.res();
		res.setStatus(200);
		res.setContentType("text/event-stream");
		res.setCharacterEncoding("UTF-8");
		res.setHeader("Cache-Control", "no-cache");
		res.setHeader("X-Accel-Buffering", "no");
		PrintWriter writer = res.getWriter();
		writer.write(sseBody);
		writer.flush();
		res.flushBuffer();
	}

	/**
	 * Send a JSON-RPC response as either SSE or JSON.
	 * @param ctx Javalin context
	 * @param response The response to send
	 * @param useSSE true to send as SSE, false for plain JSON
	 */
	public static void sendResponse(Context ctx, ACell response, boolean useSSE) {
		if (useSSE) {
			try {
				writeSseToServletResponse(ctx, formatSseEvent(response));
			} catch (IOException e) {
				// Client disconnected — nothing to do
			}
		} else {
			ctx.contentType("application/json");
			ctx.result(JSON.print(response).toString());
		}
	}

	/**
	 * Send batch responses as individual SSE events on one stream.
	 * @param ctx Javalin context
	 * @param responses Vector of response maps
	 */
	public static void sendSseBatchResponse(Context ctx, AVector<AMap<AString, ACell>> responses) {
		try {
			StringBuilder sb = new StringBuilder();
			long n = responses.count();
			for (long i = 0; i < n; i++) {
				appendSseEvent(sb, responses.get(i));
			}
			writeSseToServletResponse(ctx, sb.toString());
		} catch (IOException e) {
			// Client disconnected
		}
	}
}
