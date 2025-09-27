package convex.restapi.web;

import static j2html.TagCreator.*;

import convex.core.util.Utils;
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
		returnPage(ctx,"Convex Peer Server",
			h6("Welcome to the Convex peer server at: "+host),
			article(
				details(
				    summary("Peer Info"),
				    table(
				    	tr(td("Version"),td(Utils.getVersion())),
				    	tr(td("Peer Port"),td(restServer.getServer().getPort().toString())),
				    	tr(td("Host"),td(host))
				    )
				).attr("open", true)
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
