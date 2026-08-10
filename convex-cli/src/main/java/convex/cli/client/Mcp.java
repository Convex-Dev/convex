package convex.cli.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import convex.api.Convex;
import convex.cli.CLIError;
import convex.cli.ExitCodes;
import convex.core.Result;
import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.restapi.mcp.McpPrompt;
import convex.restapi.mcp.McpProtocol;
import convex.restapi.mcp.McpServer;
import convex.restapi.mcp.McpTool;
import picocli.CommandLine.Command;

/**
 * Convex mcp sub command: MCP (Model Context Protocol) server on stdin/stdout
 *
 *		convex.mcp
 *
 * Speaks newline-delimited JSON-RPC on stdin/stdout as per the MCP stdio
 * transport, so local MCP clients (e.g. AI agent tools) can run Convex queries
 * and transactions. Reuses the MCP protocol implementation from convex-restapi
 * with a client-side tool set: tools execute against whatever Convex instance
 * is targeted, and transactions are signed locally.
 */
@Command(name="mcp",
	mixinStandardHelpOptions=true,
	description="Run an MCP (Model Context Protocol) server on stdin/stdout for local MCP clients such as AI agents. Tools execute against an ephemeral local instance unless a peer is targeted with --host; transactions are signed with the local key configured via --address and --key. With --query, only read-only tools are offered.")
public class Mcp extends AEvalCommand {

	private static final String TOOLS_PATH = "convex/cli/mcp/tools/";

	private static final StringShort ARG_SOURCE = Strings.intern("source");
	private static final StringShort ARG_ADDRESS = Strings.intern("address");
	private static final StringShort ARG_TOKEN = Strings.intern("token");
	private static final StringShort ARG_NAME = Strings.intern("name");

	private static final StringShort KEY_VALUE = Strings.intern("value");
	private static final StringShort KEY_ERROR_CODE = Strings.intern("errorCode");
	private static final StringShort KEY_INFO = Strings.intern("info");

	private static final Pattern CNS_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9._\\-]*");

	private Convex convex;

	@Override
	public void execute() throws InterruptedException {
		try (Convex c=connectMcp()) {
			convex=c;
			StdioMcpServer server=new StdioMcpServer(Maps.of(
				"name","convex-cli",
				"title","Convex CLI MCP server",
				"version",Utils.getVersion()));
			registerTools(server);
			inform("Convex MCP server on stdio: target "+targetDescription()+", address "+convex.getAddress()
					+(queryMode?", read-only":"")+". Reading JSON-RPC from stdin.");
			runLoop(server);
		}
	}

	/**
	 * Connects to the targeted Convex instance. Unlike eval/repl, a remote
	 * target does not require an address or key up front: read-only tools work
	 * without them, and the transact tool reports what is missing at call time.
	 * Any key passwords are prompted for here, before the stdio loop starts.
	 */
	private Convex connectMcp() throws InterruptedException {
		if (isLocalTarget()) return createLocalInstance();
		Convex c=clientConnect();
		Address a=addressMixin.getSpecifiedAddress();
		if (a!=null) c.setAddress(a);
		if (!queryMode&&(a!=null)&&(keyMixin.getPublicKey()!=null)) {
			ensureKeyPair(c);
		}
		return c;
	}

	private String targetDescription() {
		return isLocalTarget()?"local ephemeral instance":("peer "+peerMixin.getHostname());
	}

	private void registerTools(StdioMcpServer server) {
		server.registerTool(new QueryTool());
		server.registerTool(new GetBalanceTool());
		server.registerTool(new ResolveCNSTool());
		server.registerTool(new StatusTool());
		if (!queryMode) {
			server.registerTool(new TransactTool());
		}
		// Generic Convex reference material, shared with the peer MCP endpoint
		server.registerPrompt(new McpPrompt(McpPrompt.loadMetadata("convex/restapi/mcp/prompts/convex-guide.json")));
	}

	// ==================== stdio transport ====================

	private void runLoop(StdioMcpServer server) {
		BufferedReader br=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
		PrintWriter out=commandLine().getOut();
		while (true) {
			String line;
			try {
				line=br.readLine();
			} catch (IOException e) {
				throw new CLIError(ExitCodes.IOERR,"Unable to read standard input: "+e.getMessage(),e);
			}
			if (line==null) break; // end of input: client closed the pipe
			if (line.isBlank()) continue;

			ACell message;
			try {
				message=JSONReader.read(line);
			} catch (ParseException e) {
				emit(out,McpProtocol.protocolError(-32700,"Parse error"));
				continue;
			}
			ACell response=server.handleMessage(message);
			if (response!=null) emit(out,response);
		}
	}

	/**
	 * Emits one JSON-RPC message as a single line on stdout, per the MCP stdio
	 * transport framing
	 */
	private void emit(PrintWriter out, ACell response) {
		out.print(JSON.print(response).toString()+"\n");
		out.flush();
	}

	/**
	 * McpServer with the transport-independent JSON-RPC dispatch exposed for
	 * stdio use. HTTP-specific behaviour (origin validation, SSE, routes) is
	 * simply never invoked.
	 */
	private static class StdioMcpServer extends McpServer {
		StdioMcpServer(AMap<AString, ACell> serverInfo) {
			super(serverInfo);
		}

		/**
		 * Handles one parsed JSON-RPC message (single or batch).
		 * @return Response to send, or null for notifications
		 */
		ACell handleMessage(ACell message) {
			if (message instanceof AMap<?, ?> map) {
				if (McpProtocol.isNotification(map)) return null;
				return createResponse(map);
			} else if (message instanceof AVector<?> vector) {
				long n=vector.count();
				if (n==0) return McpProtocol.protocolError(-32600,"Invalid batch request (empty)");
				if (n>MAX_BATCH_SIZE) return McpProtocol.protocolError(-32600,"Batch too large (max "+MAX_BATCH_SIZE+")");
				AVector<AMap<AString, ACell>> responses=Vectors.empty();
				for (long i=0; i<n; i++) {
					ACell entry=vector.get(i);
					if (entry instanceof AMap<?, ?> batchMap) {
						if (!McpProtocol.isNotification(batchMap)) {
							responses=responses.conj(createResponse(batchMap));
						}
					} else {
						responses=responses.conj(McpProtocol.protocolError(-32600,"Invalid Request"));
					}
				}
				return responses.isEmpty()?null:responses;
			}
			return McpProtocol.protocolError(-32600,"Request must be a JSON object or array");
		}
	}

	// ==================== Tool helpers ====================

	/**
	 * Builds an MCP tool result from a CVM Result, mirroring the peer MCP
	 * endpoint's result shape (value / errorCode / info)
	 */
	private static AMap<AString, ACell> toolResult(Result result) {
		AMap<AString, ACell> structured=McpProtocol.EMPTY_MAP;
		ACell value=result.getValue();
		if (value!=null) structured=structured.assoc(KEY_VALUE,value);
		ACell errorCode=result.getErrorCode();
		if (errorCode!=null) structured=structured.assoc(KEY_ERROR_CODE,errorCode);
		ACell info=result.getInfo();
		if (info!=null) structured=structured.assoc(KEY_INFO,info);
		return McpProtocol.protocolResult(McpProtocol.buildMcpResult(structured,result.isError()));
	}

	/**
	 * Parses an address argument: accepts a number or a string like "#13" or "13".
	 * @return Address, or null if the argument is absent
	 * @throws IllegalArgumentException if present but not a valid address
	 */
	private static Address parseAddressArg(ACell cell) {
		if (cell==null) return null;
		if (cell instanceof CVMLong l) return Address.create(l.longValue());
		Address a=Address.parse(RT.str(cell).toString());
		if (a==null) throw new IllegalArgumentException("Invalid address: "+cell);
		return a;
	}

	// ==================== Tools ====================

	private class QueryTool extends McpTool {
		QueryTool() {
			super(McpTool.loadMetadata(TOOLS_PATH+"query.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString source=RT.ensureString(arguments.get(ARG_SOURCE));
			if (source==null) return McpProtocol.toolError("query requires 'source' string");
			try {
				ACell form=readForm(source.toString());
				if (form==null) return McpProtocol.toolError("No form to evaluate");
				Address address=parseAddressArg(arguments.get(ARG_ADDRESS));
				if (address==null) address=convex.getAddress();
				return toolResult(convex.querySync(form,address));
			} catch (ParseException e) {
				return McpProtocol.toolError("Parse error: "+e.getMessage());
			} catch (IllegalArgumentException e) {
				return McpProtocol.toolError(e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return McpProtocol.toolError("Query interrupted");
			}
		}
	}

	private class TransactTool extends McpTool {
		TransactTool() {
			super(McpTool.loadMetadata(TOOLS_PATH+"transact.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString source=RT.ensureString(arguments.get(ARG_SOURCE));
			if (source==null) return McpProtocol.toolError("transact requires 'source' string");
			if (convex.getKeyPair()==null) {
				return McpProtocol.toolError("No signing key configured. Restart 'convex mcp' with --address and --key "
						+"(plus --keypass or CONVEX_KEY_PASSWORD) to enable transactions.");
			}
			try {
				ACell form=readForm(source.toString());
				if (form==null) return McpProtocol.toolError("No form to evaluate");
				return toolResult(convex.transactSync(form));
			} catch (ParseException e) {
				return McpProtocol.toolError("Parse error: "+e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return McpProtocol.toolError("Transaction interrupted");
			}
		}
	}

	private class GetBalanceTool extends McpTool {
		GetBalanceTool() {
			super(McpTool.loadMetadata(TOOLS_PATH+"getBalance.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				Address address=parseAddressArg(arguments.get(ARG_ADDRESS));
				if (address==null) address=convex.getAddress();
				if (address==null) return McpProtocol.toolError("getBalance requires 'address'");
				Address token=parseAddressArg(arguments.get(ARG_TOKEN));

				String source=(token==null)
						?("(balance "+address+")")
						:("(@convex.fungible/balance "+token+" "+address+")");
				Result result=convex.querySync(readForm(source),convex.getAddress());
				if (result.isError()) return toolResult(result);

				AMap<AString, ACell> out=Maps.of(
					"address",address.longValue(),
					"balance",result.getValue());
				if (token!=null) out=out.assoc(ARG_TOKEN,CVMLong.create(token.longValue()));
				return McpProtocol.toolSuccess(out);
			} catch (IllegalArgumentException e) {
				return McpProtocol.toolError(e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return McpProtocol.toolError("Query interrupted");
			}
		}
	}

	private class ResolveCNSTool extends McpTool {
		ResolveCNSTool() {
			super(McpTool.loadMetadata(TOOLS_PATH+"resolveCNS.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			AString name=RT.ensureString(arguments.get(ARG_NAME));
			if (name==null) return McpProtocol.toolError("resolveCNS requires 'name' string");
			if (!CNS_NAME.matcher(name.toString()).matches()) {
				return McpProtocol.toolError("Invalid CNS name: "+name);
			}
			try {
				return toolResult(convex.querySync(readForm("(resolve "+name+")"),convex.getAddress()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return McpProtocol.toolError("Query interrupted");
			}
		}
	}

	private class StatusTool extends McpTool {
		StatusTool() {
			super(McpTool.loadMetadata(TOOLS_PATH+"status.json"));
		}

		@Override
		public AMap<AString, ACell> handle(AMap<AString, ACell> arguments) {
			try {
				Result result=convex.querySync(readForm("*timestamp*"),convex.getAddress());
				if (result.isError()) return toolResult(result);
				AMap<AString, ACell> out=Maps.of(
					"target",targetDescription(),
					"address",RT.str(convex.getAddress()),
					"signing",convex.getKeyPair()!=null,
					"timestamp",result.getValue());
				return McpProtocol.toolSuccess(out);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return McpProtocol.toolError("Query interrupted");
			}
		}
	}
}
