package convex.restapi;

import static j2html.TagCreator.a;
import static j2html.TagCreator.article;
import static j2html.TagCreator.aside;
import static j2html.TagCreator.body;
import static j2html.TagCreator.div;
import static j2html.TagCreator.each;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.h4;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.join;
import static j2html.TagCreator.link;
import static j2html.TagCreator.p;
import static j2html.TagCreator.title;

import java.util.List;

import convex.core.util.Utils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.DomContent;

public class WebApp {

	protected RESTServer server;

	public WebApp(RESTServer restServer) {
		this.server=restServer;
	}
	
	private void indexPage(Context ctx) {
		DomContent content= html(
				makeHeader("Convex Peer Server"),
				body(
					h1("Convex Peer Server"),
					aside(makeLinks()).withStyle("float: right"),
					p("Version: "+Utils.getVersion()),
					p("This is the default page for a Convex Peer Server running the REST API")
				)
			);
		ctx.result(content.render());
		ctx.header("Content-Type", "text/html");
		ctx.status(200);
	}
	
	protected void missingPage(Context ctx) { 
		DomContent content= html(
				makeHeader("404: Not Found"),
				body(
					h1("404: not found: "+ctx.path()),
					p("This is not the page you are looking for."),
					a("Go back to index").withHref("/index.html"),
					makeLinks()
					
				)
			);
		ctx.result(content.render());
		ctx.header("Content-Type", "text/html");
		ctx.status(404);
	}

	static final List<String[]> LINKS = List.of(
		sa("Open API documentation for this peer: ","Swagger API" ,"/swagger"),
		sa("General information at the ","Convex Website", "https://convex.world"),
		sa("Chat with the community at the ","Convex Discord Server", "https://discord.com/invite/xfYGq4CT7v"),
		sa("Join the open source development: ","Convex-Dev", "https://github.com/Convex-Dev")
	);
	
	private DomContent makeLinks() {
		return article(
			h4("Useful links: "),
			each(LINKS,a->{
				return div(join(a[0],a(a[1]).withHref(a[2])));
			})
		); //.withClass("grid");
	}

	// Silly helper function
	private static String[] sa(String... strings) {
		return strings;
	}

	private DomContent makeHeader(String title) {
		return head(
				title(title),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}

	public void addRoutes(Javalin app) {
		app.get("/index.html", this::indexPage);
		app.get("/", this::indexPage);
		app.get("/404.html", this::missingPage);
		
		// app.error(404, this::missingPage);
	}
}
