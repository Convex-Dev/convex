package convex.restapi.api;

import static j2html.TagCreator.*;

import java.util.ArrayList;

import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.data.AVector;
import convex.core.data.SignedData;
import convex.peer.Server;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import j2html.tags.DomContent;

/**
 * On-chain explorer API 
 */
public class ExplorerAPI extends ABaseAPI {
	public ExplorerAPI(RESTServer restServer) {
		super(restServer);

	}
	
	private static final String ROUTE = "/explorer/";
	
	private static final long DEFAULT_LIMIT = 100;

	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;
		app.get(prefix, this::showExplorer);
		app.get(prefix+"blocks", this::showBlocks);
		app.get(prefix+"states", this::showStates);
	}
	
	private DomContent makeHeader(String title) {
		return head(
				title(title),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}
	
	/**
	 * Produce a table of states
	 * @param ctx
	 */
	public void showExplorer(Context ctx) {
		DomContent result=html(
				makeHeader("Peer Explorer"),
				body(
					h1("Convex Explorer"),
					article(
						h4("Useful links: "),
						p(a("Consensus Blocks").withHref(ROUTE+"blocks")),
						p(a("States").withHref(ROUTE+"states"))
					)
				)
			);
		
		ctx.result(result.render());
		ctx.contentType("text/html");
	}
	
	/**
	 * Produce a table of states
	 * @param ctx
	 */
	public void showStates(Context ctx) {
		Server s=restServer.getServer();
		
		// Snapshot of Peer data
		Peer peer=s.getPeer();
		long nstates=peer.getStatePosition()+1;
		
		long[] range = getPaginationRange(ctx, nstates);
		long start=range[0];
		long end=range[1];
		
		ArrayList<DomContent[]> rows=new ArrayList<>();
		for (long i=start; i<end; i++) {
			State state=(i==0)?peer.getGenesisState():peer.getBlockResult(i-1).getState();
			rows.add(new DomContent[] {
				td(Long.toString(i)),	
				td(code(state.getHash().toString()))	
			});
		}

		DomContent result=html(
				makeHeader("States"),
				body(
					table(
						thead(tr(th("Position"),th("Hash"))),
						tbody(
							each(rows,row->{return tr(row);})
						)
					)
				)
			);
		
		ctx.result(result.render());
		ctx.contentType("text/html");
	}
	
	/**
	 * Produce a table of blocks
	 * @param ctx
	 */
	public void showBlocks(Context ctx) {
		Server s=restServer.getServer();
		
		// Snapshot of Peer data
		Peer peer=s.getPeer();
		Order o=peer.getPeerOrder();
		AVector<SignedData<Block>> blocks=o.getBlocks();
		long nblocks=blocks.count();
		
		long[] range = getPaginationRange(ctx, nblocks);
		long start=range[0];
		long end=range[1];
		
		ArrayList<DomContent[]> rows=new ArrayList<>();
		for (long i=start; i<end; i++) {
			SignedData<Block> sd=blocks.get(i);
			rows.add(new DomContent[] {
				td(Long.toString(i)),	
				td(code(sd.getAccountKey().toString())),	
				td(code(sd.getHash().toString()))	
			});
		}
		
		DomContent result=html(
			makeHeader("Blocks"),
			body(
				table(
					thead(tr(th("Block"),th("Peer"),th("Hash"))),
					tbody(
						each(rows,row->{return tr(row);})
					)
				)
			)
		);
		
		ctx.contentType("text/html");
		ctx.result(result.render());
	}
	
	private long[] getPaginationRange(Context ctx, long ns) {
		long[] range=new long[2];
		try {
			String offsetParam=ctx.queryParam("offset");
			long offset=(offsetParam==null)?0:Integer.parseInt(offsetParam);
			if (offset<0) throw new BadRequestResponse("Negative offset parameter: "+offset);
			if (offset>ns) throw new BadRequestResponse("Offset out of range: "+offset);
			
			String limitParam=ctx.queryParam("limit");
			long limit=(offsetParam==null)?DEFAULT_LIMIT:Integer.parseInt(limitParam);

			if (limit<0) throw new BadRequestResponse("Negative limit parameter: "+limit);

			range[0]=offset;
			range[1]=Math.min(ns, offset+limit);
		} catch (Exception e) {
			throw new BadRequestResponse("Invalid query parameter");
		}
		return range;
	}

}
