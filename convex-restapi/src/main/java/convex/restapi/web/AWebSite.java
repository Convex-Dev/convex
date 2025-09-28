package convex.restapi.web;

import static j2html.TagCreator.*;

import convex.core.cvm.Address;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import io.javalin.http.Context;
import j2html.tags.DomContent;

public abstract class AWebSite extends ABaseAPI {
	public AWebSite(RESTServer restServer) {
		super(restServer);

	}
	
	/**
	 * Returns a structured page for the web application
	 * @param ctx
	 * @param title
	 * @param content
	 */
	public void returnPage(Context ctx, String title, DomContent... content) {
		DomContent result=html(
				makeHeader(title),
				body(
					topBar(),
					contentBlock(content),
					// div().withStyle("flex-grow: 1"), // spacer
					footerBlock()
				).withStyle("min-height: 100vh; display: flex; flex-direction: column;")
			);
		
		ctx.result(result.render());
		ctx.contentType("text/html");
	}

	public DomContent topBar() {
		return header(
			div(
				div(
					a(img().withSrc("/Convex.png")
						.withAlt("Convex")
						.withStyle("height: 40px; margin: 0.5em;")).withHref("/")),
				h1("Convex").withStyle("height: 40px; margin-bottom: 0.2em;"),
				div().withStyle("flex-grow: 1;"),
				nav(
					a("Explorer").withHref("/explorer/"),
					a("Peer").withHref("/"),
					a("API").withHref("/swagger"),
					div()
				).withStyle("display: flex; gap: 1em; align-items: center;") // gap between nav items
			).withStyle("display: flex; align-items: center; gap: 1em")
		);
	}
	
	public DomContent contentBlock(DomContent... content) {
		return main(content).withStyle("padding: 1em; flex-grow: 1;"); // grow to fill screen
	}

	
	public DomContent footerBlock() {
		return footer(
			hr(),
			div(
				div(
					h4("Useful Links"),
					div(a("convex.world").withHref("https://convex.world")),
					div(a("Explorer").withHref("/explorer/")),
					div(a("Blocks").withHref("/explorer/blocks")),
					div(a("States").withHref("/explorer/states"))
				),
				div(
					h4("Resources"),
					div(a("Documentation").withHref("https://docs.convex.world")),
					div(a("GitHub - Convex Developers").withHref("https://github.com/Convex-Dev/convex")),
					div(a("Discord Community").withHref("https://discord.gg/convex"))
				)
			).withStyle("display: flex; justify-content: space-around; padding: 1em 0")
		);
	}
	
	DomContent showAddress(Address origin) {
		if (origin==null) return text("nil");
		return a(origin.toString()).withHref("/explorer/accounts/"+origin.longValue());
	}

	protected DomContent makeButton(String text, String prevLink) {
		return a(text).withHref(prevLink).withClass("button");
	}
	
	protected DomContent makeHeader(String title) {
		return head(
				title(title),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}
	

	
	protected String getVersion() {
		try {
			return convex.core.util.Utils.getVersion();
		} catch (Exception e) {
			return "Unknown";
		}
	}
	
	// Silly helper function
	protected static String[] sa(String... strings) {
		return strings;
	}
	


}
