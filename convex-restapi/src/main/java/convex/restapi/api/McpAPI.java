package convex.restapi.api;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.restapi.RESTServer;
import convex.restapi.model.JsonRPCRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

/**
 * Minimal MCP JSON-RPC endpoint that follows the core patterns from the Covia Venue
 * implementation, adapted for the Convex REST server.
 */
public class McpAPI extends ABaseAPI {

	private static final Logger log = LoggerFactory.getLogger(McpAPI.class);

	private static final AString FIELD_ID = Strings.create("id");
	private static final AString FIELD_METHOD = Strings.create("method");
	private static final AString FIELD_RESULT = Strings.create("result");
	private static final AString FIELD_ERROR = Strings.create("error");
	private static final AString FIELD_CODE = Strings.create("code");
	private static final AString FIELD_MESSAGE = Strings.create("message");
	private static final AHashMap<AString, ACell> BASE_RESPONSE = Maps.of("jsonrpc", "2.0");
	private static final AVector<?> EMPTY_VECTOR = Vectors.empty();
	private static final AMap<AString, ACell> EMPTY_MAP = Maps.empty();

	private final AMap<AString, ACell> serverInfo;

	public McpAPI(RESTServer restServer) {
		super(restServer);
		AMap<AString, ACell> info = Maps.of(
			Strings.create("name"), Strings.create("convex-mcp"),
			Strings.create("title"), Strings.create("Convex MCP"),
			Strings.create("version"), Strings.create(Utils.getVersion())
		);
		Peer peer = server.getPeer();
		if (peer != null) {
			info = info.assoc(Strings.create("networkId"), Strings.create(peer.getNetworkID().toHexString()));
			info = info.assoc(Strings.create("peerKey"), Strings.create(peer.getPeerKey().toHexString()));
		}
		serverInfo = info;
	}

	@Override
	public void addRoutes(Javalin app) {
		app.post("/mcp", this::handleMcpRequest);
	}

	@OpenApi(path = "/mcp", 
			methods = HttpMethod.POST, 
			versions="peer-v1",
			tags = { "MCP"},
			summary = "Handle MCP JSON-RPC requests", 
			requestBody = @OpenApiRequestBody(
					description = "JSON-RPC request",
					content= @OpenApiContent(
							type = "application/json" ,
							from = JsonRPCRequest.class,
							exampleObjects = {
								@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
								@OpenApiExampleProperty(name = "method", value = "initialize"),
								@OpenApiExampleProperty(name = "params", value="{}"),
								@OpenApiExampleProperty(name = "id", value = "1")
							}
					)),
			operationId = "mcpServer",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "JSON-RPC response", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = Object.class,
										exampleObjects = {
											@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
											@OpenApiExampleProperty(name = "result", value="{}"),
											@OpenApiExampleProperty(name = "id", value = "1")
										}
										) })
					})	
	private void handleMcpRequest(Context ctx) {
		ctx.contentType(ContentTypes.JSON);
		try {
			ACell body = JSONReader.read(ctx.bodyInputStream());
			if (body instanceof AMap<?, ?> map) {
				AMap<AString, ACell> response = createResponse(map);
				setContent(ctx, response);
			} else if (body instanceof AVector<?> vector) {
				AVector<AMap<AString, ACell>> responses = Vectors.empty();
				long n = vector.count();
				for (long i = 0; i < n; i++) {
					ACell entry = vector.get(i);
					if (entry instanceof AMap<?, ?> batchMap) {
						responses = responses.conj(createResponse(batchMap));
					} else {
						responses = responses.conj(protocolError(-32600, "Batch entries must be JSON objects"));
					}
				}
				setContent(ctx, responses);
			} else {
				setContent(ctx, protocolError(-32600, "Request must be a JSON object or array"));
			}
		} catch (ParseException | IOException e) {
			setContent(ctx, protocolError(-32700, "Parse error"));
		}
	}

	private AMap<AString, ACell> createResponse(AMap<?, ?> request) {
		ACell idCell = request.get(FIELD_ID);
		AString methodCell = RT.ensureString(request.get(FIELD_METHOD));

		if (methodCell == null) {
			return maybeAttachId(protocolError(-32600, "Missing method"), idCell);
		}

		String method = methodCell.toString().trim();

		AMap<AString, ACell> result;
		try {
			switch (method) {
				case "initialize" -> result = protocolResult(buildInitializeResult());
				case "ping" -> result = protocolResult(EMPTY_MAP);
				case "notifications/initialized" -> result = protocolResult(EMPTY_MAP);
				case "tools/list" -> result = protocolResult(listTools());
				case "tools/call" -> result = protocolError(-32601, "No tools available");
				default -> result = protocolError(-32601, "Method not found: " + method);
			}
		} catch (Exception ex) {
			log.warn("Error handling MCP request for method {}", method, ex);
			result = protocolError(-32603, "Internal error");
		}

		return maybeAttachId(result, idCell);
	}

	private AMap<AString, ACell> buildInitializeResult() {
		AMap<AString, ACell> capabilities = Maps.of(Strings.create("tools"), EMPTY_MAP);
		AMap<AString, ACell> result = Maps.of(
			Strings.create("protocolVersion"), Strings.create("1.0"),
			Strings.create("serverInfo"), serverInfo,
			Strings.create("capabilities"), capabilities,
			Strings.create("tools"), EMPTY_VECTOR
		);
		return result;
	}

	private AMap<AString, ACell> listTools() {
		return Maps.of(Strings.create("tools"), EMPTY_VECTOR);
	}

	private AMap<AString, ACell> protocolResult(AMap<AString, ACell> result) {
		return BASE_RESPONSE.assoc(FIELD_RESULT, result);
	}

	private AMap<AString, ACell> protocolError(int code, String message) {
		AMap<AString, ACell> error = Maps.of(
			FIELD_CODE, CVMLong.create(code),
			FIELD_MESSAGE, Strings.create(message)
		);
		return BASE_RESPONSE.assoc(FIELD_ERROR, error);
	}

	private AMap<AString, ACell> maybeAttachId(AMap<AString, ACell> response, ACell idCell) {
		if (idCell == null) return response;
		return response.assoc(FIELD_ID, idCell);
	}
}