package convex.restapi.api;

import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.cvm.Peer;
import convex.core.data.AVector;
import convex.core.data.SignedData;
import convex.peer.Server;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import j2html.tags.DomContent;

import static j2html.TagCreator.*;

import java.util.ArrayList;

/**
 * On-chain explorer API 
 */
public class ExplorerAPI extends ABaseAPI {

	public ExplorerAPI(RESTServer restServer) {
		super(restServer);

	}
	
	private static final String ROUTE = "/explorer/";
	
	private static final long DEFAULT_LIMIT = 100;
	private static final DomContent[] EMPTY_DOM_CONTENT= new DomContent[0];


	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;
		app.get(prefix+"blocks", this::showBlocks);
	}
	
	private DomContent makeHeader(String title) {
		return head(
				title(title),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}
	
	public void showBlocks(Context ctx) {
		Server s=restServer.getServer();
		
		// Snapshot of Peer data
		Peer peer=s.getPeer();
		Order o=peer.getPeerOrder();
		AVector<SignedData<Block>> blocks=o.getBlocks();
		long nblocks=blocks.count();
		
		long start=0;
		long end=0;
		try {
			String offsetParam=ctx.queryParam("offset");
			long offset=(offsetParam==null)?0:Integer.parseInt(offsetParam);
			if (offset<0) throw new BadRequestResponse("Negative offset parameter: "+offset);
			if (offset>nblocks) throw new BadRequestResponse("Offset out of range: "+offset);
			
			String limitParam=ctx.queryParam("limit");
			long limit=(offsetParam==null)?DEFAULT_LIMIT:Integer.parseInt(limitParam);

			if (limit<0) throw new BadRequestResponse("Negative limit parameter: "+limit);

			start=offset;
			end=Math.min(nblocks, offset+limit);
		} catch (Exception e) {
			throw new BadRequestResponse("Invalid query parameter");
		}
		
		ArrayList<DomContent[]> rows=new ArrayList<>();
		for (long i=start; i<end; i++) {
			SignedData<Block> sd=blocks.get(i);
			rows.add(new DomContent[] {
				td(Long.toString(i)),	
				td(sd.getAccountKey().toString())	
			});
		}
		
		DomContent result=html(
			makeHeader("Blocks"),
			body(
				table(
					thead(tr(th("Block"),th("Peer"))),
					tbody(
						each(rows,row->{return tr(row);})
					)
				)
			)
		);
		
		ctx.contentType("text/html");
		ctx.result(result.render());
	}

}
