package convex.restapi.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.prim.CVMBool;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.restapi.RESTServer;
import convex.restapi.mcp.McpTool;
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

	public static final StringShort FIELD_ID = Strings.intern("id");
	public static final StringShort FIELD_METHOD = Strings.intern("method");
	public static final StringShort FIELD_PARAMS = Strings.intern("params");
	public static final StringShort FIELD_NAME = Strings.intern("name");
	public static final StringShort FIELD_ARGUMENTS = Strings.intern("arguments");
	public static final StringShort FIELD_RESULT = Strings.intern("result");
	public static final AString FIELD_ERROR = Strings.create("error");
	public static final StringShort FIELD_CODE = Strings.intern("code");
	public static final StringShort FIELD_MESSAGE = Strings.intern("message");
	public static final StringShort FIELD_CONTENT = Strings.intern("content");
	public static final StringShort FIELD_STRUCTURED_CONTENT = Strings.intern("structured_content");
	public static final StringShort FIELD_TYPE = Strings.intern("type");
	public static final StringShort FIELD_TEXT = Strings.intern("text");
	public static final StringShort FIELD_IS_ERROR = Strings.intern("isError");

	public static final StringShort ARG_SOURCE = Strings.intern("source");
	public static final StringShort ARG_ADDRESS = Strings.intern("address");
	public static final StringShort ARG_VALUE = Strings.intern("value");
	public static final StringShort ARG_ALGORITHM = Strings.intern("algorithm");
	public static final AString ARG_SEED = Strings.create("seed");

	private static final AHashMap<AString, ACell> BASE_RESPONSE = Maps.of("jsonrpc", "2.0");
	private static final AMap<AString, ACell> EMPTY_MAP = Maps.empty();
	private final AMap<AString, ACell> serverInfo;
	private final Map<String, McpTool> tools = new LinkedHashMap<>();

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
		registerTools();
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
				case "tools/call" -> result = toolCall(request.get(FIELD_PARAMS));
				default -> result = protocolError(-32601, "Method not found: " + method);
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			result = protocolError(-32603, "Interrupted");
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
			Strings.create("tools"), listToolsVector()
		);
		return result;
	}

	private AVector<AMap<AString, ACell>> listToolsVector() {
		AVector<AMap<AString, ACell>> vec = Vectors.empty();
		for (McpTool tool : tools.values()) {
			vec = vec.conj(tool.getMetadata());
		}
		return vec;
	}

	private AMap<AString, ACell> listTools() {
		return Maps.of(Strings.create("tools"), listToolsVector());
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

	private AMap<AString, ACell> toolCall(ACell paramsCell) throws InterruptedException {
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

		AMap<?, ?> arguments = null;
		ACell argumentsCell = params.get(FIELD_ARGUMENTS);
		if (argumentsCell instanceof AMap<?, ?> argsMap) {
			arguments = argsMap;
		}

		return tool.handle(arguments);
	}

	private AMap<AString, ACell> toolResult(Result result) {
		AMap<AString, ACell> structured = EMPTY_MAP;
		ACell value = result.getValue();
		if (value != null) {
			structured = structured.assoc(Strings.create("value"), value);
		}
		ACell errorCode = result.getErrorCode();
		if (errorCode != null) {
			structured = structured.assoc(Strings.create("errorCode"), errorCode);
		}
		ACell info = result.getInfo();
		if (info != null) {
			structured = structured.assoc(Strings.create("info"), info);
		}
		String message = result.isError()
			? "Error: " + (errorCode == null ? "unknown" : errorCode.toString())
			: "Value: " + (value == null ? "nil" : value.toString());
		return protocolResult(buildToolPayload(message, structured, result.isError()));
	}

	private AMap<AString, ACell> toolSuccess(String message, ACell structured) {
		return protocolResult(buildToolPayload(message, structured, false));
	}

	private AMap<AString, ACell> toolError(String message) {
		return protocolResult(buildToolPayload(message, EMPTY_MAP, true));
	}

	private AMap<AString, ACell> buildToolPayload(String message, ACell structured, boolean isError) {
		AMap<AString, ACell> content = Maps.of(
			FIELD_TYPE, Strings.create("text"),
			FIELD_TEXT, Strings.create(message)
		);
		AMap<AString, ACell> result = Maps.of(
			FIELD_CONTENT, Vectors.of(content)
		);
		if (structured != null && structured != EMPTY_MAP) {
			result = result.assoc(FIELD_STRUCTURED_CONTENT, structured);
		}
		if (isError) {
			result = result.assoc(FIELD_IS_ERROR, CVMBool.TRUE);
		}
		return result;
	}

	private Address parseAddress(ACell cell) {
		if (cell == null) return null;
		return Address.parse((Object) cell);
	}

	private void registerTools() {
		registerTool(new QueryTool());
		registerTool(new TransactTool());
		registerTool(new HashTool());
		registerTool(new SignTool());
		registerTool(new PeerStatusTool());
	}

	private void registerTool(McpTool tool) {
		tools.put(tool.getName(), tool);
	}

	private class QueryTool extends McpTool {
		QueryTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/query.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<?, ?> arguments) throws InterruptedException {
			if (arguments == null) {
				return protocolError(-32602, "Query requires arguments");
			}
			AString sourceCell = RT.ensureString(arguments.get(ARG_SOURCE));
			if (sourceCell == null) {
				return protocolError(-32602, "Query requires 'source' string");
			}
			String source = sourceCell.toString();
			try {
				ACell form;
				try {
					form = Reader.read(source);
				} catch (Exception e) {
					return toolError("Failed to parse query source: " + e.getMessage());
				}
				Address address = parseAddress(arguments.get(ARG_ADDRESS));
				Convex convex = restServer.getConvex();
				Result result = convex.querySync(form, address);
				return toolResult(result);
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				return toolError("Query execution failed: " + e.getMessage());
			}
		}
	}

	private class TransactTool extends McpTool {
		TransactTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/transact.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<?, ?> arguments) throws InterruptedException {
			if (arguments == null) {
				return protocolError(-32602, "Transact requires arguments");
			}
			AString sourceCell = RT.ensureString(arguments.get(ARG_SOURCE));
			if (sourceCell == null) {
				return protocolError(-32602, "Transact requires 'source' string");
			}
			String source = sourceCell.toString();
			try {
				Result result = restServer.getConvex().transactSync(source);
				return toolResult(result);
			} catch (InterruptedException e) {
				throw e;
			} catch (Exception e) {
				return toolError("Transaction failed: " + e.getMessage());
			}
		}
	}

	private class HashTool extends McpTool {
		HashTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/hash.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<?, ?> arguments) {
			if (arguments == null) {
				return protocolError(-32602, "Hash tool requires arguments");
			}
			AString valueCell = RT.ensureString(arguments.get(ARG_VALUE));
			if (valueCell == null) {
				return protocolError(-32602, "Hash tool requires 'value' string");
			}
			String value = valueCell.toString();
			AString algorithmCell = RT.ensureString(arguments.get(ARG_ALGORITHM));

			String algorithm = (algorithmCell == null) ? "sha256" : algorithmCell.toString().toLowerCase();
			Hash hash = switch (algorithm) {
				case "sha3" -> Hashing.sha3(value);
				case "sha256" -> Hashing.sha256(value);
				default -> null;
			};
			if (hash == null) {
				return toolError("Unsupported hash algorithm: " + algorithm);
			}
			String hashHex = hash.toHexString();
			AMap<AString, ACell> structured = Maps.of(
				Strings.create("algorithm"), Strings.create(algorithm),
				Strings.create("hash"), Strings.create(hashHex)
			);
			return toolSuccess("Hash (" + algorithm + "): " + hashHex, structured);
		}
	}

	private class SignTool extends McpTool {
		SignTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/sign.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<?, ?> arguments) {
			if (arguments == null) {
				return protocolError(-32602, "Sign tool requires arguments");
			}
			AString valueCell = RT.ensureString(arguments.get(ARG_VALUE));
			if (valueCell == null) {
				return toolError("Sign tool requires a 'value' hex string");
			}
			AString seedCell = RT.ensureString(arguments.get(ARG_SEED));
			if (seedCell == null) {
				return toolError("Sign tool requires Ed25519 'seed' string");
			}
			String seedHex = seedCell.toString();
			Blob seedBlob = Blob.parse(seedHex);
			if ((seedBlob == null) || (seedBlob.count() != AKeyPair.SEED_LENGTH)) {
				return toolError("Seed must be 32-byte hex string");
			}
			try {
				AKeyPair keyPair = AKeyPair.create(seedBlob);
				SignedData<AString> signed = keyPair.signData(valueCell);
				AccountKey accountKey = keyPair.getAccountKey();
				AMap<AString, ACell> structured = Maps.of(
					Strings.create("value"), valueCell,
					Strings.create("signature"), Strings.create(signed.getSignature().toHexString()),
					Strings.create("accountKey"), Strings.create(accountKey.toHexString())
				);
				return toolSuccess("Signed value with account " + accountKey.toHexString(), structured);
			} catch (Exception e) {
				return toolError("Signing failed: " + e.getMessage());
			}
		}
	}

	private class PeerStatusTool extends McpTool {
		PeerStatusTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/peerStatus.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<?, ?> arguments) {
			try {
				AMap<?, ?> status = server.getStatusMap();
				return toolSuccess("Peer status retrieved", status);
			} catch (Exception e) {
				return toolError("Failed to load peer status: " + e.getMessage());
			}
		}
	}
}