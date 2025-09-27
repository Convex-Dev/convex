package convex.restapi.web;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.div;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.html;
import static j2html.TagCreator.p;

import convex.core.util.Utils;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.DomContent;

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
		returnPage(ctx,"Convex Peer Server",
			div(
				p("Version: "+Utils.getVersion()),
				p("Host: "+ABaseAPI.getExternalBaseUrl(ctx, null)),
				p(a("Explorer").withHref("explorer"))
			)
		);
	}
	
	protected void missingPage(Context ctx) { 
		String type=ctx.header("Accept");
		if ((type!=null)&&type.contains("html")) {
			ctx.header("Content-Type", "text/html");	
			DomContent content= html(
				makeHeader("404: Not Found: "+ctx.path()),
				body(
					topBar(),
					h1("404: not found: "+ctx.path()),
					p("This is not the page you are looking for."),
					a("Go back to index").withHref("/index.html"),
					footerBlock()
					
				)
			);
			ctx.result(content.render());
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
