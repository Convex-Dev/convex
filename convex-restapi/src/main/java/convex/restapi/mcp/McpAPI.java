package convex.restapi.mcp;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Hashing;
import convex.core.crypto.Ed25519Signature;
import convex.core.Coin;
import convex.core.crypto.Providers;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.MapEntry;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Symbol;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ParseException;
import convex.core.exceptions.ResultException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import convex.restapi.api.ChainAPI;
import convex.restapi.auth.AuthMiddleware;
import convex.restapi.model.JsonRPCRequest;
import convex.restapi.model.JsonRPCResponse;
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
 * 
 * See: 
 * https://www.jsonrpc.org/specification
 * https://modelcontextprotocol.io/specification/2025-06-18
 */
public class McpAPI extends ABaseAPI {

	private static final Logger log = LoggerFactory.getLogger(McpAPI.class);

	public static final StringShort FIELD_ID = Strings.intern("id");
	public static final StringShort FIELD_METHOD = Strings.intern("method");
	public static final StringShort FIELD_PARAMS = Strings.intern("params");
	public static final StringShort FIELD_NAME = Strings.intern("name");
	public static final StringShort FIELD_ARGUMENTS = Strings.intern("arguments");
	public static final StringShort FIELD_RESULT = Strings.intern("result");
	public static final StringShort FIELD_ERROR = Strings.intern("error");
	public static final StringShort FIELD_CODE = Strings.intern("code");
	public static final StringShort FIELD_MESSAGE = Strings.intern("message");
	public static final StringShort FIELD_CONTENT = Strings.intern("content");
	public static final StringShort FIELD_STRUCTURED_CONTENT = Strings.intern("structuredContent");
	public static final StringShort FIELD_TYPE = Strings.intern("type");
	public static final StringShort FIELD_TEXT = Strings.intern("text");
	public static final StringShort FIELD_IS_ERROR = Strings.intern("isError");
	public static final StringShort SERVER_URL_FIELD=Strings.intern("server_url");

	public static final StringShort ARG_SOURCE = Strings.intern("source");
	public static final StringShort ARG_ADDRESS = Strings.intern("address");
	public static final StringShort ARG_VALUE = Strings.intern("value");
	public static final StringShort ARG_ALGORITHM = Strings.intern("algorithm");
	public static final StringShort ARG_SEED = Strings.intern("seed");
	public static final StringShort ARG_SEQUENCE = Strings.intern("sequence");
	public static final StringShort ARG_CVX = Strings.intern("cvx");
	public static final StringShort ARG_PUBLIC_KEY = Strings.intern("publicKey");
	public static final StringShort ARG_SIGNATURE = Strings.intern("signature");
	public static final StringShort ARG_SIG = Strings.intern("sig");
	public static final StringShort ARG_BYTES = Strings.intern("bytes");
	public static final StringShort ARG_ACCOUNT_KEY = Strings.intern("accountKey");
	public static final StringShort ARG_FAUCET = Strings.intern("faucet");
	public static final StringShort ARG_HASH = Strings.intern("hash");
	public static final StringShort ARG_CAD3 = Strings.intern("cad3");
	public static final StringShort ARG_GET_PATH = Strings.intern("getPath");
	public static final StringShort ARG_NAME = Strings.intern("name");
	public static final StringShort ARG_PASSPHRASE = Strings.intern("passphrase");
	public static final StringShort ARG_AUDIENCE = Strings.intern("audience");
	public static final StringShort ARG_LIFETIME = Strings.intern("lifetime");
	public static final StringShort ARG_CONFIRM_TOKEN = Strings.intern("confirmToken");
	public static final StringShort ARG_NEW_PASSPHRASE = Strings.intern("newPassphrase");

	/** ThreadLocal to make the current Javalin Context available to tool handlers */
	static final ThreadLocal<Context> currentContext = new ThreadLocal<>();

	public static final StringShort KEY_NETWORK_ID = Strings.intern("networkId");
	public static final StringShort KEY_PEER_KEY = Strings.intern("peerKey");
	public static final StringShort KEY_VALUE = Strings.intern("value");
	public static final StringShort KEY_ERROR_CODE = Strings.intern("errorCode");
	public static final StringShort KEY_INFO = Strings.intern("info");

	private static final AHashMap<AString, ACell> BASE_RESPONSE = Maps.of("jsonrpc", "2.0");
	private static final AMap<AString, ACell> EMPTY_MAP = Maps.empty();
	private final AMap<AString, ACell> serverInfo;
	private final Map<String, McpTool> tools = new LinkedHashMap<>();
	private final Map<String, McpPrompt> prompts = new LinkedHashMap<>();

	public McpAPI(RESTServer restServer) {
		super(restServer);
		AMap<AString, ACell> info = Maps.of(
			"name", "convex-mcp",
			"title", "Convex MCP",
			"version", Utils.getVersion()
		);
		Peer peer = server.getPeer();
		if (peer != null) {
			info = info.assoc(KEY_NETWORK_ID, Strings.create(peer.getNetworkID().toHexString()));
			info = info.assoc(KEY_PEER_KEY, Strings.create(peer.getPeerKey().toHexString()));
		}
		serverInfo = info;
		registerTools();
		new McpPrompts(this).registerAll();
	}

	public AMap<AString, ACell> getServerInfo() {
		return serverInfo;
	}

	public AVector<AMap<AString, ACell>> getToolMetadata() {
		return listToolsVector();
	}

	@Override
	public void addRoutes(Javalin app) {
		app.post("/mcp", this::handleMcpRequest);
		app.get("/mcp", this::handleMcpGet);
		app.get("/.well-known/mcp", this::getMCPWellKnown);
	}

	/**
	 * GET /mcp — SSE streaming not supported. Return 405 Method Not Allowed
	 * per MCP Streamable HTTP spec.
	 */
	private void handleMcpGet(Context ctx) {
		ctx.status(405);
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
										from = JsonRPCResponse.class,
										exampleObjects = {
											@OpenApiExampleProperty(name = "jsonrpc", value = "2.0"),
											@OpenApiExampleProperty(name = "result", value="{}"),
											@OpenApiExampleProperty(name = "id", value = "1")
										}
										) })
					})	
	private void handleMcpRequest(Context ctx) {
		ctx.contentType(ContentTypes.JSON);
		currentContext.set(ctx);
		try {
			ACell body = JSONReader.read(ctx.bodyInputStream());
			if (body instanceof AMap<?, ?> map) {
				if (isNotification(map)) {
					// Notification (no id) — process but return 202 with no body
					processNotification(map);
					ctx.status(202).result("");
				} else {
					AMap<AString, ACell> response = createResponse(map);
					setContent(ctx, response);
				}
			} else if (body instanceof AVector<?> vector) {
				long n = vector.count();
				if (n == 0) {
					setContent(ctx, protocolError(-32600, "Invalid batch request (empty)"));
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
					// All entries were notifications
					ctx.status(202).result("");
				} else {
					setContent(ctx, responses);
				}
			} else {
				setContent(ctx, protocolError(-32600, "Request must be a JSON object or array"));
			}
		} catch (ParseException | IOException e) {
			setContent(ctx, protocolError(-32700, "Parse error"));
		} catch (Exception e) {
			log.warn("Unexpected error handling MCP request", e);
			setContent(ctx, protocolError(-32603, "Internal error"));
		} finally {
			currentContext.remove();
		}
	}

	/**
	 * Check if a JSON-RPC message is a notification (no "id" field).
	 */
	private boolean isNotification(AMap<?, ?> request) {
		return !request.containsKey(FIELD_ID);
	}

	/**
	 * Process a notification message (no response expected).
	 */
	private void processNotification(AMap<?, ?> request) {
		AString methodCell = RT.ensureString(request.get(FIELD_METHOD));
		if (methodCell == null) return;
		String method = methodCell.toString().trim();
		// Handle known notifications silently
		switch (method) {
			case "notifications/initialized", "notifications/cancelled" -> { /* acknowledged */ }
			default -> log.debug("Unrecognised MCP notification: {}", method);
		}
	}

	/**
	 * Create a response for a single MCP request
	 * @param request
	 * @return
	 */
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
				case "prompts/list" -> result = protocolResult(listPrompts());
				case "prompts/get" -> result = promptGet(request.get(FIELD_PARAMS));
				default -> result = protocolError(-32601, "Method not found: " + method);
			}
		} catch (Exception ex) {
			log.warn("Error handling MCP request for method {}", method, ex);
			result = protocolError(-32603, "Internal error");
		}

		return maybeAttachId(result, idCell);
	}

	private AMap<AString, ACell> buildInitializeResult() {
		AMap<AString, ACell> capabilities = Maps.of(
			"tools", EMPTY_MAP,
			"prompts", EMPTY_MAP
		);
		AMap<AString, ACell> result = Maps.of(
			"protocolVersion", "2025-03-26",
			"serverInfo", serverInfo,
			"capabilities", capabilities
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
		return Maps.of("tools", listToolsVector());
	}

	/* JSON-RPC protocol result */
	AMap<AString, ACell> protocolResult(AMap<AString, ACell> result) {
		return BASE_RESPONSE.assoc(FIELD_RESULT, result);
	}

	/* JSON-RPC protocol error */
	AMap<AString, ACell> protocolError(int code, String message) {
		AMap<AString, ACell> error = Maps.of(
			FIELD_CODE, CVMLong.create(code),
			FIELD_MESSAGE, message
		);
		return BASE_RESPONSE.assoc(FIELD_ERROR, error);
	}

	private AMap<AString, ACell> maybeAttachId(AMap<AString, ACell> response, ACell idCell) {
		if (idCell == null) return response;
		return response.assoc(FIELD_ID, idCell);
	}

	private AMap<AString, ACell> toolCall(ACell paramsCell) {
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

		AMap<AString,ACell> arguments=RT.ensureMap(params.get(FIELD_ARGUMENTS));
		
		if (arguments == null) {
			return protocolError(-32602, toolName +" requires arguments");
		}

		return tool.handle(arguments);
	}

	/* Create a Result from a CVM Result */
	/* Create a Result from a CVM Result - package-private for use by tool extensions */
	AMap<AString, ACell> toolResult(Result result) {
		AMap<AString, ACell> structured = EMPTY_MAP;
		ACell value = result.getValue();
		if (value != null) {
			structured = structured.assoc(KEY_VALUE, value);
		}
		ACell errorCode = result.getErrorCode();
		if (errorCode != null) {
			structured = structured.assoc(KEY_ERROR_CODE, errorCode);
		}
		ACell info = result.getInfo();
		if (info != null) {
			structured = structured.assoc(KEY_INFO, info);
		}
		return protocolResult(buildMcpResult(structured, result.isError()));
	}

	AMap<AString, ACell> toolSuccess(ACell structuredResult) {
		AMap<AString, ACell> payload = RT.ensureMap(structuredResult);
		if (payload == null) payload = EMPTY_MAP;
		return protocolResult(buildMcpResult(payload, false));
	}

	/* Create an error result for a tool call (but protocol valid) */
	AMap<AString, ACell> toolError(String message) {
		AMap<AString, ACell> payload = Maps.of(
			"message", message
		);
		return protocolResult(buildMcpResult(payload, true));
	}

	AMap<AString, ACell> buildMcpResult(AMap<AString, ACell> structured, boolean isError) {
		AString jsonText = JSON.print(structured);
		AMap<AString, ACell> textContent = Maps.of(
			FIELD_TYPE, "text",
			FIELD_TEXT, jsonText
		);
		AVector<AMap<AString, ACell>> content = Vectors.of(textContent);
		return Maps.of(
			FIELD_CONTENT, content,
			FIELD_STRUCTURED_CONTENT, structured,
			FIELD_IS_ERROR, isError?CVMBool.TRUE:CVMBool.FALSE
		);
	}

	private void registerTools() {
		registerTool(new QueryTool());
		registerTool(new PrepareTool());
		registerTool(new TransactTool());
		registerTool(new EncodeTool());
		registerTool(new DecodeTool());
		registerTool(new SubmitTool());
		registerTool(new SignAndSubmitTool());
		registerTool(new HashTool());
		registerTool(new SignTool());
		registerTool(new PeerStatusTool());
		registerTool(new KeyGenTool());
		registerTool(new ValidateTool());
		registerTool(new CreateAccountTool());
		registerTool(new DescribeAccountTool());
		registerTool(new LookupTool());
		registerTool(new ResolveCNSTool());
		registerTool(new GetTransactionTool());

		// Signing service tools (standard + elevated)
		new SigningMcpTools(this).registerAll();
	}

	void registerTool(McpTool tool) {
		tools.put(tool.getName(), tool);
	}

	void registerPrompt(McpPrompt prompt) {
		prompts.put(prompt.getName(), prompt);
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

	private ATransaction decodeTransaction(Blob encodedBlob) throws BadFormatException, MissingDataException {
		ACell value = server.getStore().decodeRef(encodedBlob).getValue();
		if (!(value instanceof ATransaction transaction)) {
			throw new BadFormatException("Value with data " + encodedBlob.toHexString() + " is not a transaction");
		}
		return transaction;
	}

	private class QueryTool extends McpTool {
		QueryTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/query.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
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
				Address address = resolveAddress(arguments.get(ARG_ADDRESS)); // OK if null
				Convex convex = restServer.getConvex();
				Result result = convex.querySync(form, address);
				return toolResult(result);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return toolError("Tool call interrupted");
			} 
		}
	}

	private class TransactTool extends McpTool {
		TransactTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/transact.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments)  {
			AString sourceCell = RT.ensureString(arguments.get(ARG_SOURCE));
			if (sourceCell == null) {
				return protocolError(-32602, "Transact requires 'source' string");
			}
			AString seedCell = RT.ensureString(arguments.get(ARG_SEED));
			if (seedCell == null) {
				return protocolError(-32602, "Transact requires 'seed' string");
			}
			AString addressCell = RT.ensureString(arguments.get(ARG_ADDRESS));
			if (addressCell == null) {
				return protocolError(-32602, "Transact requires 'address' string");
			}
			Blob seedBlob = Blob.parse(seedCell);
			if ((seedBlob == null) || (seedBlob.count() != AKeyPair.SEED_LENGTH)) {
				return toolError("Seed must be 32-byte hex string");
			}
			Address address;
			try {
				address = resolveAddress(addressCell);
			} catch (IllegalArgumentException e) {
				return toolError("Invalid address format: " + e.getMessage());
			}
			if (address == null) {
				return toolError("Invalid address format");
			}
			try {
				AKeyPair keyPair = AKeyPair.create(seedBlob);
				try (Convex client = Convex.connect(server)) {
					client.setAddress(address);
					client.setKeyPair(keyPair);
					Result result = client.transactSync((ACell)Reader.read(sourceCell));
					return toolResult(result);
				}
			} catch (Exception e) {
				return toolError("Transaction failed: " + e.getMessage());
			}
		}
	}

	private class PrepareTool extends McpTool {
		PrepareTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/prepare.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString sourceCell = RT.ensureString(arguments.get(ARG_SOURCE));
			if (sourceCell == null) {
				return protocolError(-32602, "Prepare requires 'source' string");
			}
			AString addressCell = RT.ensureString(arguments.get(ARG_ADDRESS));
			if (addressCell == null) {
				return protocolError(-32602, "Prepare requires 'address' string");
			}
			Address address;
			try {
				address = resolveAddress(addressCell);
			} catch (IllegalArgumentException e) {
				return toolError("Invalid address format: " + e.getMessage());
			}
			if (address == null) {
				return toolError("Invalid address format");
			}

			ACell code;
			try {
				code = Reader.read(sourceCell.toString());
			} catch (Exception e) {
				return toolError("Failed to parse source: " + e.getMessage());
			}

			long sequence;
			ACell sequenceArg = arguments.get(ARG_SEQUENCE);
			if (sequenceArg != null) {
				CVMLong seqLong = CVMLong.parse(sequenceArg);
				if (seqLong == null) {
					return toolError("sequence must be an integer");
				}
				sequence = seqLong.longValue();
			} else try {
				sequence = server.getState().getAccount(address).getSequence() + 1;
			} catch (NullPointerException e) {
				return toolError("Failed to get sequence number, account does not exist: "+address);
			} 

			try {
				ATransaction transaction = Invoke.create(address, sequence, code);
				transaction = Cells.persist(transaction, server.getStore());
				Ref<ATransaction> ref = transaction.getRef();
				String hashHex = SignedData.getMessageForRef(ref).toHexString();
				String dataHex = Format.encodeMultiCell(transaction, true).toHexString();
				AMap<AString, ACell> structured = Maps.of(
				"source", sourceCell,
					"address", address,
					"hash", hashHex,
					"data", dataHex,
					"sequence", CVMLong.create(sequence)
				);
				return toolSuccess(structured);
			} catch (Exception e) {
				return toolError("Prepare failed: " + e.getMessage());
			}
		}
	}

	private class HashTool extends McpTool {
		HashTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/hash.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
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
			AMap<AString, ACell> result = Maps.of(
				"algorithm", algorithm,
				"hash", hashHex
			);
			return toolSuccess(result);
		}
	}

	private class SignTool extends McpTool {
		SignTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/sign.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString valueCell = RT.ensureString(arguments.get(ARG_VALUE));
			if (valueCell == null) {
				return toolError("Sign tool requires a 'value' hex string");
			}
			Blob valueBlob = Blob.parse(valueCell.toString());
			if (valueBlob == null) {
				return toolError("Value must be valid hex data");
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
				ASignature signature = keyPair.sign(valueBlob);
				AccountKey accountKey = keyPair.getAccountKey();
				AMap<AString, ACell> result = Maps.of(
					"value", valueCell,
					"signature", signature.toHexString(),
					"accountKey", accountKey.toHexString()
				);
				return toolSuccess(result);
			} catch (Exception e) {
				return toolError("Signing failed: " + e.getMessage());
			}
		}
	}

	private class SubmitTool extends McpTool {
		SubmitTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/submit.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments)  {
			AString hashCell = RT.ensureString(arguments.get(ARG_HASH));
			if (hashCell == null) {
				return protocolError(-32602, "Submit requires 'hash' string");
			}
			Blob hashBlob = Blob.parse(hashCell);
			if (hashBlob == null) {
				return toolError("hash must be valid hex");
			}
			try {
				ATransaction transaction = decodeTransaction(hashBlob);
				AString accountKeyCell = RT.ensureString(arguments.get(ARG_ACCOUNT_KEY));
				if (accountKeyCell == null) {
					return protocolError(-32602, "Submit requires 'accountKey' string");
				}
				AccountKey accountKey = AccountKey.parse(accountKeyCell.toString());
				if (accountKey == null) {
					return toolError("Invalid account key");
				}
				// Accept both 'sig' and 'signature' for backwards compatibility
				AString signatureCell = RT.ensureString(arguments.get(ARG_SIG));
				if (signatureCell == null) {
					signatureCell = RT.ensureString(arguments.get(ARG_SIGNATURE));
				}
				if (signatureCell == null) {
					return protocolError(-32602, "Submit requires 'sig' string with Ed25519 signature");
				}
				Blob signatureBlob = Blob.parse(signatureCell.toString());
				if ((signatureBlob == null) || (signatureBlob.count() != Ed25519Signature.SIGNATURE_LENGTH)) {
					return toolError("signature must be a 64-byte hex string");
				}
				ASignature signature = Ed25519Signature.fromBlob(signatureBlob);
				SignedData<ATransaction> signed = SignedData.create(accountKey, signature, transaction.getRef());
				Result result = restServer.getConvex().transactSync(signed);
				return toolResult(result);
			} catch (Exception e) {
				return toolError("Submit failed: " + e.getMessage());
			}
		}
	}

	private class SignAndSubmitTool extends McpTool {
		SignAndSubmitTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/signAndSubmit.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString hashCell = RT.ensureString(arguments.get(ARG_HASH));
			if (hashCell == null) {
				return protocolError(-32602, "signAndSubmit requires 'hash' string");
			}
			Blob hashBlob = Blob.parse(hashCell);
			if (hashBlob == null) {
				return toolError("hash must be valid hex");
			}
			AString seedCell = RT.ensureString(arguments.get(ARG_SEED));
			if (seedCell == null) {
				return protocolError(-32602, "signAndSubmit requires 'seed' string");
			}
			Blob seedBlob = Blob.parse(seedCell);
			if (seedBlob == null || seedBlob.count() != AKeyPair.SEED_LENGTH) {
				return toolError("seed must be a 32-byte hex string (64 hex characters)");
			}
			try {
				ATransaction transaction = decodeTransaction(hashBlob);
				AKeyPair keyPair = AKeyPair.create(seedBlob);
				SignedData<ATransaction> signed = keyPair.signData(transaction);
				Result result = restServer.getConvex().transactSync(signed);
				return toolResult(result);
			} catch (Exception e) {
				return toolError("signAndSubmit failed: " + e.getMessage());
			}
		}
	}

	private class PeerStatusTool extends McpTool {
		PeerStatusTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/peerStatus.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				AMap<?, ?> status = server.getStatusMap();
				AMap<AString, ACell> payload = Maps.of("status", (ACell) status);
				return toolSuccess(payload);
			} catch (Exception e) {
				return toolError("Failed to load peer status: " + e.getMessage());
			}
		}
	}

	private class EncodeTool extends McpTool {
		EncodeTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/encode.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString cvxCell = RT.ensureString(arguments.get(ARG_CVX));
			if (cvxCell == null) {
				return protocolError(-32602, "Encode requires 'cvx' string");
			}
			try {
				ACell value = Reader.read(cvxCell.toString());
				Blob encoded = Format.encodeMultiCell(value, true);
				AMap<AString, ACell> result = Maps.of(
					"cad3", encoded.toCVMHexString(),
					"hash", Ref.get(value).getEncoding().toCVMHexString()
				);
				return toolSuccess(result);
			} catch (Exception e) {
				return toolError("Encode failed: " + e.getMessage());
			}
		}
	}

	private class DecodeTool extends McpTool {
		DecodeTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/decode.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString cad3Cell = RT.ensureString(arguments.get(ARG_CAD3));
			if (cad3Cell == null) {
				return protocolError(-32602, "Decode requires 'cad3' string");
			}
			Blob cad3Blob = Blob.parse(cad3Cell);
			if (cad3Blob == null) {
				return toolError("cad3 must be valid hex data");
			}
			try {
				ACell decoded = Format.decodeMultiCell(cad3Blob);
				AString cvx = RT.print(decoded);
				AMap<AString, ACell> result = Maps.of(
					"cvx", cvx == null ? "" : cvx
				);
				return toolSuccess(result);
			} catch (Exception e) {
				return toolError("Decode failed: " + e.getMessage());
			}
		}
	}

	private class KeyGenTool extends McpTool {
		KeyGenTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/keyGen.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			Blob seedBlob;
			try {
				AString seedCell = RT.ensureString(arguments != null ? arguments.get(ARG_SEED) : null);
				if (seedCell != null) {
					// Use provided seed
					String seedHex = seedCell.toString();
					seedBlob = Blob.parse(seedHex);
					if (seedBlob == null) {
						return toolError("Seed must be valid hex data");
					}
					if (seedBlob.count() != AKeyPair.SEED_LENGTH) {
						return toolError("Seed must be 32-byte hex string (64 hex characters)");
					}
				} else {
					// Generate secure random seed
					seedBlob = Blob.createRandom(new SecureRandom(), AKeyPair.SEED_LENGTH);
				}
				
				AKeyPair keyPair = AKeyPair.create(seedBlob);
				AccountKey publicKey = keyPair.getAccountKey();
				
				AMap<AString, ACell> result = Maps.of(
					"seed", seedBlob.toString(),
					"publicKey", publicKey.toString()
				);
				return toolSuccess(result);
			} catch (Exception e) {
				return toolError("Key generation failed: " + e.getMessage());
			}
		}
	}

	private class ValidateTool extends McpTool {
		ValidateTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/validate.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString publicKeyCell = RT.ensureString(arguments.get(ARG_PUBLIC_KEY));
			if (publicKeyCell == null) {
				return protocolError(-32602, "Validate requires 'publicKey' string");
			}
			AString signatureCell = RT.ensureString(arguments.get(ARG_SIGNATURE));
			if (signatureCell == null) {
				return protocolError(-32602, "Validate requires 'signature' string");
			}
			AString bytesCell = RT.ensureString(arguments.get(ARG_BYTES));
			if (bytesCell == null) {
				return protocolError(-32602, "Validate requires 'bytes' string");
			}
			
			try {
				AccountKey publicKey = AccountKey.parse(publicKeyCell.toString());
				if (publicKey == null) {
					return toolError("Invalid public key format");
				}
				
				Blob signatureBlob = Blob.parse(signatureCell.toString());
				if (signatureBlob == null) {
					return toolError("Signature must be valid hex data");
				}
				if (signatureBlob.count() != Ed25519Signature.SIGNATURE_LENGTH) {
					return toolError("Signature must be 64-byte hex string (128 hex characters)");
				}
				ASignature signature = Ed25519Signature.fromBlob(signatureBlob);
				
				Blob messageBlob = Blob.parse(bytesCell.toString());
				if (messageBlob == null) {
					return toolError("Bytes must be valid hex data");
				}
				
				boolean isValid = Providers.verify(signature, messageBlob, publicKey);
				
				AMap<AString, ACell> result = Maps.of(
					"value", isValid ? CVMBool.TRUE : CVMBool.FALSE
				);
				return toolSuccess(result);
			} catch (Exception e) {
				return toolError("Validation failed: " + e.getMessage());
			}
		}
	}

	/**
	 * Common method to perform faucet payout. Used by both createAccount and potentially other tools.
	 * @param faucetClient The Convex client with faucet permissions
	 * @param address The address to transfer coins to
	 * @param faucetAmount The amount in copper to transfer (may be null)
	 * @return Result of the transfer, or null if no transfer was requested
	 */
	private Result performFaucetPayout(Convex faucetClient, Address address, Long faucetAmount) throws InterruptedException {
		if (faucetAmount == null) {
			return null;
		}
		long amt = faucetAmount;
		// Apply same limit as ChainAPI.faucetRequest
		if (amt > Coin.GOLD) {
			amt = Coin.GOLD;
		}
		return faucetClient.transferSync(address, amt);
	}

	private class CreateAccountTool extends McpTool {
		CreateAccountTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/createAccount.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments)  {
			AString accountKeyCell = RT.ensureString(arguments.get(ARG_ACCOUNT_KEY));
			if (accountKeyCell == null) {
				return protocolError(-32602, "CreateAccount requires 'accountKey' string");
			}
			
			Convex faucetClient = restServer.getFaucet();
			if (faucetClient == null) {
				return toolError("Faucet use not authorised on this server");
			}
			
			try {
				AccountKey accountKey = AccountKey.parse(accountKeyCell.toString());
				if (accountKey == null) {
					return toolError("Unable to parse accountKey: " + accountKeyCell);
				}
				
				ACell faucetCell = arguments.get(ARG_FAUCET);
				Long faucetAmount = null;
				if (faucetCell != null) {
					CVMLong faucetLong = CVMLong.parse(faucetCell);
					if (faucetLong == null) {
						return toolError("Faucet amount must be a valid number");
					}
					faucetAmount = faucetLong.longValue();
				}
				
				Address address = faucetClient.createAccountSync(accountKey);
				
				// Perform faucet payout if requested
				if (faucetAmount != null) {
					Result transferResult = performFaucetPayout(faucetClient, address, faucetAmount);
					if (transferResult != null && transferResult.isError()) {
						return toolResult(transferResult);
					}
				}
				
				AMap<AString, ACell> result = Maps.of(
					"address", CVMLong.create(address.longValue())
				);
				return toolSuccess(result);
			} catch (ResultException e) {
				return toolResult(e.getResult());
			} catch (Exception e) {
				return toolError("Account creation failed: " + e.getMessage());
			}
		}
	}
	
	private class DescribeAccountTool extends McpTool {
		DescribeAccountTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/describeAccount.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				Address address;
				try {
					address = resolveAddress(RT.getIn(arguments,ARG_ADDRESS));
				} catch (IllegalArgumentException e) {
					return toolError("Invalid address format: " + e.getMessage());
				}
				if (address == null) {
					return toolError("No valid address provided");
				}
				
				AccountStatus accountStatus = server.getState().getAccount(address);
				if (accountStatus == null) {
					return toolError("Account not found: " + address);
				}
				
				// Get metadata: AHashMap<Symbol, AHashMap<ACell,ACell>>
				AHashMap<Symbol, AHashMap<ACell, ACell>> meta = accountStatus.getMetadata();
				if (meta==null) meta=Maps.empty();
				
				// Complete metadata with any Symbols that have no metadata but are present in environment
				AHashMap<Symbol, ACell> env = accountStatus.getEnvironment();
				if (env!=null) {
					long esize=env.count();
					for (long i=0; i<esize; i++) {
						MapEntry<Symbol, ACell> entry = env.entryAt(i);
						Symbol sym = entry.getKey();
						if (!meta.containsKey(sym)) {
							meta=meta.assoc(sym, null);
						}
					}
				}
				
				Object metadataString = RT.print(meta);
				if (metadataString == null) {
					metadataString="Metadata too large to print";
				}
				AMap<AString, ACell> resultMap = Maps.of(
					"metadata", metadataString,
					"accountInfo",ChainAPI.getAccountInfo(address, accountStatus)
				);
				return toolSuccess(resultMap);
			} catch (Exception e) {
				return toolError("Account lookup failed: " + e.getMessage());
			}
		}
	}
	
	private class LookupTool extends McpTool {
		LookupTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/lookup.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				// Parse address
				ACell addressCell = arguments.get(ARG_ADDRESS);
				if (addressCell == null) {
					return toolError("Lookup requires 'address' parameter, e.g. '#5675' or '@convex.core'");
				}
				Address address;
				try {
					address = resolveAddress(addressCell);
				} catch (IllegalArgumentException e) {
					return toolError("Invalid address format: " + e.getMessage());
				}
				if (address == null) {
					return toolError("Invalid address format");
				}
				
				// Parse symbol
				AString symbolCell = RT.ensureString(arguments.get(Strings.SYMBOL));
				if (symbolCell == null) {
					return toolError("Lookup requires 'symbol' parameter");
				}
				Symbol symbol = RT.ensureSymbol(Reader.read(symbolCell));
				
				// Get account status
				AccountStatus accountStatus = server.getPeer().getConsensusState().getAccount(address);
				if (accountStatus == null) {
					return toolError("Account not found: " + address);
				}
				
				// Check if symbol exists in environment
				AHashMap<Symbol, ACell> env = accountStatus.getEnvironment();
				boolean exists = (env != null) && env.containsKey(symbol);
				
				if (!exists) {
					return toolSuccess(Maps.of("exists", CVMBool.FALSE));
				}

				// Get the value
				ACell value = null;
				if (exists && env != null) {
					value = env.get(symbol);
					
					// Apply path if provided
					AString pathCell = RT.ensureString(arguments.get(ARG_GET_PATH));
					if (pathCell != null && value != null) {
						String pathStr = pathCell.toString();
						try {
							// Parse the path as a sequence
							ACell pathForm = Reader.read(pathStr);
							AVector<ACell> pathSeq = RT.ensureVector(pathForm);
							if (pathSeq != null) {
								// Convert sequence to array for RT.getIn
								long pathLen = pathSeq.count();
								ACell[] pathKeys = new ACell[(int)pathLen];
								for (long i = 0; i < pathLen; i++) {
									pathKeys[(int)i] = pathSeq.get(i);
								}
								value = RT.getIn(value, pathKeys);
							} else {
								// If not a vector, try as a single key
								value = RT.getIn(value, pathForm);
							}
						} catch (Exception e) {
							return toolError("Failed to parse getPath: " + e.getMessage());
						}
					}
				}
				
				// Get metadata for the symbol (only if it exists)
				AHashMap<ACell, ACell> meta = null;
				if (exists) {
					AHashMap<ACell, ACell> symbolMeta = accountStatus.getMetadata(symbol);
					// Return metadata only if it's not empty
					if (symbolMeta != null && !symbolMeta.isEmpty()) {
						meta = symbolMeta;
					}
				}
				
				// Build result
				AMap<AString, ACell> resultMap = Maps.of(
					"exists", exists ? CVMBool.TRUE : CVMBool.FALSE,
					"value", RT.print(value),
					"meta", RT.print(meta)
				);
				return toolSuccess(resultMap);
			} catch (Exception e) {
				return toolError("Lookup failed: " + e.getMessage());
			}
		}
	}
	
	private class ResolveCNSTool extends McpTool {
		ResolveCNSTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/resolveCNS.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				// Parse name argument
				AString nameCell = RT.ensureString(arguments.get(ARG_NAME));
				if (nameCell == null) {
					return toolError("ResolveCNS requires 'name' parameter, e.g. 'convex.core'");
				}

				// Remove leading @ if present
				if (nameCell.startsWith("@")) {
					nameCell = nameCell.slice(1);
				}

				// Treat as Symbol
				Symbol symbol=Symbol.create(nameCell);
				if (symbol == null) {
					return toolError("Invalid CNS name: "+nameCell);
				}

				// Look up CNS record
				AVector<ACell> record = server.getState().lookupCNSRecord(symbol);
				if (record == null) {
					return toolSuccess(Maps.of("exists", CVMBool.FALSE));
				}

				if (record.count() != 4) {
					return toolError("CNS record has unexpected length, got "+record);
				}

				// Extract elements from vector: [value controller meta child]
				ACell value = record.get(0);
				ACell controller = record.get(1);
				ACell meta = record.get(2);
				ACell child = record.get(3);

				// Build result map
				AMap<AString, ACell> resultMap = Maps.of(
					"value", RT.print(value),
					"controller", RT.print(controller),
					"meta", RT.print(meta),
					"child", RT.print(child),
					"exists", CVMBool.TRUE
				);
				return toolSuccess(resultMap);
			} catch (Exception e) {
				return toolError("CNS resolution failed: " + e.getMessage());
			}
		}
	}

	private class GetTransactionTool extends McpTool {
		GetTransactionTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/getTransaction.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString hashCell = RT.ensureString(arguments.get(ARG_HASH));
			if (hashCell == null) {
				return protocolError(-32602, "getTransaction requires 'hash' string");
			}

			Hash h = Hash.parse(hashCell.toString());
			if (h == null) {
				return toolError("Invalid hash format: " + hashCell);
			}

			try {
				Peer peer = server.getPeer();

				SignedData<ATransaction> transaction = peer.getTransaction(h);
				if (transaction == null) {
					return toolSuccess(Maps.of("found", CVMBool.FALSE));
				}

				AVector<CVMLong> pos = peer.getTransactionLocation(h);
				Result txResult = peer.getTransactionResult(pos);

				AMap<AString, ACell> resultMap = Maps.of(
					"found", CVMBool.TRUE,
					"tx", RT.print(transaction),
					"position", pos,
					"result", RT.print(txResult)
				);
				return toolSuccess(resultMap);
			} catch (Exception e) {
				return toolError("getTransaction failed: " + e.getMessage());
			}
		}
	}

	/**
	 * Gets the RESTServer instance. Package-private accessor for tool extensions.
	 */
	RESTServer getRESTServer() {
		return restServer;
	}

	/**
	 * Gets the authenticated identity from the current request context, or null if unauthenticated.
	 */
	AString getRequestIdentity() {
		Context ctx = currentContext.get();
		if (ctx == null) return null;
		return AuthMiddleware.getIdentity(ctx);
	}

	private AMap<AString,ACell> WELL_KNOWN=JSON.parse("""
		{	
			"mcp_version": "1.0",
			"server_url": "http://localhost:8080/mcp",
			"description": "Convex network MCP for decentralised economic systems",
			"tools_endpoint": "/mcp",
			"endpoint": {"path":"/mcp","transport":"streamable-http"}
		}
""");
	
	@OpenApi(path = "/.well-known/mcp", 
			methods = HttpMethod.GET, 
			tags = { "MCP"},
			summary = "Get MCP server capabilities", 
			operationId = "mcpWellKnown")	
	protected void getMCPWellKnown(Context ctx) { 
		AMap<AString,ACell> result=WELL_KNOWN;
		AString mcpURL=Strings.create(getExternalBaseUrl(ctx, "mcp"));
		result=result.assoc(SERVER_URL_FIELD,mcpURL);
		setContent(ctx,result);
	}

}