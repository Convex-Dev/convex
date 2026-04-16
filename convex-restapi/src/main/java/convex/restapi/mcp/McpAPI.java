package convex.restapi.mcp;

import static convex.restapi.mcp.McpProtocol.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.Hashing;
import convex.core.crypto.Ed25519Signature;
import convex.core.crypto.Providers;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.MapEntry;
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
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import convex.restapi.api.ChainAPI;
import convex.restapi.auth.AuthMiddleware;
import jakarta.servlet.http.HttpServletResponse;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * MCP (Model Context Protocol) implementation for Convex peers.
 *
 * <p>Provides JSON-RPC over Streamable HTTP transport with SSE support, exposing
 * Convex on-chain operations (query, transact, account management, etc.) as MCP
 * tools.</p>
 *
 * <h3>SECURITY — Denial of Service risk</h3>
 *
 * <p>MCP is a public endpoint. Every SSE connection holds a virtual thread and TCP
 * socket. A malicious client can exhaust resources by opening many connections.</p>
 *
 * <p>Mitigations in place:</p>
 * <ul>
 *   <li>{@code initialize} creates zero server-side state. The session ID returned
 *       is a correlation token, not stored.</li>
 *   <li>The only heavy resource ({@link McpConnection}: virtual thread + TCP socket)
 *       is created on {@code GET /mcp}, bounded by {@link #MAX_CONNECTIONS}.</li>
 *   <li>Watches are connection-scoped and bounded by {@link #MAX_WATCHES_PER_CONNECTION}.
 *       Disconnect destroys everything.</li>
 * </ul>
 *
 * <p><b>High-value peers (large stake, critical infrastructure) should normally disable
 * MCP entirely</b> and leave it to lower-staked proxy or gateway peers that can absorb
 * DoS risk without threatening consensus participation. MCP is best suited for
 * dedicated API/gateway peers rather than core validators.</p>
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a>
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25">MCP Specification</a>
 */
public class McpAPI extends ABaseAPI {

	private static final Logger log = LoggerFactory.getLogger(McpAPI.class);

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
	public static final StringShort ARG_CONTROLLER = Strings.intern("controller");
	public static final StringShort ARG_TOKEN = Strings.intern("token");
	public static final StringShort ARG_TO = Strings.intern("to");
	public static final StringShort ARG_AMOUNT = Strings.intern("amount");
	public static final StringShort ARG_PATH = Strings.intern("path");
	public static final StringShort ARG_WATCH_ID = Strings.intern("watchId");

	/** Maximum concurrent McpConnections. Each holds a virtual thread + TCP socket. */
	public static final int MAX_CONNECTIONS = 1000;

	/** Maximum watches per connection. Caps polling overhead per client. */
	public static final int MAX_WATCHES_PER_CONNECTION = 16;

	/** Size threshold for queryState responses (bytes). Values larger than this are omitted. */
	static final long QUERY_STATE_SIZE_THRESHOLD = 4096;

	/** Maximum entries in a batch JSON-RPC request. */
	public static final int MAX_BATCH_SIZE = 20;

	public static final StringShort KEY_NETWORK_ID = Strings.intern("networkId");
	public static final StringShort KEY_PEER_KEY = Strings.intern("peerKey");
	public static final StringShort KEY_VALUE = Strings.intern("value");
	public static final StringShort KEY_ERROR_CODE = Strings.intern("errorCode");
	public static final StringShort KEY_INFO = Strings.intern("info");

	private final McpServer mcpServer;

	/** Connection map: session ID → McpConnection (created on GET /mcp) */
	private final ConcurrentHashMap<String, McpConnection> connections = new ConcurrentHashMap<>();

	/** Convex-specific state watcher */
	private final ConvexStateWatcher stateWatcher = new ConvexStateWatcher();

	public McpAPI(RESTServer restServer, McpServer mcpServer) {
		super(restServer);
		this.mcpServer = mcpServer;

		// Enrich server info with peer details
		AMap<AString, ACell> info = mcpServer.getServerInfo();
		Peer peer = server.getPeer();
		if (peer != null) {
			info = info.assoc(KEY_NETWORK_ID, Strings.create(peer.getNetworkID().toHexString()));
			info = info.assoc(KEY_PEER_KEY, Strings.create(peer.getPeerKey().toHexString()));
			mcpServer.setServerInfo(info);
		}

		registerTools();
		new McpPrompts(this, mcpServer).registerAll();
	}

	public McpServer getMcpServer() {
		return mcpServer;
	}

	public AMap<AString, ACell> getServerInfo() {
		return mcpServer.getServerInfo();
	}

	public AVector<AMap<AString, ACell>> getToolMetadata() {
		return mcpServer.getToolMetadata();
	}

	@Override
	public void addRoutes(Javalin app) {
		// McpServer handles POST /mcp and GET /.well-known/mcp
		// We add only the SSE session routes (Convex-specific watch support)
		app.get("/mcp", this::handleMcpGet);
		app.delete("/mcp", this::handleMcpDelete);
	}

	/**
	 * GET /mcp — Open long-lived SSE stream for server-to-client notifications.
	 *
	 * <p>Creates the only server-side resource: a {@link McpConnection} keyed by
	 * session ID. This is the notification delivery channel for state watches.
	 * Cleaned up immediately on disconnect.</p>
	 */
	private void handleMcpGet(Context ctx) {
		String accept = ctx.header("Accept");
		if (accept == null || !accept.contains("text/event-stream")) {
			ctx.status(405);
			return;
		}

		// Enforce global connection limit (soft cap)
		if (connections.size() >= MAX_CONNECTIONS) {
			ctx.status(429);
			return;
		}

		// Get session ID from header, or generate one
		String sessionId = ctx.header(HEADER_SESSION_ID);
		if (sessionId == null) {
			sessionId = UUID.randomUUID().toString();
		}

		try {
			HttpServletResponse res = ctx.res();
			res.setContentType("text/event-stream");
			res.setCharacterEncoding("UTF-8");
			res.setHeader("Cache-Control", "no-cache");
			res.setHeader("X-Accel-Buffering", "no");
			res.setHeader(HEADER_SESSION_ID, sessionId);

			PrintWriter writer = res.getWriter();
			McpConnection conn = new McpConnection(writer);
			// Register connection BEFORE flushing headers so POSTs using
			// the session ID can find it immediately.
			connections.put(sessionId, conn);
			res.flushBuffer();
			try {
				// Keep-alive loop — blocks virtual thread until client disconnects
				while (!conn.isClosed()) {
					writer.write(": keepalive\n\n");
					writer.flush();
					if (writer.checkError()) break;
					Thread.sleep(McpProtocol.SSE_KEEPALIVE_MS);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				conn.close();
				connections.remove(sessionId);
			}
		} catch (IOException e) {
			log.debug("SSE connection setup failed", e);
		}
	}

	/**
	 * DELETE /mcp — Terminate session and close connection.
	 */
	private void handleMcpDelete(Context ctx) {
		String sessionId = ctx.header(HEADER_SESSION_ID);
		if (sessionId == null) {
			ctx.status(400);
			return;
		}
		McpConnection conn = connections.remove(sessionId);
		if (conn != null) {
			conn.close();
			ctx.status(200);
		} else {
			ctx.status(404);
		}
	}

	// ===== Delegating methods for tool extensions (e.g. SigningMcpTools) =====

	/* JSON-RPC protocol error — delegates to McpProtocol */
	AMap<AString, ACell> protocolError(int code, String message) {
		return McpProtocol.protocolError(code, message);
	}

	/* MCP tool success — delegates to McpProtocol */
	AMap<AString, ACell> toolSuccess(ACell structuredResult) {
		return McpProtocol.toolSuccess(structuredResult);
	}

	/* MCP tool error — returns isError result, not JSON-RPC error.
	 * Per MCP 2025-11-25: tool input validation errors use this so LLMs can self-correct. */
	AMap<AString, ACell> toolError(String message) {
		return McpProtocol.toolError(message);
	}

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
		registerTool(new GetBalanceTool());
		registerTool(new TransferTool());
		registerTool(new QueryStateTool());
		registerTool(new WatchStateTool());
		registerTool(new UnwatchStateTool());

		// Signing service tools (standard + elevated)
		new SigningMcpTools(this).registerAll();
	}

	void registerTool(McpTool tool) {
		mcpServer.registerTool(tool);
	}

	void registerPrompt(McpPrompt prompt) {
		mcpServer.registerPrompt(prompt);
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
				return toolError("Query requires 'source' string");
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
				return toolError("Transact requires 'source' string");
			}
			AString seedCell = RT.ensureString(arguments.get(ARG_SEED));
			if (seedCell == null) {
				return toolError("Transact requires 'seed' string");
			}
			AString addressCell = RT.ensureString(arguments.get(ARG_ADDRESS));
			if (addressCell == null) {
				return toolError("Transact requires 'address' string");
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
				return toolError("Prepare requires 'source' string");
			}
			AString addressCell = RT.ensureString(arguments.get(ARG_ADDRESS));
			if (addressCell == null) {
				return toolError("Prepare requires 'address' string");
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
				return toolError("Hash tool requires 'value' string");
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
				return toolError("Submit requires 'hash' string");
			}
			Blob hashBlob = Blob.parse(hashCell);
			if (hashBlob == null) {
				return toolError("hash must be valid hex");
			}
			try {
				ATransaction transaction = decodeTransaction(hashBlob);
				AString accountKeyCell = RT.ensureString(arguments.get(ARG_ACCOUNT_KEY));
				if (accountKeyCell == null) {
					return toolError("Submit requires 'accountKey' string");
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
					return toolError("Submit requires 'sig' string with Ed25519 signature");
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
				return toolError("signAndSubmit requires 'hash' string");
			}
			Blob hashBlob = Blob.parse(hashCell);
			if (hashBlob == null) {
				return toolError("hash must be valid hex");
			}
			AString seedCell = RT.ensureString(arguments.get(ARG_SEED));
			if (seedCell == null) {
				return toolError("signAndSubmit requires 'seed' string");
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
				return toolError("Encode requires 'cvx' string");
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
				return toolError("Decode requires 'cad3' string");
			}
			Blob cad3Blob = Blob.parse(cad3Cell);
			if (cad3Blob == null) {
				return toolError("cad3 must be valid hex data");
			}
			try {
				ACell decoded = server.getStore().decodeMultiCell(cad3Blob);
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
				return toolError("Validate requires 'publicKey' string");
			}
			AString signatureCell = RT.ensureString(arguments.get(ARG_SIGNATURE));
			if (signatureCell == null) {
				return toolError("Validate requires 'signature' string");
			}
			AString bytesCell = RT.ensureString(arguments.get(ARG_BYTES));
			if (bytesCell == null) {
				return toolError("Validate requires 'bytes' string");
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
		long max = restServer.getFaucetMax();
		if (amt > max) {
			amt = max;
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
				return toolError("CreateAccount requires 'accountKey' string");
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

				// Resolve controller: default *caller* (faucet address), "nil" for self-sovereign
				AString controllerCell = RT.ensureString(arguments.get(ARG_CONTROLLER));
				String controllerStr = (controllerCell != null) ? controllerCell.toString() : "*caller*";

				String controllerCVM;
				if ("nil".equals(controllerStr)) {
					controllerCVM = null; // no controller — self-sovereign
				} else if ("*caller*".equals(controllerStr)) {
					controllerCVM = "#" + faucetClient.getAddress().longValue();
				} else {
					// Parse as address literal e.g. "#13"
					Address cAddr = Address.parse(controllerStr);
					if (cAddr == null) {
						return toolError("Invalid controller address: " + controllerStr);
					}
					controllerCVM = "#" + cAddr.longValue();
				}

				// Build CVM source using deploy pattern
				String source;
				if (controllerCVM != null) {
					source = "(deploy '(do (set-controller " + controllerCVM + ") (set-key 0x" + accountKey.toHexString() + ")))";
				} else {
					source = "(create-account 0x" + accountKey.toHexString() + ")";
				}

				// Add faucet transfer if requested
				if (faucetAmount != null) {
					long max = restServer.getFaucetMax();
					long amt = Math.min(faucetAmount, max);
					source = "(let [addr " + source + "] (transfer addr " + amt + ") addr)";
				}

				Result r = faucetClient.transactSync(source);
				if (r.isError()) {
					return toolResult(r);
				}

				Address address = (Address)r.getValue();
				AMap<AString, ACell> out = Maps.of(
					"address", CVMLong.create(address.longValue())
				);
				ACell info = r.getInfo();
				if (info != null) out = out.assoc(KEY_INFO, info);
				return toolSuccess(out);
			} catch (Exception e) {
				return toolError("Account creation failed: " + e.getMessage());
			}
		}
	}
	
	/**
	 * Resolves an optional token address from arguments. Returns null for CVM native coin.
	 */
	private Address resolveTokenAddress(ACell tokenCell) {
		if (tokenCell == null) return null;
		String tokenStr = tokenCell.toString();
		if (tokenStr.isEmpty() || "nil".equals(tokenStr)) return null;
		return Address.parse(tokenStr);
	}

	private class GetBalanceTool extends McpTool {
		GetBalanceTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/getBalance.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				Address address = resolveAddress(arguments.get(ARG_ADDRESS));
				if (address == null) {
					return toolError("getBalance requires 'address'");
				}

				Address token = resolveTokenAddress(arguments.get(ARG_TOKEN));

				Convex convex = restServer.getConvex();
				String source;
				if (token == null) {
					source = "(balance " + address + ")";
				} else {
					source = "(@convex.fungible/balance " + token + " " + address + ")";
				}

				Result result = convex.querySync(source);
				if (result.isError()) {
					return toolResult(result);
				}

				AMap<AString, ACell> out = Maps.of(
					"address", CVMLong.create(address.longValue()),
					"balance", result.getValue()
				);
				if (token != null) {
					out = out.assoc(ARG_TOKEN, CVMLong.create(token.longValue()));
				}
				return toolSuccess(out);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return toolError("Tool call interrupted");
			}
		}
	}

	private class TransferTool extends McpTool {
		TransferTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/transfer.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString seedCell = RT.ensureString(arguments.get(ARG_SEED));
			if (seedCell == null) {
				return toolError("transfer requires 'seed' string");
			}
			AString addressCell = RT.ensureString(arguments.get(ARG_ADDRESS));
			if (addressCell == null) {
				return toolError("transfer requires 'address' string");
			}

			try {
				Address from = resolveAddress(addressCell);
				if (from == null) return toolError("Invalid origin address");

				Address to = resolveAddress(arguments.get(ARG_TO));
				if (to == null) return toolError("transfer requires 'to' address");

				CVMLong amountCell = CVMLong.parse(arguments.get(ARG_AMOUNT));
				if (amountCell == null) return toolError("transfer requires 'amount' number");
				long amount = amountCell.longValue();

				Blob seedBlob = Blob.parse(seedCell);
				if (seedBlob == null || seedBlob.count() != 32) {
					return toolError("Invalid seed: expected 32-byte hex string");
				}
				AKeyPair kp = AKeyPair.create(seedBlob);

				Address token = resolveTokenAddress(arguments.get(ARG_TOKEN));

				String source;
				if (token == null) {
					source = "(transfer " + to + " " + amount + ")";
				} else {
					source = "(@convex.fungible/transfer " + token + " " + to + " " + amount + ")";
				}

				try (Convex client = Convex.connect(server)) {
					client.setAddress(from);
					client.setKeyPair(kp);
					Result r = client.transactSync(source);
					if (r.isError()) return toolResult(r);

					AMap<AString, ACell> out = Maps.of(
						"value", r.getValue()
					);
					ACell info = r.getInfo();
					if (info != null) out = out.assoc(KEY_INFO, info);
					return toolSuccess(out);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return toolError("Tool call interrupted");
			} catch (Exception e) {
				return toolError("Transfer failed: " + e.getMessage());
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
				return toolError("getTransaction requires 'hash' string");
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

	// ===== State query and watch tools =====

	/** Parsed state path — shared between queryState and watchState. */
	private record StatePath(AVector<ACell> vec, ACell[] keys, String pathString) {}

	/** Result of resolving a state path — distinguishes "exists with null" from "not found". */
	private record StateResult(boolean exists, ACell value) {}

	/**
	 * Parse and validate a 'path' argument as a CVM vector.
	 * Shared validation for queryState and watchState tools.
	 * @return parsed path, or null if invalid
	 */
	private StatePath parsePath(AMap<AString, ACell> arguments) {
		AString pathCell = RT.ensureString(arguments.get(ARG_PATH));
		if (pathCell == null) return null;
		ACell parsed;
		try {
			parsed = Reader.read(pathCell.toString());
		} catch (Exception e) {
			return null;
		}
		AVector<ACell> pathVec = RT.ensureVector(parsed);
		if (pathVec == null || pathVec.isEmpty()) return null;
		int len = (int) pathVec.count();
		ACell[] keys = new ACell[len];
		for (int i = 0; i < len; i++) {
			keys[i] = pathVec.get(i);
		}
		return new StatePath(pathVec, keys, pathCell.toString());
	}

	/**
	 * Resolve a state path, distinguishing "path exists with null value" from "path not found".
	 * Uses RT.getIn to navigate to the parent, then containsKey for the final key.
	 */
	private StateResult resolveStatePath(ACell[] pathKeys) {
		ACell state = server.getState();
		ACell parent = (pathKeys.length == 1)
			? state
			: RT.getIn(state, java.util.Arrays.copyOf(pathKeys, pathKeys.length - 1));
		ACell lastKey = pathKeys[pathKeys.length - 1];
		if (parent instanceof ADataStructure<?> ds) {
			boolean exists = ds.containsKey(lastKey);
			return new StateResult(exists, exists ? RT.get(ds, lastKey) : null);
		}
		return new StateResult(false, null);
	}

	private class QueryStateTool extends McpTool {
		QueryStateTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/queryState.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			StatePath path = parsePath(arguments);
			if (path == null) {
				return toolError("queryState requires 'path' as a non-empty CVM vector, e.g. '[:accounts 0 :balance]'");
			}

			StateResult sr = resolveStatePath(path.keys);

			AMap<AString, ACell> out = Maps.of(
				"exists", CVMBool.of(sr.exists)
			);
			if (sr.exists) {
				long memSize = ACell.getMemorySize(sr.value);
				if (memSize <= QUERY_STATE_SIZE_THRESHOLD) {
					// Two representations of the same value, matching REST API convention:
					// - "value": JSON-friendly form (numbers, strings, arrays, objects)
					// - "result": CVM printed form preserving type info that JSON loses
					//   e.g. Address #42 → JSON number 42 vs CVM string "#42"
					out = out.assoc(KEY_VALUE, sr.value);
					out = out.assoc(Strings.create("result"), RT.print(sr.value));
				} else {
					out = out.assoc(Strings.create("size"), CVMLong.create(memSize));
				}
			}
			return toolSuccess(out);
		}
	}

	private class WatchStateTool extends McpTool {
		WatchStateTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/watchState.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			StatePath path = parsePath(arguments);
			if (path == null) {
				return toolError("watchState requires 'path' as a non-empty CVM vector, e.g. '[:accounts 0 :balance]'");
			}

			// Require active McpConnection via session ID
			Context ctx = McpServer.getCurrentContext();
			String sessionId = (ctx != null) ? ctx.header(HEADER_SESSION_ID) : null;
			McpConnection conn = (sessionId != null) ? connections.get(sessionId) : null;
			if (conn == null) {
				return toolError("watchState requires an active GET /mcp stream");
			}

			// Enforce per-connection watch limit
			if (conn.watches.size() >= MAX_WATCHES_PER_CONNECTION) {
				return toolError("Watch limit exceeded (max " + MAX_WATCHES_PER_CONNECTION + " per connection)");
			}

			// Resolve current value via RT.getIn (may be null — we watch for it to appear)
			ACell currentValue = stateWatcher.resolveValue(path.keys);
			Hash currentHash = Hash.get(currentValue);

			// Register watch on the connection
			String watchId = conn.addWatch(path.keys, path.vec, path.pathString, currentHash);
			stateWatcher.ensureRunning();

			// Return initial state
			AMap<AString, ACell> out = Maps.of(
				"watchId", watchId
			);
			long memSize = ACell.getMemorySize(currentValue);
			if (memSize <= ConvexStateWatcher.VALUE_SIZE_THRESHOLD) {
				out = out.assoc(Strings.create("value"), RT.print(currentValue));
			}
			return toolSuccess(out);
		}
	}

	private class UnwatchStateTool extends McpTool {
		UnwatchStateTool() {
			super(McpTool.loadMetadata("convex/restapi/mcp/tools/unwatchState.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString watchIdCell = RT.ensureString(arguments.get(ARG_WATCH_ID));
			AString pathCell = RT.ensureString(arguments.get(ARG_PATH));
			if (watchIdCell == null && pathCell == null) {
				return toolError("unwatchState requires 'watchId' or 'path'");
			}

			// Find connection via session ID
			Context ctx = McpServer.getCurrentContext();
			String sessionId = (ctx != null) ? ctx.header(HEADER_SESSION_ID) : null;
			McpConnection conn = (sessionId != null) ? connections.get(sessionId) : null;
			if (conn == null) {
				return toolSuccess(Maps.of("removed", CVMLong.ZERO));
			}

			long removed;
			if (watchIdCell != null) {
				removed = conn.removeWatch(watchIdCell.toString()) ? 1 : 0;
			} else {
				ACell parsed;
				try {
					parsed = Reader.read(pathCell.toString());
				} catch (Exception e) {
					return toolError("Failed to parse path: " + e.getMessage());
				}
				AVector<ACell> prefixVec = RT.ensureVector(parsed);
				if (prefixVec == null) {
					return toolError("path must be a CVM vector");
				}
				removed = conn.removeWatchesByPathPrefix(prefixVec);
			}
			return toolSuccess(Maps.of("removed", CVMLong.create(removed)));
		}
	}

	// ===== Convex state watcher =====

	/**
	 * Convex-specific state watcher. Polls CVM global state and pushes
	 * notifications to McpConnections when watched paths change.
	 *
	 * <p>Daemon virtual thread. Starts on first watch, exits when no watches remain.</p>
	 */
	private class ConvexStateWatcher {
		static final long POLL_INTERVAL_MS = 1000;
		static final long VALUE_SIZE_THRESHOLD = 1024;

		private volatile Thread thread;
		private volatile boolean running;

		synchronized void ensureRunning() {
			if (running) return;
			running = true;
			thread = Thread.ofVirtual().name("convex-state-watcher").start(this::pollLoop);
		}

		void shutdown() {
			running = false;
			Thread t = thread;
			if (t != null) t.interrupt();
		}

		ACell resolveValue(ACell[] path) {
			return RT.getIn(server.getState(), path);
		}

		private void pollLoop() {
			try {
				while (running) {
					if (!hasAnyWatches()) break;
					try {
						checkAllConnections();
					} catch (Exception e) {
						log.debug("Error in state watcher poll", e);
					}
					Thread.sleep(POLL_INTERVAL_MS);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				running = false;
				thread = null;
			}
		}

		private boolean hasAnyWatches() {
			for (McpConnection conn : connections.values()) {
				if (conn.hasWatches()) return true;
			}
			return false;
		}

		private void checkAllConnections() {
			for (McpConnection conn : connections.values()) {
				if (conn.isClosed() || !conn.hasWatches()) continue;
				for (StateWatcher.WatchEntry entry : conn.watches.values()) {
					try {
						ACell value = resolveValue(entry.path);
						Hash currentHash = Hash.get(value);
						if (!currentHash.equals(entry.lastHash)) {
							entry.lastHash = currentHash;
							notifyChange(conn, entry, value);
						}
					} catch (Exception e) {
						log.debug("Error checking watch {}", entry.watchId, e);
					}
				}
			}
		}

		private void notifyChange(McpConnection conn, StateWatcher.WatchEntry entry, ACell newValue) {
			var params = Maps.of(
				"watchId", entry.watchId,
				"path", entry.pathString,
				"changed", CVMBool.TRUE
			);

			long memSize = ACell.getMemorySize(newValue);
			if (memSize <= VALUE_SIZE_THRESHOLD) {
				params = params.assoc(Strings.create("value"), RT.print(newValue));
			}

			var notification = Maps.of(
				"jsonrpc", "2.0",
				"method", "notifications/stateChanged",
				"params", params
			);

			String json = JSON.print(notification).toString();
			try {
				conn.sendEvent("message", json);
			} catch (Exception e) {
				log.debug("Failed to send watch notification", e);
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
		Context ctx = McpServer.getCurrentContext();
		if (ctx == null) return null;
		return AuthMiddleware.getIdentity(ctx);
	}

}