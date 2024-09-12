package convex.restapi;

import static j2html.TagCreator.*;

import java.util.List;

import convex.core.util.Utils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import j2html.tags.DomContent;
import j2html.tags.specialized.HtmlTag;

public class WebApp {

	protected RESTServer server;

	public WebApp(RESTServer restServer) {
		this.server=restServer;
	}

	public HtmlTag index() {
		return html(
			makeHeader(),
			body(
				h1("Convex Peer Server"),
				p("Version: "+Utils.getVersion()),
				p("This is the default page for a Convex Peer Server with REST API"),
				h3("Useful Link: "),
				makeLinks()
			)
		);
	}
	
	private void indexPage(Context ctx) {
		ctx.header("Content-Type", "text.html");
		ctx.status(200);
		ctx.result(index().render());
	}


	static final List<String[]> LINKS = List.of(
		sa("Open API documentation for this peer: ","Swagger API" ,"/swagger"),
		sa("Convex Website: ","https://convex.world", "https://convex.world"),
		sa("The Convex ","Project Discord", "https://discord.com/invite/xfYGq4CT7v")
	);
	
	private DomContent makeLinks() {
		return each(LINKS,a->{
			return li(join(a[0],a(a[1]).withHref(a[2])));
		});
	}

	// Silly helper function
	private static String[] sa(String... strings) {
		return strings;
	}

	private DomContent makeHeader() {
		return head(
				title("Convex Peer Server"),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}

	public void addRoutes(Javalin app) {
		app.get("/index.html", this::indexPage);
	}
}
