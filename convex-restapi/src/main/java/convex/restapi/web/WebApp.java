package convex.restapi.web;

import static j2html.TagCreator.*;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.core.cvm.State;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import convex.restapi.mcp.McpAPI;
import io.javalin.Javalin;
import io.javalin.http.Context;

public class WebApp extends AWebSite {

	public WebApp(RESTServer restServer) {
		super(restServer);
	}
	
	public void addRoutes(Javalin app) {
		app.get("/index.html", this::indexPage);
		app.get("/", this::indexPage);
		app.get("/404.html", this::missingPage);
		app.get("/llms.txt",this::llmsTxt);
		
		app.error(404, this::missingPage);
	}
	
	private void indexPage(Context ctx) {
		String host=ABaseAPI.getExternalBaseUrl(ctx, null);
		String peerKeyHex = server.getPeer().getPeerKey().toHexString();
		String peerLink = ABaseAPI.getExternalBaseUrl(ctx, "/explorer/peers/"+peerKeyHex);

		State state=server.getState();
		long accountCount=state.getAccounts().count();
		long issuedSupply=state.computeSupply();

		AMap<Keyword,ACell> statusMap=server.getStatusMap();

		// MCP section content
		McpAPI mcp = restServer.getMcpAPI();
		String mcpEndpoint = host + "/mcp";
		int toolCount = 0;
		if (mcp != null) {
			AVector<AMap<AString, ACell>> tools = mcp.getToolMetadata();
			if (tools != null) toolCount = (int) tools.count();
		}

		returnPage(ctx,"Convex Peer Server",
			tag("hgroup").with(
				h6("Convex peer server at: "+host),
				p("This is an operational Convex peer, a running consensus node on a Convex network. Use this server to interact with the Convex network, deploy smart contracts, query chain states, and monitor transactions in real-time."),
				small(em("The Engine of the Agentic Economy"))
			),
			article(
				details(
				    summary("Peer Info"),
				    table(
				    	tr(td("Version"),td(code(Utils.getVersion()))),
				    	tr(td("Peer Port"),td(code(getHostname(ctx)+":"+server.getPort()))),
				    	tr(td("Host"),td(code(host))),
				    	tr(td("Peer Key"),td(a(showID(server.getPeer().getPeerKey())).withHref(peerLink)))
				    )
				).attr("open", true)
			),
			article(
				details(
					summary("Network Info"),
					table(
						tr(td("Genesis Hash"),td(code(server.getPeer().getGenesisState().getHash().toString()))),
						tr(td("Accounts"),td(code(Long.toString(accountCount)))),
						tr(td("Issued CVM"),td(showBalance(issuedSupply)))
					)
				).attr("open", true)
			),
			article(
				details(
					summary("Agent Integration (MCP)"),
					p(text("This peer provides "), a("Model Context Protocol").withHref("https://modelcontextprotocol.io/"), text(" access for AI agents and LLMs.")),
					table(
						tr(td("MCP Endpoint"),td(code(mcpEndpoint)),td("POST JSON-RPC")),
						tr(td("Tools Available"),td(code(Integer.toString(toolCount))),td(a("View tools").withHref("/explorer/mcp"))),
						tr(td("Agent Docs"),td(a("/llms.txt").withHref("/llms.txt")),td("Machine-readable documentation"))
					),
					p(small("Agents can query state, prepare transactions, sign data, and interact with the network programmatically."))
				).attr("open", true)
			),
			article(
				details(
				    summary("Status"),
				    table(
				    	each(statusMap.entryVector(),me->{
				    		return tr(
				    				td(me.getKey().getName().toString()),
				    				td(code(RT.toString(me.getValue()))));
				    	})
				    )
				)
			)
		);
	}
	
	protected void missingPage(Context ctx) { 
		String type=ctx.header("Accept");
		if ((type!=null)&&type.contains("html")) {
			ctx.header("Content-Type", "text/html");	
			returnPage(ctx, "404: Not Found: "+ctx.path(),
				h1("404: not found: "+ctx.path()),
				p("This is not the page you are looking for."),
				a("Go back to index").withHref("/index.html")
			);
		} else {
			ctx.result("404 Not found: "+ctx.path());
		}
		ctx.status(404);
	}

	protected void llmsTxt(Context ctx) {
		String host = ABaseAPI.getExternalBaseUrl(ctx, null);
		String peerKey = server.getPeer().getPeerKey().toHexString();

		StringBuilder sb = new StringBuilder();
		sb.append("# Convex Peer Server\n\n");
		sb.append("> The Engine of the Agentic Economy\n\n");

		sb.append("This is a sovereign Convex peer at " + host + " — a full node of the Convex network,\n");
		sb.append("a globally distributed, cryptographically secure, and financially open economic operating system.\n\n");

		sb.append("This peer is live, on-chain, and economically active 24/7. It holds real Convex Coins,\n");
		sb.append("can execute transactions, deploy smart contracts, transfer value, and participate in\n");
		sb.append("governance — all without human intervention.\n\n");

		// Peer info
		sb.append("## Peer Info\n\n");
		sb.append("- Peer Key: " + peerKey + "\n");
		sb.append("- API Base: " + host + "\n");
		sb.append("- MCP Endpoint: " + host + "/mcp (POST JSON-RPC)\n");
		sb.append("- REST API: " + host + "/api/v1/\n");
		sb.append("- Explorer: " + host + "/explorer/\n\n");

		// MCP Tools section
		McpAPI mcp = restServer.getMcpAPI();
		if (mcp != null) {
			AVector<AMap<AString, ACell>> toolMetadata = mcp.getToolMetadata();
			if (toolMetadata != null && toolMetadata.count() > 0) {
				sb.append("## MCP Tools Available\n\n");
				sb.append("This peer exposes Model Context Protocol tools for AI agents:\n\n");
				long toolCount = toolMetadata.count();
				for (int i = 0; i < toolCount; i++) {
					AMap<AString, ACell> metadata = toolMetadata.get(i);
					AString nameCell = RT.ensureString(metadata.get(Strings.create("name")));
					AString descCell = RT.ensureString(metadata.get(Strings.create("description")));
					String name = (nameCell != null) ? nameCell.toString() : "unknown";
					String desc = (descCell != null) ? descCell.toString() : "";
					sb.append("- **" + name + "**: " + desc + "\n");
				}
				sb.append("\n");
			}
		}

		// Capabilities
		sb.append("## Agent Capabilities\n\n");
		sb.append("1. **Native money, no wrappers**: Convex Coins are real digital cash — no ERC-20, no bridges.\n");
		sb.append("   Agents can hold, earn, spend, and stake value with zero counterparty risk.\n\n");
		sb.append("2. **Persistent identity & memory**: Agents get permanent Addresses (e.g. #1465) that survive\n");
		sb.append("   across sessions. On-chain key-value storage acts as infinite tamper-proof memory.\n\n");
		sb.append("3. **Autonomous execution**: Deploy agents as Convex actors that run forever.\n");
		sb.append("   React to on-chain events and compose with any other agent permissionlessly.\n\n");
		sb.append("4. **Zero-fee reads**: Query the entire world state for free. Buy some Convex Coins,\n");
		sb.append("   execute millions of transactions.\n\n");

		// Quick start for agents
		sb.append("## Quick Start for Agents\n\n");
		sb.append("```bash\n");
		sb.append("# Query (free, read-only)\n");
		sb.append("curl -X POST " + host + "/api/v1/query \\\n");
		sb.append("  -H 'Content-Type: application/json' \\\n");
		sb.append("  -d '{\"source\": \"(+ 1 2)\", \"address\": \"#11\"}'\n\n");
		sb.append("# MCP Tool Call\n");
		sb.append("curl -X POST " + host + "/mcp \\\n");
		sb.append("  -H 'Content-Type: application/json' \\\n");
		sb.append("  -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"query\",\"arguments\":{\"source\":\"*balance*\"}},\"id\":1}'\n");
		sb.append("```\n\n");

		// References
		sb.append("## References\n\n");
		sb.append("- [Convex World](https://convex.world) — The Global Economic Operating System\n");
		sb.append("- [Documentation](https://docs.convex.world) — Build Autonomous Agents\n");
		sb.append("- [Explorer](" + host + "/explorer/) — Watch Agents Trade and Govern\n");
		sb.append("- [GitHub](https://github.com/Convex-Dev) — Open Source Runtime & SDKs\n");
		sb.append("- [Discord](https://discord.com/invite/xfYGq4CT7v) — Join the Builder Community\n");

		ctx.result(sb.toString());
		ctx.contentType("text/plain");
		ctx.status(200);
	}

}
