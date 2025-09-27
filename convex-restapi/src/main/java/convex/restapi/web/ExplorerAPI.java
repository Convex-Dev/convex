package convex.restapi.web;

import static j2html.TagCreator.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import javax.imageio.ImageIO;

import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.IdenticonBuilder;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Peer;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.AArrayBlob;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.SignedData;
import convex.peer.Server;
import convex.restapi.RESTServer;
import convex.restapi.api.ABaseAPI;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import j2html.tags.DomContent;
import j2html.tags.specialized.CodeTag;
import j2html.tags.specialized.ImgTag;
import j2html.tags.specialized.TbodyTag;

/**
 * On-chain explorer API 
 */
public class ExplorerAPI extends AWebSite {
	public ExplorerAPI(RESTServer restServer) {
		super(restServer);

	}
	
	static final String ROUTE = "/explorer/";
	
	private static final long DEFAULT_LIMIT = 100;

	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;
		app.get(prefix, this::showExplorer);
		app.get(prefix+"blocks", this::showBlocks);
		app.get(prefix+"blocks/{blockNum}", this::showBlock);
		app.get(prefix+"blocks/{blockNum}/txs/{txNum}", this::showTransaction);
		app.get(prefix+"states", this::showStates);
		app.get(prefix+"accounts/{accountNum}", this::showAccount);
		app.get("/identicon/{hex}", this::showIdenticon);
	}
	

	
	/**
	 * Produce a table of states
	 * @param ctx Javalin context
	 */
	public void showExplorer(Context ctx) {
		returnPage(ctx, "Peer Explorer",
			article(
				p(a("Blocks").withHref(ROUTE+"blocks")),
				p(a("States").withHref(ROUTE+"states"))
			)
		);
	}
	
	/**
	 * Produce a table of states
	 * @param ctx Javalin context
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
				td(code(state.getHash().toString())),	
				td(state.getTimestamp().toString())	
			});
		}

		returnPage(ctx,"States,",
					table(
						thead(tr(th("Position"),th("State Hash"),th("Timestamp"))),
						tbody(
							each(rows,row->{return tr(row);})
						)
					)
			);
	}
	
	/**
	 * Produce a table of blocks
	 * @param ctx Javalin context
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
			String link=ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+i);
			
			rows.add(new DomContent[] {
				td(a(Long.toString(i)).withHref(link)),	
				td(code(sd.getAccountKey().toString())),	
				td(code(sd.getHash().toString()))	
			});
		}
		
		returnPage(ctx, "Blocks",
			table(
				thead(tr(th("Block"),th("Peer"),th("Hash"))),
				tbody(
					each(rows,row->{return tr(row);})
				)
			)
		);
	}
	
	/**
	 * Produce a table of blocks
	 * @param ctx Javalin context
	 */
	public void showBlock(Context ctx) {
		Server s=restServer.getServer();
		long blockNum=Long.parseLong(ctx.pathParam("blockNum"));
		
		// Snapshot of Peer data
		Peer peer=s.getPeer();
		Order o=peer.getPeerOrder();
		AVector<SignedData<Block>> blocks=o.getBlocks();
		long nblocks=blocks.count();
		
		if (blockNum>=nblocks) throw new NotFoundResponse("Block "+blockNum+" does not yet exist");
		SignedData<Block> sblock=blocks.get(blockNum);
		
		// Create navigation links
		ArrayList<DomContent> navLinks = makeBlockNavigationLinks(ctx, blockNum, nblocks);
		
		returnPage(ctx, "Convex Block: "+blockNum,
			h4("Block position: "+blockNum),
			navLinks.isEmpty() ? div() : div(
				each(navLinks, link -> link),
				br()
			),
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makeBlockTable(sblock)
			),
			makeTransactionsSection(sblock, blockNum, ctx)
		);
	}
	
	/**
	 * Show a specific transaction within a block
	 * @param ctx Javalin context
	 */
	public void showTransaction(Context ctx) {
		Server s=restServer.getServer();
		long blockNum=Long.parseLong(ctx.pathParam("blockNum"));
		long txNum=Long.parseLong(ctx.pathParam("txNum"));
		
		// Snapshot of Peer data
		Peer peer=s.getPeer();
		Order o=peer.getPeerOrder();
		AVector<SignedData<Block>> blocks=o.getBlocks();
		long nblocks=blocks.count();
		
		if (blockNum>=nblocks) throw new NotFoundResponse("Block "+blockNum+" does not yet exist");
		SignedData<Block> sblock=blocks.get(blockNum);
		
		AVector<SignedData<ATransaction>> transactions = sblock.getValue().getTransactions();
		long txCount = transactions.count();
		
		if (txNum >= txCount) throw new NotFoundResponse("Transaction "+txNum+" does not exist in block "+blockNum);
		
		SignedData<ATransaction> transaction = transactions.get(txNum);
		
		// Create navigation links
		ArrayList<DomContent> navLinks = makeTransactionNavigationLinks(ctx, blockNum, txNum, txCount);
		
		returnPage(ctx, "Transaction "+txNum+" in Block "+blockNum,
			h1("Transaction "+txNum+" in Block "+blockNum),
			navLinks.isEmpty() ? div() : div(
				each(navLinks, link -> link),
				br()
			),
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makeTransactionTable(transaction)
			)
		);
	}
	
	/**
	 * Show account details for a specific account number
	 * @param ctx Javalin context
	 */
	public void showAccount(Context ctx) {
		Server s=restServer.getServer();
		long accountNum=Long.parseLong(ctx.pathParam("accountNum"));
		
		// Get current state from server
		State state = s.getPeer().getConsensusState();
		Address address = Address.create(accountNum);
		AccountStatus account = state.getAccount(address);
		
		if (account == null) {
			throw new NotFoundResponse("Account " + accountNum + " does not exist");
		}
		
		// Create navigation links
		ArrayList<DomContent> navLinks = new ArrayList<>();
		if (accountNum > 0) {
			String prevLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"accounts/"+(accountNum-1));
			navLinks.add(makeButton("<< Prev",prevLink));
		}
		String nextLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"accounts/"+(accountNum+1));
		navLinks.add(makeButton("Next >>",nextLink));
		
		returnPage(ctx, "Account: #"+accountNum,
			h1("Account: #"+accountNum),
			each(navLinks, link -> link),
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makeAccountTable(account, address)
			)
		);
	}

	// Utility to display block summary info as a table
	private TbodyTag makeBlockTable(SignedData<Block> sblock) {
		return tbody(
			tr(
				td("Peer"),
				td(showID(sblock.getAccountKey())),
				td("Peer Ed25519 public key.")),
			tr(
				td("Block Hash"),
				td(code(sblock.getHash().toString())),
				td("Hash of block as signed by peer")),
			tr(
				td("Signature"),
				td(wrappedCode(sblock.getSignature().toString())),
				td("Ed25519 signature of block (as signed by peer)")),
			tr(
				td("Tx Count"),
				td(code(""+sblock.getValue().getTransactions().count())),
				td("Number of transactions in this block")),
			tr(
				td("Storage Size"),
				td(code(""+Cells.storageSize(sblock))),
				td("Bytes consumed by blcok data structure"))
		);
	}
	
	// Utility to display transaction summary info as a table
	private TbodyTag makeTransactionTable(SignedData<ATransaction> signedTx) {
		ATransaction trans=signedTx.getValue();
		return tbody(
			tr(
				td("Address"),
				td(identicon(signedTx.getAccountKey().toHexString()),showAddress(trans.getOrigin())),
				td("Origin address of transaction")),
			tr(
				td("Account Key"),
				td(showID(signedTx.getAccountKey())),
				td("Ed25519 public key of the signer")),
			tr(
				td("Transaction Hash"),
				td(showID(signedTx.getHash())),
				td("Hash code of the transaction object")),
			tr(
				td("Transaction Type"),
				td(code(trans.getClass().getSimpleName())),
				td("Java class name of the transaction")),
			tr(
				td("Transaction Data"),
				td(wrappedCode(signedTx.toString())),
				td("CVX representation of the transaction")),
			tr(
				td("Storage Size"),
				td(code(""+Cells.storageSize(signedTx))),
				td("Bytes consumed by transaction data"))
		);
	}
	
	// Utility to display account summary info as a table
	private TbodyTag makeAccountTable(AccountStatus account, Address address) {
		return tbody(
			tr(
				td("Address"),
				td(code(address.toString())),
				td("Account address")),
			tr(
				td("Account Key"),
				td(account.getAccountKey() != null ? showID(account.getAccountKey()) : code("null")),
				td("Ed25519 public key (null for actors)")),
			tr(
				td("Balance"),
				td(code(Long.toString(account.getBalance()))),
				td("Convex coin balance")),
			tr(
				td("Sequence"),
				td(code(Long.toString(account.getSequence()))),
				td("Number of transactions executed by this account")),
			tr(
				td("Memory Allowance"),
				td(code(Long.toString(account.getMemory()))),
				td("Memory allowance in bytes")),
			tr(
				td("Memory Usage"),
				td(code(Long.toString(account.getMemoryUsage()))),
				td("Actual memory usage in bytes")),
			tr(
				td("Is Actor"),
				td(code(account.isActor() ? "true" : "false")),
				td("Whether this is an actor account (no public key)")),
			tr(
				td("Controller"),
				td(account.getController() != null ? showAddress((Address)account.getController()) : code("null")),
				td("Controller address if any")),
			tr(
				td("Parent"),
				td(account.getParent() != null ? showAddress(account.getParent()) : code("null")),
				td("Parent account address if any")),
			tr(
				td("Holdings Count"),
				td(code(Long.toString(account.getHoldings().count()))),
				td("Number of holdings (token balances)"))
		);
	}

	public static CodeTag wrappedCode(String value) {
		return code(value).withStyle("display: inline-block;white-space: normal;max-width:100%; word-break:break-all; overflow-wrap:break-word;");
	}
	
	/**
	 * Create a div containing an identicon and corresponding data value
	 * @param data The data to create identicon for
	 * @return DomContent div with identicon and code
	 */
	public static DomContent showID(AArrayBlob data) {
		String dataString = data.toString();
		
		ImgTag identicon = identicon(dataString);
		
		return div(
			identicon,
			code(dataString)
		);
	}

	private static ImgTag identicon(String hexString) {
		String identiconUrl = "/identicon/" + hexString;
		ImgTag identicon = img().withSrc(identiconUrl).withAlt("Identicon for " + hexString).withStyle("height: 28; image-rendering: pixelated; margin: 2px;");
		return identicon;
	}
	
	private DomContent makeTransactionsSection(SignedData<Block> sblock, long blockNum, Context ctx) {
		AVector<SignedData<ATransaction>> transactions = sblock.getValue().getTransactions();
		long txCount = transactions.count();
		
		ArrayList<DomContent[]> rows = new ArrayList<>();
		for (long i = 0; i < txCount; i++) {
			String txLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+blockNum+"/txs/"+i);
			SignedData<ATransaction> strans=transactions.get(i);
			
			rows.add(new DomContent[] {
				td(a(Long.toString(i)).withHref(txLink)),
				td(identicon(strans.getAccountKey().toHexString()),showAddress(strans.getValue().getOrigin())),
				td(showID(strans.getHash()))
			});
		}
		
		return div(
			h4("Transactions"),
			table(
				thead(tr(th("Index"), th("Origin Address"),th("Transaction Hash"))),
				tbody(
					each(rows, row -> tr(row))
				)
			)
		);
	}

	/**
	 * Show an identicon PNG image for the given hex data
	 * @param ctx Javalin context
	 */
	public void showIdenticon(Context ctx) {
		String hexParam = ctx.pathParam("hex");
		
		// Parse hex string to blob
		AArrayBlob data = Blob.parse(hexParam);
		if (data == null) {
			throw new BadRequestResponse("Invalid hex string for identicon: " + hexParam);
		}
		
		try {
			// Generate identicon data
			int[] identiconData = IdenticonBuilder.build(data);
			
			// Create BufferedImage from identicon data
			BufferedImage image = new BufferedImage(IdenticonBuilder.SIZE, IdenticonBuilder.SIZE, BufferedImage.TYPE_INT_RGB);
			image.setRGB(0, 0, IdenticonBuilder.SIZE, IdenticonBuilder.SIZE, identiconData, 0, IdenticonBuilder.SIZE);
			
			// Convert to PNG bytes
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(image, "PNG", baos);
			byte[] pngBytes = baos.toByteArray();
			
			// Set response headers for caching and content type
			ctx.header("Content-Type", "image/png");
			ctx.header("Cache-Control", "public, max-age=31536000"); // 1 year cache
			ctx.header("ETag", "\"" + data.toHexString() + "\""); // Use data as ETag
			ctx.result(pngBytes);
			
		} catch (IOException e) {
			throw new InternalServerErrorResponse("Failed to generate identicon: " + e.getMessage());
		}
	}
	
	private ArrayList<DomContent> makeBlockNavigationLinks(Context ctx, long blockNum, long nblocks) {
		ArrayList<DomContent> navLinks = new ArrayList<>();
		if (blockNum > 0) {
			String prevLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+(blockNum-1));
			navLinks.add(makeButton("<< Previous",prevLink));
		}
		if (blockNum < nblocks - 1) {
			String nextLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+(blockNum+1));
			navLinks.add(makeButton("Next >>",nextLink));
		}
		return navLinks;
	}
	

	private ArrayList<DomContent> makeTransactionNavigationLinks(Context ctx, long blockNum, long txNum, long txCount) {
		ArrayList<DomContent> navLinks = new ArrayList<>();
		String blockLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+blockNum);
		navLinks.add(a("Back to Block "+blockNum).withHref(blockLink));
		
		if (txNum > 0) {
			String prevTxLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+blockNum+"/txs/"+(txNum-1));
			navLinks.add(makeButton("<< Prev" ,prevTxLink));
		}
		if (txNum < txCount - 1) {
			String nextTxLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+blockNum+"/txs/"+(txNum+1));
			navLinks.add(makeButton("Next >>",nextTxLink));
		}
		return navLinks;
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
		} catch (BadRequestResponse e) {
			throw e;
		} catch (Exception e) {
			throw new InternalServerErrorResponse("Error handling query parameter: "+e);
		}
		return range;
	}

}
