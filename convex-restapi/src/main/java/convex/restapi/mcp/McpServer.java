package convex.restapi.mcp;

import static convex.restapi.mcp.McpProtocol.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Standalone MCP (Model Context Protocol) server with pluggable tool and prompt
 * registries. Any module can register tools by calling {@link #registerTool}.
 *
 * <p>Handles the generic MCP protocol: JSON-RPC dispatch for {@code initialize},
 * {@code ping}, {@code tools/list}, {@code tools/call}, {@code prompts/list},
 * {@code prompts/get}, plus origin validation and {@code .well-known/mcp}.</p>
 *
 * <p>Does not depend on any Convex peer infrastructure. SSE session handling
 * and transport-specific extensions (e.g. state watches) are left to the caller
 * — typically by registering additional routes on the same Javalin app.</p>
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a>
 * @see <a href="https://modelcontextprotocol.io/specification/2025-06-18">MCP Specification</a>
 */
public class McpServer {

	private static final Logger log = LoggerFactory.getLogger(McpServer.class);

	/** Maximum entries in a batch JSON-RPC request. */
	public static final int MAX_BATCH_SIZE = 20;

	/** ThreadLocal to make the current Javalin Context available to tool handlers */
	static final ThreadLocal<Context> currentContext = new ThreadLocal<>();

	private AMap<AString, ACell> serverInfo;
	private final Map<String, McpTool> tools = new LinkedHashMap<>();
	private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();

	/** The route path for the MCP endpoint (default "/mcp") */
	private final String routePath;

	/**
	 * Creates a new McpServer with the given server info and default route "/mcp".
	 * @param serverInfo Server info map returned in initialize responses
	 */
	public McpServer(AMap<AString, ACell> serverInfo) {
		this(serverInfo, "/mcp");
	}

	/**
	 * Creates a new McpServer with the given server info and route path.
	 * @param serverInfo Server info map returned in initialize responses
	 * @param routePath The route path for the MCP endpoint (e.g. "/mcp")
	 */
	public McpServer(AMap<AString, ACell> serverInfo, String routePath) {
		this.serverInfo = serverInfo;
		this.routePath = routePath;
	}

	// ==================== Tool & Prompt Registration ====================

	/**
	 * Registers a tool. Tools are listed in tools/list and dispatched by tools/call.
	 * @param tool The tool to register
	 */
	public void registerTool(McpTool tool) {
		tools.put(tool.getName(), tool);
	}

	/**
	 * Registers a prompt. Prompts are listed in prompts/list and fetched by prompts/get.
	 * @param prompt The prompt to register
	 */
	public void registerPrompt(McpPrompt prompt) {
		prompts.put(prompt.getName(), prompt);
	}

	/**
	 * Gets a registered tool by name, or null if not found.
	 */
	public McpTool getTool(String name) {
		return tools.get(name);
	}

	/**
	 * Gets the tools as a vector of metadata maps (for tools/list).
	 */
	public AVector<AMap<AString, ACell>> getToolMetadata() {
		AVector<AMap<AString, ACell>> vec = Vectors.empty();
		for (McpTool tool : tools.values()) {
			vec = vec.conj(tool.getMetadata());
		}
		return vec;
	}

	/**
	 * Gets the server info map.
	 */
	public AMap<AString, ACell> getServerInfo() {
		return serverInfo;
	}

	/**
	 * Updates the server info map.
	 */
	public void setServerInfo(AMap<AString, ACell> serverInfo) {
		this.serverInfo = serverInfo;
	}

	/**
	 * Gets the Javalin context for the current request (available during tool handling).
	 */
	public static Context getCurrentContext() {
		return currentContext.get();
	}

	// ==================== Route Registration ====================

	/**
	 * Registers MCP routes on the given Javalin app.
	 * Registers POST and .well-known only. SSE (GET/DELETE) should be added
	 * by the caller if needed.
	 */
	public void addRoutes(Javalin app) {
		app.before(routePath, this::validateOrigin);
		app.post(routePath, this::handlePost);
		app.get("/.well-known/mcp", this::handleWellKnown);
	}

	// ==================== POST /mcp — JSON-RPC dispatch ====================

	protected void handlePost(Context ctx) {
		currentContext.set(ctx);
		try {
			boolean useSSE = acceptsEventStream(ctx);
			ACell body = JSONReader.read(ctx.bodyInputStream());

			if (body instanceof AMap<?, ?> map) {
				if (isNotification(map)) {
					processNotification(map);
					ctx.status(202).contentType(ContentTypes.JSON);
					return;
				}

				AMap<AString, ACell> response = createResponse(map);
				sendResponse(ctx, response, useSSE);
			} else if (body instanceof AVector<?> vector) {
				long n = vector.count();
				if (n == 0) {
					sendResponse(ctx, protocolError(-32600, "Invalid batch request (empty)"), useSSE);
					return;
				}
				if (n > MAX_BATCH_SIZE) {
					sendResponse(ctx, protocolError(-32600, "Batch too large (max " + MAX_BATCH_SIZE + ")"), useSSE);
					return;
				}
				AVector<AMap<AString, ACell>> responses = Vectors.empty();
				for (long i = 0; i < n; i++) {
					ACell entry = vector.get(i);
					if (entry instanceof AMap<?, ?> batchMap) {
						if (isNotification(batchMap)) {
							processNotification(batchMap);
						} else {
							responses = responses.conj(createResponse(batchMap));
						}
					} else {
						responses = responses.conj(protocolError(-32600, "Invalid Request"));
					}
				}
				if (responses.isEmpty()) {
					ctx.status(202).contentType(ContentTypes.JSON);
				} else if (useSSE) {
					sendSseBatchResponse(ctx, responses);
				} else {
					ctx.contentType(ContentTypes.JSON);
					ctx.result(JSON.print(responses).toString());
				}
			} else {
				sendResponse(ctx, protocolError(-32600, "Request must be a JSON object or array"), useSSE);
			}
		} catch (ParseException | IOException e) {
			ctx.contentType(ContentTypes.JSON);
			ctx.result(JSON.print(protocolError(-32700, "Parse error")).toString());
		} catch (Exception e) {
			log.warn("Unexpected error handling MCP request", e);
			ctx.contentType(ContentTypes.JSON);
			ctx.result(JSON.print(protocolError(-32603, "Internal error")).toString());
		} finally {
			currentContext.remove();
		}
	}

	// ==================== JSON-RPC dispatch ====================

	protected AMap<AString, ACell> createResponse(AMap<?, ?> request) {
		ACell idCell = request.get(FIELD_ID);
		String method = getMethodName(request);

		if (method == null) {
			return maybeAttachId(protocolError(-32600, "Missing method"), idCell);
		}

		AMap<AString, ACell> result;
		try {
			result = switch (method) {
				case "initialize" -> {
					Context reqCtx = currentContext.get();
					if (reqCtx != null) {
						reqCtx.res().setHeader(HEADER_SESSION_ID, UUID.randomUUID().toString());
					}
					yield protocolResult(buildInitializeResult(request.get(FIELD_PARAMS)));
				}
				case "ping" -> protocolResult(EMPTY_MAP);
				case "notifications/initialized" -> protocolResult(EMPTY_MAP);
				case "tools/list" -> protocolResult(listTools());
				case "tools/call" -> toolCall(request.get(FIELD_PARAMS));
				case "prompts/list" -> protocolResult(listPrompts());
				case "prompts/get" -> promptGet(request.get(FIELD_PARAMS));
				default -> protocolError(-32601, "Method not found: " + method);
			};
		} catch (Exception ex) {
			log.warn("Error handling MCP request for method {}", method, ex);
			result = protocolError(-32603, "Internal error");
		}

		return maybeAttachId(result, idCell);
	}

	/**
	 * Builds the initialize result. Override to customise capabilities or
	 * protocol version negotiation.
	 */
	protected AMap<AString, ACell> buildInitializeResult(ACell params) {
		AMap<AString, ACell> capabilities = Maps.of(
			"tools", EMPTY_MAP
		);
		if (!prompts.isEmpty()) {
			capabilities = capabilities.assoc(Strings.create("prompts"), EMPTY_MAP);
		}
		return Maps.of(
			"protocolVersion", "2025-06-18",
			"serverInfo", serverInfo,
			"capabilities", capabilities
		);
	}

	/**
	 * Lists tools. Override to provide dynamic tool discovery (e.g. from adapters).
	 * Default implementation returns the static tool registry.
	 */
	protected AMap<AString, ACell> listTools() {
		return Maps.of("tools", getToolMetadata());
	}

	/**
	 * Handles a tools/call request. Override to provide custom dispatch
	 * (e.g. job-based execution with timeouts).
	 *
	 * <p>Called on a virtual thread — implementations may block.</p>
	 *
	 * @param paramsCell The JSON-RPC params (contains "name" and "arguments")
	 * @return JSON-RPC result (use {@link McpProtocol#toolSuccess} / {@link McpProtocol#toolError})
	 */
	protected AMap<AString, ACell> toolCall(ACell paramsCell) {
		if (!(paramsCell instanceof AMap<?, ?> params)) {
			return protocolError(-32602, "params must be an object");
		}

		AString toolNameCell = RT.ensureString(params.get(FIELD_NAME));
		if (toolNameCell == null) {
			return protocolError(-32602, "Tool name required");
		}
		String toolName = toolNameCell.toString();
		McpTool tool = tools.get(toolName);
		if (tool == null) {
			return protocolError(-32601, "Unknown tool: " + toolName);
		}

		AMap<AString, ACell> arguments = RT.ensureMap(params.get(FIELD_ARGUMENTS));
		if (arguments == null) {
			return protocolError(-32602, toolName + " requires arguments");
		}

		return tool.handle(arguments);
	}

	private AMap<AString, ACell> listPrompts() {
		AVector<AMap<AString, ACell>> vec = Vectors.empty();
		for (McpPrompt prompt : prompts.values()) {
			vec = vec.conj(prompt.getMetadata());
		}
		return Maps.of("prompts", vec);
	}

	private AMap<AString, ACell> promptGet(ACell paramsCell) {
		if (!(paramsCell instanceof AMap<?, ?> params)) {
			return protocolError(-32602, "params must be an object");
		}

		AString nameCell = RT.ensureString(params.get(FIELD_NAME));
		if (nameCell == null) return protocolError(-32602, "Prompt name required");

		McpPrompt prompt = prompts.get(nameCell.toString());
		if (prompt == null) return protocolError(-32601, "Unknown prompt: " + nameCell);

		AMap<AString, ACell> arguments = RT.ensureMap(params.get(FIELD_ARGUMENTS));
		if (arguments == null) arguments = Maps.empty();

		AVector<AMap<AString, ACell>> messages = prompt.render(arguments);
		return protocolResult(Maps.of(
			"description", prompt.getMetadata().get(Strings.create("description")),
			"messages", messages
		));
	}

	// ==================== Notifications ====================

	private void processNotification(AMap<?, ?> request) {
		String method = getMethodName(request);
		if (method == null) return;
		switch (method) {
			case "notifications/initialized", "notifications/cancelled" -> { /* acknowledged */ }
			default -> log.debug("Unrecognised MCP notification: {}", method);
		}
	}

	// ==================== Origin validation ====================

	private void validateOrigin(Context ctx) {
		String origin = ctx.header("Origin");
		if (origin != null && !isOriginAllowed(origin)) {
			throw new io.javalin.http.ForbiddenResponse("Forbidden: invalid origin");
		}
	}

	/**
	 * Check if an Origin is allowed for MCP requests. Override to restrict.
	 * Default: all origins allowed.
	 */
	protected boolean isOriginAllowed(String origin) {
		return true;
	}

	// ==================== .well-known/mcp ====================

	void handleWellKnown(Context ctx) {
		String baseUrl = getExternalBaseUrl(ctx);
		AMap<AString, ACell> result = Maps.of(
			"mcp_version", "1.0",
			"server_url", baseUrl,
			"description", serverInfo.get(Strings.create("title")),
			"endpoint", Maps.of("path", routePath, "transport", "streamable-http")
		);
		ctx.contentType(ContentTypes.JSON);
		ctx.result(JSON.print(result).toString());
	}

	private String getExternalBaseUrl(Context ctx) {
		String proto = ctx.header("X-Forwarded-Proto");
		if (proto == null) proto = ctx.scheme();
		String host = ctx.header("X-Forwarded-Host");
		if (host == null) host = ctx.host();
		return proto + "://" + host + routePath;
	}

	// ==================== Response helpers ====================

	private void sendResponse(Context ctx, ACell response, boolean useSSE) {
		if (useSSE) {
			McpProtocol.sendResponse(ctx, response, true);
		} else {
			ctx.contentType(ContentTypes.JSON);
			ctx.result(JSON.print(response).toString());
		}
	}
}
