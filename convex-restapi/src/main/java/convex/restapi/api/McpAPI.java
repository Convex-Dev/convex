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
import convex.core.crypto.ASignature;
import convex.core.crypto.Hashing;
import convex.core.crypto.Ed25519Signature;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
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
import convex.restapi.mcp.McpTool;
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
	public static final StringShort FIELD_STRUCTURED_CONTENT = Strings.intern("structured_content");
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

	public AMap<AString, ACell> getServerInfo() {
		return serverInfo;
	}

	public AVector<AMap<AString, ACell>> getToolMetadata() {
		return listToolsVector();
	}

	@Override
	public void addRoutes(Javalin app) {
		app.post("/mcp", this::handleMcpRequest);
		app.get("/.well-known/mcp", this::getMCPWellKnown);
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
		try {
			ACell body = JSONReader.read(ctx.bodyInputStream());
			if (body instanceof AMap<?, ?> map) {
				AMap<AString, ACell> response = createResponse(map);
				setContent(ctx, response);
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
						responses = responses.conj(createResponse(batchMap));
					} else {
						responses = responses.conj(protocolError(-32600, "Invalid Request"));
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

		return tool.handle(RT.ensureMap(params.get(FIELD_ARGUMENTS)));
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
		return protocolResult(buildMcpResult(structured, result.isError()));
	}

	private AMap<AString, ACell> toolSuccess(ACell structuredResult) {
		AMap<AString, ACell> payload = RT.ensureMap(structuredResult);
		if (payload == null) payload = EMPTY_MAP;
		return protocolResult(buildMcpResult(payload, false));
	}

	private AMap<AString, ACell> toolError(String message) {
		AMap<AString, ACell> payload = Maps.of(
			Strings.create("message"), Strings.create(message)
		);
		return protocolResult(buildMcpResult(payload, true));
	}

	private AMap<AString, ACell> buildMcpResult(AMap<AString, ACell> structured, boolean isError) {
		AString jsonText = JSON.print(structured);
		AMap<AString, ACell> textContent = Maps.of(
			FIELD_TYPE, Strings.create("text"),
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
		registerTool(new SubmitTool());
		registerTool(new HashTool());
		registerTool(new SignTool());
		registerTool(new PeerStatusTool());
	}

	private void registerTool(McpTool tool) {
		tools.put(tool.getName(), tool);
	}

	private ATransaction decodeTransaction(Blob hashBlob) throws BadFormatException, MissingDataException {
		Ref<?> ref = Format.readRef(hashBlob, 0);
		ACell value = ref.getValue();
		if (!(value instanceof ATransaction transaction)) {
			throw new BadFormatException("Value with hash " + hashBlob.toHexString() + " is not a transaction");
		}
		return transaction;
	}

	private class QueryTool extends McpTool {
		QueryTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/query.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) throws InterruptedException {
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
				Address address = Address.parse(arguments.get(ARG_ADDRESS));
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
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) throws InterruptedException {
			if (arguments == null) {
				return protocolError(-32602, "Transact requires arguments");
			}
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
			Address address= Address.parse(addressCell.toString());
			if (address == null) {
				return toolError("Invalid address format");
			}
			try {
				AKeyPair keyPair = AKeyPair.create(seedBlob);
				try (Convex client = Convex.connect(server)) {
					client.setAddress(address);
					client.setKeyPair(keyPair);
					Result result = client.transactSync(Reader.read(sourceCell));
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
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) throws InterruptedException {
			AString sourceCell = RT.ensureString(arguments.get(ARG_SOURCE));
			if (sourceCell == null) {
				return protocolError(-32602, "Prepare requires 'source' string");
			}
			AString addressCell = RT.ensureString(arguments.get(ARG_ADDRESS));
			if (addressCell == null) {
				return protocolError(-32602, "Prepare requires 'address' string");
			}
			Address address = Address.parse(addressCell.toString());
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
			} else {
				try {
					sequence = restServer.getConvex().getSequence(address) + 1;
				} catch (ResultException e) {
					return toolResult(e.getResult());
				}
			}

			try {
				ATransaction transaction = Invoke.create(address, sequence, code);
				transaction = Cells.persist(transaction);
				Ref<ATransaction> ref = transaction.getRef();
				String hashHex = SignedData.getMessageForRef(ref).toHexString();
				String dataHex = Format.encodeMultiCell(transaction, true).toHexString();
				AMap<AString, ACell> structured = Maps.of(
					Strings.create("source"), sourceCell,
					Strings.create("address"), Strings.create(address.toString()),
					Strings.create("hash"), Strings.create(hashHex),
					Strings.create("data"), Strings.create(dataHex),
					Strings.create("sequence"), CVMLong.create(sequence)
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
			AMap<AString, ACell> result = Maps.of(
				Strings.create("algorithm"), Strings.create(algorithm),
				Strings.create("hash"), Strings.create(hashHex)
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
			if (arguments == null) {
				return protocolError(-32602, "Sign tool requires arguments");
			}
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
					Strings.create("value"), valueCell,
						Strings.create("signature"), Strings.create(signature.toHexString()),
					Strings.create("accountKey"), Strings.create(accountKey.toHexString())
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
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) throws InterruptedException {
			if (arguments == null) {
				return protocolError(-32602, "Submit requires arguments");
			}
			AString hashCell = RT.ensureString(arguments.get(Strings.create("hash")));
			if (hashCell == null) {
				return protocolError(-32602, "Submit requires 'hash' string");
			}
			Blob hashBlob = Blob.parse(hashCell);
			if (hashBlob == null) {
				return toolError("hash must be valid hex");
			}
			try {
				ATransaction transaction = decodeTransaction(hashBlob);
				AString accountKeyCell = RT.ensureString(arguments.get(Strings.create("accountKey")));
				if (accountKeyCell == null) {
					return protocolError(-32602, "Submit requires 'accountKey' string");
				}
				AccountKey accountKey = AccountKey.parse(accountKeyCell.toString());
				if (accountKey == null) {
					return toolError("Invalid account key");
				}
				AString signatureCell = RT.ensureString(arguments.get(Strings.create("signature")));
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

	private class PeerStatusTool extends McpTool {
		PeerStatusTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/peerStatus.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				AMap<?, ?> status = server.getStatusMap();
				AMap<AString, ACell> payload = Maps.of(Strings.create("status"), (ACell) status);
				return toolSuccess(payload);
			} catch (Exception e) {
				return toolError("Failed to load peer status: " + e.getMessage());
			}
		}
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