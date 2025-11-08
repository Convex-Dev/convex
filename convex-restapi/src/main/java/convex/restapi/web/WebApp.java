package convex.restapi.web;

import static j2html.TagCreator.*;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.core.cvm.State;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
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
		
		State state=server.getPeer().getConsensusState();
		long accountCount=state.getAccounts().count();
		long issuedSupply=state.computeSupply();
		
		AMap<Keyword,ACell> statusMap=server.getStatusMap();
		returnPage(ctx,"Convex Peer Server",
			tag("hgroup").with(
				h6("Convex peer server at: "+host),
				p("This is an operational Convex peer, a running consensus node on a Convex network. Use this server to interact with the Convex network, deploy smart contracts, query chain states, and monitor transactions in real-time.")
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
		StringBuilder sb=new StringBuilder();
		sb.append("# Convex Peer Server");
			
		ctx.result(sb.toString());
		ctx.contentType("text/plain");
		ctx.status(200);
	}

}
