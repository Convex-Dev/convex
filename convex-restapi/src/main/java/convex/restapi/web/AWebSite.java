package convex.restapi.web;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.button;
import static j2html.TagCreator.code;
import static j2html.TagCreator.div;
import static j2html.TagCreator.footer;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.h4;
import static j2html.TagCreator.head;
import static j2html.TagCreator.header;
import static j2html.TagCreator.hr;
import static j2html.TagCreator.html;
import static j2html.TagCreator.img;
import static j2html.TagCreator.each;
import static j2html.TagCreator.link;
import static j2html.TagCreator.main;
import static j2html.TagCreator.nav;
import static j2html.TagCreator.ul;
import static j2html.TagCreator.li;
import static j2html.TagCreator.pre;
import static j2html.TagCreator.rawHtml;
import static j2html.TagCreator.small;
import static j2html.TagCreator.text;
import static j2html.TagCreator.title;

import convex.core.cvm.Address;
import java.util.Locale;
import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.text.Text;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import io.javalin.http.Context;
import j2html.tags.DomContent;
import j2html.tags.specialized.CodeTag;
import j2html.tags.specialized.ImgTag;
import j2html.tags.specialized.PreTag;

/**
 * Base class for website pages.
 * 
 * Contains common utility / formatting functionality for consistency across site
 */
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
        returnPage(ctx,title,(String[][])null,content);
    }

    /**
     * Returns a structured page with optional breadcrumbs
     * @param ctx
     * @param title
     * @param breadcrumbs Pairs of {label, href}. Last entry should have null href for current page. If null, no breadcrumbs shown
     * @param content
     */
    public void returnPage(Context ctx, String title, String[][] breadcrumbs, DomContent... content) {
        DomContent result=html(
                makeHeader(title),
                body(
                    topBar(breadcrumbs),
                    contentBlock(content),
                    footerBlock()
                ).withStyle("min-height: 100vh; display: flex; flex-direction: column;")
            );
        ctx.result(result.render());
        ctx.contentType("text/html");
    }

	public DomContent topBar(String[][] breadcrumbs) {
		return header(
			div(
				div(
					a(img().withSrc("/Convex.png")
						.withAlt("Convex")
						.withStyle("width: 2.5em; height: 2.5em; margin: 0.5em;")).withHref("/")),
				h1("Convex").withStyle("height: 40px; margin-bottom: 0.2em;"),
				div().withStyle("flex-grow: 1;"), // spacer
				nav(
					topButton("Explorer","/explorer/"),
					topButton("Peers","/explorer/peers"),
					topButton("API","/swagger"),
					div()
				).withStyle("display: flex; gap: 0.5em; align-items: center;") // gap between nav items
			).withStyle("display: flex; align-items: center; gap: 0.5em"),
			breadcrumb(breadcrumbs)
		);
	}
	
	// Make a button suitable for the top bar
	private DomContent topButton(String text, String link) {
		return a(
				button(text).withStyle("padding:.25em .6em;")
		).withHref(link);
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

    protected DomContent makeButton(String text, String href) {
        return makeButton(text, href, true);
    }

    protected DomContent makeButton(String text, String href, boolean enabled) {
        if (enabled && (href!=null)) {
            return a(
                button(text)
                    .withClass("secondary")
                    .withStyle("padding:.25em .6em;font-size:.8em;margin-right:.25em;")
            ).withHref(href);
        } else {
            return button(text)
                .attr("disabled")
                .withClass("secondary")
                .withStyle("padding:.25em .6em;font-size:.8em;margin-right:.25em;");
        }
    }
	
	protected DomContent makeHeader(String title) {
		return head(
				title(title),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}

	/**
	 * Build standard pagination links: First, Prev, Next, End.
	 * Links are only included when applicable.
	 *
	 * @param ctx      Request context
	 * @param basePath Base path (e.g. "/explorer/accounts") without query
	 * @param offset   Current offset
	 * @param limit    Page size (items per page)
	 * @param total    Total number of items
	 * @return DomContent containing pagination buttons
	 */
	protected DomContent makePaginationLinks(Context ctx, String basePath, long offset, long limit, long total) {
		long end = Math.min(total, offset+limit);
	    if (total<=0) return div();
	
	    String firstLink = basePath+"?offset=0&limit="+limit;
	    long prevOffset=Math.max(0, offset-limit);
	    String prevLink = basePath+"?offset="+prevOffset+"&limit="+limit;
	    boolean hasPrev = offset>0;
	
	    String nextLink = basePath+"?offset="+end+"&limit="+limit;
	    long lastOffset = Math.max(0, total - limit);
	    String lastLink = basePath+"?offset="+lastOffset+"&limit="+limit;
	    boolean hasNext = end<total;
	
	    return div(
	        makeButton("<<", firstLink, hasPrev),
	        makeButton("<-", prevLink, hasPrev),
	        makeButton("->", nextLink, hasNext),
	        makeButton(">>", lastLink, hasNext),
	        makePaginationInfo(offset, limit, total)
	    );
	}
	

	/**
	 * Build a compact pagination info element like: "0 - 10 / 1567".
	 * Placed after pagination links.
	 */
	protected DomContent makePaginationInfo(long offset, long limit, long total) {
		long end=Math.min(total, offset+limit);
		return small(((offset>0)?((offset+1)+"-"):"")+(end)+" / "+total);
	}

	/**
	 * Build Prev/Next navigation for detail pages with compact info label.
	 * Example: "Block 3 / 678" next to buttons.
	 *
	 * @param ctx      Request context
	 * @param basePath Base path without trailing index (e.g. "/explorer/blocks")
	 * @param index    Current zero-based index
	 * @param total    Total count
	 * @param label    Label to display (e.g. "Block", "Tx")
	 * @return DomContent containing navigation buttons and info
	 */
	protected DomContent makeNavigationLinks(Context ctx, String basePath, long index, long total, String label) {
	    if (total<=0) return div();
	    String prevLink = basePath+"/"+(index-1);
	    String nextLink = basePath+"/"+(index+1);
	    boolean hasPrev = index>0;
	    boolean hasNext = index<total-1;
	    return div(
	        makeButton("<", prevLink, hasPrev),
	        makeButton(">", nextLink, hasNext),
	        small(label+" "+index+" / "+total)
	    );
	}

	
	/**
	 * Show a formatted CVM balance
	 * @param bal
	 * @return
	 */
	protected DomContent showBalance(long bal) {
		String s=Text.toFriendlyNumber(bal/1000000000);
		s=Text.leftPad(s, 13);
		return pre(rawHtml(s+"."),small(String.format("%09d", bal%1000000000))).withStyle("margin: 0; width: min-content;");
	}

	/**
	 * Show a percentage value with fixed-width alignment, formatted like " 18.00 %"
	 * @param percent Percentage value (0-100)
	 * @return Monospace-formatted DomContent
	 */
	protected DomContent showPercent(double percent) {
		String s=String.format(Locale.US, "%6.2f %%", percent);
		return pre(s).withStyle("margin: 0; width: min-content;");
	}
	
	/**
	 * Show a value in formatted CVX format
	 * @param trans
	 * @return
	 */
	protected CodeTag showCVX(ACell trans) {
		return wrappedCode(RT.print(trans).toString());
	}
	
	protected String getVersion() {
		try {
			return convex.core.util.Utils.getVersion();
		} catch (Exception e) {
			return "Unknown";
		}
	}

	/**
	 * Construct a breadcrumb navigation line.
	 * Provide pairs of (label, href) for intermediate links, and the last label with null href for the current page.
	 * Example: breadcrumb(new String[][] {{"Home", "/"}, {"Services", "/services"}, {"Design", null}})
	 */
	protected DomContent breadcrumb(String[][] items) {
		if (items==null) return div();
		return nav(
			ul(
				each(java.util.Arrays.asList(items), it -> {
					String label = it[0];
					String href = (it.length>1)?it[1]:null;
					if (href==null) {
						return li(text(label));
					} else {
						return li(a(label).withHref(href));
					}
				})
			).withStyle("margin-left: 0.5em")
		).attr("aria-label", "breadcrumb");
	}
	
	// Silly helper function
	protected static String[] sa(String... strings) {
		return strings;
	}
	
	/**
	 * Create a div containing an identicon and corresponding data value
	 * @param data The data to create identicon for
	 * @return DomContent div with identicon and code
	 */
	public static DomContent showID(AArrayBlob data) {			
		return div(
			identicon(data),
			div(
				showHex(data).withStyle("align-self: center; white-space: nowrap; margin: 0; text-overflow: ellipsis; overflow: hidden;")
			).withStyle("min-width: 0;")
		).withStyle("display: flex; flex-direction: row; align-items: center; gap: 0.5em; max-width: 100%; min-width: 0;");
	}
	
	/**
	 * Create a div containing an identicon and corresponding data value
	 * @param data The data to create identicon for
	 * @return DomContent div with identicon and code
	 */
	public static PreTag showHex(AArrayBlob data) {
		String dataString = (data==null)?"nil":data.toString();
		
		return pre(dataString).withStyle("align-self: center; white-space: normal; margin: 0; word-break:break-all; overflow-wrap:break-word;");
	}
	
	public static CodeTag wrappedCode(String value) {
		return code(value).withStyle("display: inline-block;white-space: normal;max-width:100%; word-break:break-all; overflow-wrap:break-word;");
	}

	protected static ImgTag identicon(String hexString) {
		if (hexString==null) hexString="0x";
		String identiconUrl = "/identicon/" + hexString;
		ImgTag identicon = img().withSrc(identiconUrl).withAlt("Identicon for " + hexString).withStyle("height: 21; width: 21; image-rendering: pixelated; margin: 2px; flex-shrink: 0;");
		return identicon;
	}
	
	protected static ImgTag identicon(ABlob blob) {
		if (blob==null) return identicon((String)null);
		return identicon(blob.toHexString());
	}
}
