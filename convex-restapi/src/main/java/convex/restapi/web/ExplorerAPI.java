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
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.AArrayBlob;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.MapEntry;
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
import j2html.tags.specialized.TbodyTag;

/**
 * On-chain explorer API 
 */
public class ExplorerAPI extends AWebSite {
	public ExplorerAPI(RESTServer restServer) {
		super(restServer);

	}
	
	static final String ROUTE = "/explorer/";
	


	@Override
	public void addRoutes(Javalin app) {
		String prefix = ROUTE;
		app.get(prefix, this::showExplorer);
		app.get(prefix+"blocks", this::showBlocks);
		app.get(prefix+"blocks/{blockNum}", this::showBlock);
		app.get(prefix+"blocks/{blockNum}/txs/{txNum}", this::showTransaction);
		app.get(prefix+"states", this::showStates);
		app.get(prefix+"accounts", this::showAccounts);
		app.get(prefix+"accounts/{accountNum}", this::showAccount);
		app.get(prefix+"peers", this::showPeers);
		app.get(prefix+"peers/{peerKey}", this::showPeerDetail);
		app.get("/identicon/{hex}", this::getIdenticon);
	}
	

	
	/**
	 * Produce a table of states
	 * @param ctx Javalin context
	 */
	public void showExplorer(Context ctx) {
		returnPage(ctx, "Peer Explorer",
			breadcrumb(new String[][] {{"Explorer","/explorer/"}}),
			article(
				p(a("Blocks").withHref(ROUTE+"blocks")),
				p(a("Accounts").withHref(ROUTE+"accounts")),
				p(a("Peers").withHref(ROUTE+"peers")),
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
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"States",null}}),
					table(
						thead(tr(th("Position"),th("State Hash"),th("Timestamp"))),
						tbody(
							each(rows,row->{return tr(row);})
						)
					)
			);
	}
	
	/**
	 * Produce a table of accounts
	 * @param ctx Javalin context
	 */
	public void showAccounts(Context ctx) {
		Server s=restServer.getServer();
		
		// Get current state from server
		State state = s.getPeer().getConsensusState();
		AVector<AccountStatus> accounts = state.getAccounts();
		long naccounts = accounts.count();
		
		// Get pagination parameters
		long[] range = getPaginationRange(ctx, naccounts);
		long start = range[0];
		long end = range[1];
		long limit = range[2]; // limit if provided. or default limit

		// throw if too big
		if (end-start>100) throw new BadRequestResponse("Too many elements requested");
		
		ArrayList<DomContent[]> rows = new ArrayList<>();
		for (long i = start; i < end; i++) {
			Address address = Address.create(i);
			AccountStatus account = accounts.get(i);
			String accountLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"accounts/"+i);
			
			rows.add(new DomContent[] {
				td(a(address.toString()).withHref(accountLink)),
				td(showID(account.getAccountKey()) ),
				td(div(showBalance(account.getBalance())))
			});
		}
		
		// Create pagination controls
		DomContent paginationLinks = makePaginationLinks(ctx, ROUTE+"accounts", start, limit, naccounts);
		
		returnPage(ctx, "Accounts",
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Accounts",null}}),
			div(
				paginationLinks
			),
			table(
				thead(tr(th("Address"), th("Key"), th("Balance"))),
				tbody(
					each(rows, row -> tr(row))
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
			String peerLink=ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"peers/"+sd.getAccountKey().toHexString());
			
			rows.add(new DomContent[] {
				td(a(Long.toString(i)).withHref(link)),	
				td(a(showID(sd.getAccountKey())).withHref(peerLink)),	
				td(showHex(sd.getHash()))	
			});
		}
		
		returnPage(ctx, "Blocks",
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Blocks",null}}),
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
		long blockOffset = blockNum;
		DomContent navLinks = makeNavigationLinks(ctx, ROUTE+"blocks", blockOffset, nblocks, "Block");
		
		returnPage(ctx, "Convex Block: "+blockNum,
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Blocks","/explorer/blocks"},{Long.toString(blockNum),null}}),
			navLinks,
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makeBlockTable(sblock)
			),
			makeTransactionsSection(sblock, blockNum, ctx)
		);
	}
	
	/**
	 * Show specific transaction details within a block
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
		
		SignedData<ATransaction> signedTx = transactions.get(txNum);
		ATransaction trans=signedTx.getValue();
		
		// Create navigation links for transactions within this block
		long txOffset = txNum;
		DomContent navLinks = makeNavigationLinks(ctx, ROUTE+"blocks/"+blockNum+"/txs", txOffset, txCount, "Tx");
		
		returnPage(ctx, "Transaction "+txNum+" in Block "+blockNum,
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Blocks","/explorer/blocks"},{Long.toString(blockNum),"/explorer/blocks/"+Long.toString(blockNum)},{"Tx "+Long.toString(txNum),null}}),
			h5("Transaction "+txNum+" in Block "+blockNum),
			navLinks,
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				tbody(
					tr(
						td("Address"),
						td(identicon(signedTx.getAccountKey()),
						   span("  "), // bit of space. How to make this 1em?
						   showAddress(trans.getOrigin())),
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
						td(showCVX(trans)),
						td("CVX representation of the transaction")),
					tr(
						td("Storage Size"),
						td(code(""+Cells.storageSize(signedTx))),
						td("Bytes consumed by transaction data"))
				)
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
		if (accountNum<state.getAccounts().count()-1) {
			String nextLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"accounts/"+(accountNum+1));
			navLinks.add(makeButton("Next >>",nextLink));
		}
		
		returnPage(ctx, "Account: #"+accountNum,
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Accounts","/explorer/accounts"},{"#"+Long.toString(accountNum),null}}),
			// h1("Account: #"+accountNum),
			each(navLinks, link -> link),
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makeAccountTable(account, address)
			)
		);
	}

	/**
	 * Produce a table of peers
	 * @param ctx Javalin context
	 */
	public void showPeers(Context ctx) {
		Server s=restServer.getServer();
		
		// Get current state from server
		State state = s.getPeer().getConsensusState();
		Index<AccountKey, PeerStatus> peers = state.getPeers();
		long npeers = peers.count();
		
		// Get pagination parameters
		long[] range = getPaginationRange(ctx, npeers);
		long start = range[0];
		long end = range[1];
		
		ArrayList<DomContent[]> rows = new ArrayList<>();
		long totalStakeAllPeers = state.getPeers().reduceValues((Long acc, PeerStatus ps) -> acc + ps.getBalance(), 0L);
		for (long i = start; i < end; i++) {
			MapEntry<AccountKey, PeerStatus> entry = peers.entryAt(i);
			AccountKey peerKey = entry.getKey();
			PeerStatus peerStatus = entry.getValue();
			String peerLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"peers/"+peerKey.toHexString());
			double percent = (totalStakeAllPeers>0)?(100.0*peerStatus.getBalance()/ (double)totalStakeAllPeers):0.0;
			
			rows.add(new DomContent[] {
				td(a(showID(peerKey)).withHref(peerLink)),
				td(div(showBalance(peerStatus.getBalance()))),
				td(div(showBalance(peerStatus.getPeerStake()))),
				td(div(showBalance(peerStatus.getDelegatedStake()))),
				td(showPercent(percent))
			});
		}
		
		// Create pagination controls
		String basePath = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"peers");
		DomContent pagination = makePaginationLinks(ctx, basePath, start, end - start, npeers);
		
		returnPage(ctx, "Peers",
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Peers",null}}),
			pagination,
			table(
				thead(tr(
					th("Peer Key"),
					th("Total Stake"),
					th("Peer Stake"),
					th("Delegated Stake"),
					th("Stake %")
				)),
				tbody(
					each(rows, row -> tr(row))
				)
			)
		);
	}

	/**
	 * Show detailed information for a specific peer
	 * @param ctx Javalin context
	 */
	public void showPeerDetail(Context ctx) {
		String peerKeyParam = ctx.pathParam("peerKey");
		
		// Parse the peer key from hex string
		AccountKey peerKey;
		try {
			peerKey = AccountKey.parse(peerKeyParam);
		} catch (Exception e) {
			throw new BadRequestResponse("Invalid peer key format: " + peerKeyParam);
		}
		
		// Get current state from server
		State state = server.getPeer().getConsensusState();
		PeerStatus peerStatus = state.getPeer(peerKey);
		
		if (peerStatus == null) {
			throw new NotFoundResponse("Peer " + peerKeyParam + " does not exist");
		}
		
		returnPage(ctx, "Peer: " + peerKey.toHexString(),
			breadcrumb(new String[][] {{"Explorer","/explorer/"},{"Peers","/explorer/peers"},{peerKey.toHexString(),null}}),
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makePeerTable(peerStatus, peerKey)
			)
		);
	}
	
	// Utility to display peer summary info as a table
	private TbodyTag makePeerTable(PeerStatus peerStatus, AccountKey peerKey) {
		return tbody(
			tr(
				td("Peer Key"),
				td(showID(peerKey)),
				td("Public key of the peer")),
			tr(
				td("Controller"),
				td(showAddress(peerStatus.getController())),
				td("Controller address for this peer")),
			tr(
				td("Total Stake"),
				td(div(showBalance(peerStatus.getBalance()))),
				td("Total stake (peer + delegated) in CVM")),
			tr(
				td("Peer Stake"),
				td(div(showBalance(peerStatus.getPeerStake()))),
				td("Peer's own stake in CVM")),
			tr(
				td("Delegated Stake"),
				td(div(showBalance(peerStatus.getDelegatedStake()))),
				td("Stake delegated to this peer in CVM")),
			tr(
				td("Timestamp"),
				td(code(Long.toString(peerStatus.getTimestamp()))),
				td("Timestamp of last block issued by this peer")),
			tr(
				td("Hostname"),
				td(peerStatus.getHostname() != null ? code(peerStatus.getHostname().toString()) : code("<not defined>")),
				td("Hostname/URL for peer connections")),
			tr(
				td("Metadata"),
				td(showCVX(peerStatus.getMetadata())),
				td("Metadata provide by peer operator")),
			tr(
				td("Storage Size"),
				td(code(""+Cells.storageSize(peerStatus))),
				td("Bytes consumed by peer status data structure"))
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
				td(showHex(sblock.getHash())),
				td("Hash of block as signed by peer")),
			tr(
				td("Signature"),
				td(showHex(sblock.getSignature())),
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
				td("Memory"),
				td(code(Long.toString(account.getMemory()))),
				td("Memory allowance credit in bytes")),
			tr(
				td("Memory Usage"),
				td(code(Long.toString(account.getMemoryUsage()))),
				td("Actual memory usage in bytes")),
			tr(
				td("Actor?"),
				td(code(account.isActor() ? "true" : "false")),
				td("Whether this is an actor account (no public key)")),
			tr(
				td("Controller"),
				td(showAddress((Address)account.getController())),
				td("Controller address (if any)")),
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
	


	

	
	private DomContent makeTransactionsSection(SignedData<Block> sblock, long blockNum, Context ctx) {
		AVector<SignedData<ATransaction>> transactions = sblock.getValue().getTransactions();
		long txCount = transactions.count();
		
		ArrayList<DomContent[]> rows = new ArrayList<>();
		for (long i = 0; i < txCount; i++) {
			String txLink = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"blocks/"+blockNum+"/txs/"+i);
			SignedData<ATransaction> strans=transactions.get(i);
			
			rows.add(new DomContent[] {
				td(a(Long.toString(i)).withHref(txLink)),
				td(identicon(strans.getAccountKey()),showAddress(strans.getValue().getOrigin())),
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
	 * Get an identicon PNG image for the given hex data
	 * @param ctx Javalin context
	 */
	public void getIdenticon(Context ctx) {
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
	

	


}
