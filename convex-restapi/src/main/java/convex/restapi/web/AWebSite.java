package convex.restapi.web;

import static j2html.TagCreator.a;
import static j2html.TagCreator.div;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.head;
import static j2html.TagCreator.header;
import static j2html.TagCreator.img;
import static j2html.TagCreator.link;
import static j2html.TagCreator.nav;
import static j2html.TagCreator.text;
import static j2html.TagCreator.title;

import convex.core.cvm.Address;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import io.javalin.Javalin;
import j2html.tags.DomContent;

public abstract class AWebSite extends ABaseAPI {
	public AWebSite(RESTServer restServer) {
		super(restServer);

	}
	

	public DomContent topBar() {
		return header(
			div(
				a(img().withSrc("/Convex.png").withAlt("Convex").withStyle("height: 40px; margin: 6px;"))
					.withHref("/"),
				h1("Convex"),
				div().withStyle("flex-grow: 1;"),
				nav(
					a("Peer").withHref("/"),
					a("Explorer").withHref("/explorer/")
				).withStyle("display: flex; gap: 1em;") // gap between nav items
			).withStyle("display: flex; align-items: stretch; gap: 1em; margin: 1em;")
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

}
