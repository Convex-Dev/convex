package convex.restapi.web;

import static j2html.TagCreator.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import javax.imageio.ImageIO;

import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.IdenticonBuilder;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
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
import convex.core.data.Symbol;
import convex.core.lang.RT;
import convex.core.data.AMap;
import convex.core.data.ACell;
import j2html.tags.specialized.TableTag;
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
		app.get(prefix+"states/{position}", this::showStatePage);
		app.get(prefix+"accounts", this::showAccounts);
		app.get(prefix+"accounts/{accountNum}", this::showAccount);
		app.get(prefix+"peers", this::showPeers);
		app.get(prefix+"peers/{peerKey}", this::showPeerDetail);

	}
	

	
	/**
	 * Produce a table of states
	 * @param ctx Javalin context
	 */
	public void showExplorer(Context ctx) {
        returnPage(ctx, "Peer Explorer", (String[][])null,
			div(
				// Pico grid of cards
				article(
					p(a("Blocks").withHref(ROUTE+"blocks").withStyle("font-weight:600;font-size:1.1em;")),
					p("Browse blocks in the peer's consensus order, view details and transactions.")
				),
				article(
					p(a("Accounts").withHref(ROUTE+"accounts").withStyle("font-weight:600;font-size:1.1em;")),
					p("Explore accounts in the latest consensus.")
				),
				article(
					p(a("Peers").withHref(ROUTE+"peers").withStyle("font-weight:600;font-size:1.1em;")),
					p("Examine peers on the current network, including stakes and activity.")
				),
					article(
					p(a("States").withHref(ROUTE+"states").withStyle("font-weight:600;font-size:1.1em;")),
					p("View historical consensus states.")
				)
			).withClass("grid").withStyle("align-items: stretch;")
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
				td(a(Long.toString(i)).withHref(ROUTE+"states/"+i)),	
				td(showStateID(state,i)),	
				td(timestamp(state.getTimestamp().longValue()))	
			});
		}

        returnPage(ctx,"States,", new String[][] {{"Explorer",ROUTE},{"States",null}},
					table(
						thead(tr(th("Position"),th("State Hash"),th("Timestamp"))),
						tbody(
							each(rows,row->{return tr(row);})
						)
					)
			);
	}

    /**
     * Show a specific state by position
     */
    public void showStatePage(Context ctx) {
        Server s=restServer.getServer();
        Peer peer=s.getPeer();
        long pos=Long.parseLong(ctx.pathParam("position"));
        long nstates=peer.getStatePosition()+1;
        if ((pos<0)||(pos>=nstates)) throw new NotFoundResponse("State position out of range: "+pos);
        State state=(pos==0)?peer.getGenesisState():peer.getBlockResult(pos-1).getState();
        returnPage(ctx, "State #"+pos, new String[][] {{"Explorer",ROUTE},{"States","/explorer/states"},{Long.toString(pos),null}},
            article(
            	(pos==0)?
            			h5("Genesis state")
            			:h5(text("State after "),a("block "+(pos-1)).withHref(ROUTE+"blocks/"+(pos-1))),
            	makeNavigationLinks(ROUTE+"states", pos, nstates, "State"),
        		table(
	                thead(tr(th("Field"),th("Value"),th("Notes"))),
	                tbody(
	                    tr(td("Hash"),td(showID(state.getHash(),64)),td("State hash")),
	                    tr(td("Timestamp"),td(timestamp(state.getTimestamp().longValue())),td("State timestamp (UTC)")),
	                    tr(td("Accounts"),td(code(Long.toString(state.getAccounts().count()))),td("Account count at this state"))
	                ))
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
		long limit = range[2]; // limit if provided. or default limit
		
		// Create pagination controls
		DomContent paginationLinks = makePaginationLinks(ctx, ROUTE+"accounts", start, limit, naccounts);
		returnPage(ctx, "Accounts", new String[][] {{"Explorer","/explorer/"},{"Accounts",null}},
			paginationLinks,
			buildAccountsTable(ctx,accounts,range)
		);
		return ;
	}
	
	public TableTag buildAccountsTable(Context ctx, AVector<AccountStatus> accounts, long[] range) {
		long start = range[0];
		long end = range[1];

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
		
        return table(
					thead(tr(th("Address"), th("Key"), th("Balance"))),
					tbody(
						each(rows, row -> tr(row))));
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
				td(showID(sd.getHash()))	
			});
		}
		
        returnPage(ctx, "Blocks", new String[][] {{"Explorer",ROUTE},{"Blocks",null}},
				table(
					thead(tr(th("Index"),th("Peer"),th("Block Hash"))),
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
		DomContent navLinks = makeNavigationLinks(ROUTE+"blocks", blockOffset, nblocks, "Block");
		
        returnPage(ctx, "Convex Block: "+blockNum, new String[][] {{"Explorer",ROUTE},{"Blocks","/explorer/blocks"},{Long.toString(blockNum),null}},
            article(
        		navLinks,
				table(
					thead(tr(th("Field"),th("Value"),th("Notes"))),
					makeBlockTable(sblock)
            )),
            article(
            		makeStateTransitionSection(peer, blockNum, ctx)
            ),
            article(makeTransactionsSection(sblock, blockNum, ctx))
		);
	}

	// Utility to display block summary info as a table
	private TbodyTag makeBlockTable(SignedData<Block> sblock) {
		AccountKey peerKey=sblock.getAccountKey(); // Public key of signing peer
		return tbody(
				tr(
						td("Block Hash"),
						td(showID(sblock.getHash(),64)),
						td("Hash of block as signed by peer")),
			tr(
				td("Peer"),
				td(a(showID(peerKey,64)).withHref(ROUTE+"peers/"+peerKey)),
				td("Peer Ed25519 public key.")),
			tr(
				td("Signature"),
				td(showCVX(sblock.getSignature())),
				td("Ed25519 signature of block (as signed by peer)")),
			tr(
				td("Memory"),
				td(code(""+Cells.storageSize(sblock))),
				td("Bytes consumed by blcok data structure"))
		);
	}
	
	
	// Transactions table for use in block
	private DomContent makeTransactionsSection(SignedData<Block> sblock, long blockNum, Context ctx) {
		AVector<SignedData<ATransaction>> transactions = sblock.getValue().getTransactions();
		int txCount = transactions.size();
		
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
			h4("Transactions ("+txCount+")"),
			table(
				thead(tr(th("Index"), th("Origin Address"),th("Transaction Hash"))),
				tbody(
					each(rows, row -> tr(row))
				)
			)
		);
	}

    // State transition line for a block
    private DomContent makeStateTransitionSection(Peer peer, long blockNum, Context ctx) {
        State beforeState=null;
        State afterState=null;
        try {
            beforeState=(blockNum==0)?peer.getGenesisState():peer.getBlockResult(blockNum-1).getState();
            if (blockNum<peer.getPeerOrder().getBlockCount()) {
                afterState=peer.getBlockResult(blockNum).getState();
            }
        } catch (Exception e) {
            // leave nulls
        }
        return div(
            h5("State Transition"),
    		p("Each block updates the CVM state. For posterity, here's the record of what this block did."),
            div(
            	showStateID(beforeState,blockNum),
                span("  >  ").withStyle("margin: 0.5em"),
                showStateID(afterState,blockNum+1)
            ).withStyle("display: flex; align-items: center;")
        );
    }
    
    protected DomContent showStateID(State s, long position) {
    	return (s==null)?code("<No History>"):
            a(showID(s.getHash())).withHref(ROUTE+"states/"+position);
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
		DomContent navLinks = makeNavigationLinks(ROUTE+"blocks/"+blockNum+"/txs", txOffset, txCount, "Transaction");
		
        returnPage(ctx, "Transaction "+txNum+" in Block "+blockNum, new String[][] {{"Explorer",ROUTE},{"Blocks","/explorer/blocks"},{Long.toString(blockNum),"/explorer/blocks/"+Long.toString(blockNum)},{"Transactions",null},{Long.toString(txNum),null}},
			navLinks,
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				tbody(
					row(
						"Address",
						div(identicon(signedTx.getAccountKey()),
						   span().withStyle("margin-right: 0.5em;"), // bit of space
						   showAddress(trans.getOrigin())),
						"Origin address of transaction"),
					row(
						"Account Key",
						showID(signedTx.getAccountKey()),
						"Ed25519 public key of the signer"),
					row(
						"Transaction Hash",
						showID(signedTx.getHash()),
						"Hash code of the transaction object"),
					row(
						"Type",
						code(trans.getClass().getSimpleName()),
						"Type of transaction. Most common is 'Invoke' for general purpose execution."),
					row(
						"Transaction Data",
						showCVX(trans),
						"CVX representation of the transaction"),
					row(
						"Storage Size",
						code(""+Cells.storageSize(signedTx)),
						"Bytes consumed by transaction data")
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
		
        returnPage(ctx, "Account: #"+accountNum, new String[][] {{"Explorer",ROUTE},{"Accounts","/explorer/accounts"},{"#"+Long.toString(accountNum),null}},
			// h1("Account: #"+accountNum),
			article(
					h6("Account "+address),
					makeNavigationLinks(ROUTE+"accounts", accountNum, state.getAccounts().count(), "Account"),
					makeAccountTable(account, address)),
	        article(buildAccountFieldsView(account)),
            article(buildEnvironmentView(account)),
            article(buildHoldingsView(account))
 		);
	}
	
	// Utility to display account summary info as a table
	private DomContent makeAccountTable(AccountStatus account, Address address) {
		return table(
			thead(tr(th("Field"),th("Value"),th("Notes"))),
			tbody(
				row(
					"Account Key",
					account.getAccountKey() != null ? showID(account.getAccountKey()) : code("null"),
					"Ed25519 public key (null for actors)"),
				row(
					"Balance",
					showBalance(account.getBalance()),
					"Convex coin balance")
		));
	}
	
	   /**
     * Build a table view of Account fields (ACCOUNT_KEYS)
     */
    private DomContent buildAccountFieldsView(AccountStatus account) {
        return details(
            summary("Account Fields"),
            table(
                thead(tr(th("Field"), th("Value"), th("Description"))),
                tbody(
                    row(
                    	code(":sequence"), 
                    	code(Long.toString(account.getSequence())),
                    	text("Number of transactions executed by this account to date.")),
                    row(
                    	code(":key"), 
                    	account.getAccountKey()==null?code("nil"):showID(account.getAccountKey(),64),
                    	text("Ed25519 public key of this account. If nil, account cannot execute external transactions (e.g. an actor).")),
                    row(
                    	code(":balance"), 
                    	showBalance(account.getBalance()),
                    	text("CVM balance of account. This is used for transaction fees and may be freely transferred.")),
                    row(
                    	code(":allowance"), 
                    	code(Long.toString(account.getMemory())),
                    	text("Memory allowance credit on the CVM. If positive, the account may allocated up to this amount of memory before incurring fees for additional memory.")),
                    row(
                    	code(":holdings"), 
                    	Long.toString(account.getHoldings().count())+ " value(s)",
                    	text("Storage for holdings data referenced by other accounts.")),
                    row(
                    	code(":controller"), 
                    	account.getController()==null?code("nil"):showAddress((Address)account.getController()),
                    	text("Account controller. If set, the controller can execute code in this account (e.g. change the key). If you don't trust the controller, don't trust the account!")),
                    row(
                    	code(":environment"), 
                    	Long.toString(account.getEnvironment()==null?0:account.getEnvironment().count())+ " value(s)",
                    	text("Symbols defined in this account. Typically used to store data or executable code.")),
                    row(
                    	code(":metadata"), 
                    	Long.toString(account.getMetadata()==null?0:account.getMetadata().count())+ " value(s)",
                    	text("Metadata attached to symbols defined in this account.")),
                    row(
                    	code(":parent"), 
                    	account.getParent()==null?code("nil"):showAddress(account.getParent()),
                    	text("Parent account. This defines fallback values for symbols not defined in this account."))
                )
            )
        );
    }

    /**
     * Build a table view of the account environment (Symbol -> Value)
     */
    private DomContent buildEnvironmentView(AccountStatus account) {
        AMap<Symbol, ACell> env = account.getEnvironment();
        if (env==null) return summary("No Environment");
        ArrayList<DomContent> rows = new ArrayList<>();
        long n=env.count();
        for (int i=0; i<n; i++) {
            MapEntry<Symbol, ACell> me=env.entryAt(i);
        	Symbol sym = me.getKey();
            ACell val = me.getValue();
            AMap<ACell,ACell> md = account.getMetadata(sym);
            rows.add(row(
                code(sym.getName().toString()),
                showCVX(val),
                showCVX(RT.getIn(md,Keywords.DOC,Keywords.DESCRIPTION))
            ));
        }

        return details(
			summary("Environment ("+((n==0)?"Empty":Long.toString(n))+")"),
            p(text("The Environment contains symbols defined in this account. These may be referenced like: "),code("#45/symbol-name")),
			table(
	            thead(tr(th("Symbol"), th("Value"), th("Description"))),
	            tbody(
	                each(rows, row -> row)
	            )
		    )      
		);
    }

    /**
     * Build a table view of the holdings (Address -> Value)
     */
    private DomContent buildHoldingsView(AccountStatus account) {
        var hodls = account.getHoldings();
        if (hodls==null) {
            hodls = Index.none();
        }
        long n=hodls.count();
        ArrayList<DomContent[]> rows = new ArrayList<>();
        for (long i=0; i<n; i++) {
            MapEntry<Address, ACell> me = hodls.entryAt(i);
            Address addr = me.getKey();
            ACell val = me.getValue();
            rows.add(new DomContent[] {
                td(showAddress(addr)),
                td(showCVX(val))
            });
        }
        return details(
            summary("Holdings ("+((n==0)?"Empty":Long.toString(n))+")"),
            p(text("Holdings track token balances and other indexed values by address.")),
            table(
                thead(tr(th("Address"), th("Value"))),
                tbody(
                    each(rows, row -> tr(row))
                )
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
				td(showBalance(peerStatus.getBalance())),
				td(showBalance(peerStatus.getPeerStake())),
				td(showBalance(peerStatus.getDelegatedStake())),
				td(showPercent(percent))
			});
		}
		
		// Create pagination controls
		String basePath = ABaseAPI.getExternalBaseUrl(ctx, ROUTE+"peers");
		DomContent pagination = makePaginationLinks(ctx, basePath, start, end - start, npeers);
		
        returnPage(ctx, "Peers", new String[][] {{"Explorer",ROUTE},{"Peers",null}},
			pagination,
			table(
				thead(tr(
					th("Peer Key"),
					th("Total Stake"),
					th("Peer Stake"),
					th("Delegated Stake"),
					th("Stake")
				)),
				tbody(
					each(rows, row -> tr(row))
				)
			)
		);
	}
	
	/**
	 * Show a percentage value with fixed-width alignment, formatted like " 18.00 %"
	 * @param percent Percentage value (0-100)
	 * @return Monospace-formatted DomContent
	 */
	protected DomContent showPercent(double percent) {
		String s=String.format(Locale.US, "%6.2f %%", percent);
		return preCode(s);
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
		
        returnPage(ctx, "Peer: " + peerKey.toHexString(), new String[][] {{"Explorer",ROUTE},{"Peers","/explorer/peers"},{peerKey.toHexString(8)+"...",null}},
			table(
				thead(tr(th("Field"),th("Value"),th("Notes"))),
				makePeerTable(peerStatus, peerKey)
			)
		);
	}
	
	// Utility to display peer summary info as a table
	private TbodyTag makePeerTable(PeerStatus peerStatus, AccountKey peerKey) {
		return tbody(
			row("Peer Key",showID(peerKey,64),
					"Public key of the peer"),
			row("Controller",
					showAddress(peerStatus.getController()),
					"Controller address for this peer"),
			row("Total Stake",
					showBalance(peerStatus.getBalance()),
					"Total stake (peer + delegated) in CVM"),
			row("Peer Stake",
					showBalance(peerStatus.getPeerStake()),
					"Peer's own stake in CVM"),
			row("Delegated Stake",
					showBalance(peerStatus.getDelegatedStake()),
					"Stake delegated to this peer in CVM"),
			row("Last Block",
					timestamp(peerStatus.getTimestamp()),
					"Timestamp of last block issued by this peer"),
			row("Hostname",
					peerStatus.getHostname() != null ? code(peerStatus.getHostname().toString()) : code("<not defined>"),
					"Hostname/URL for peer connections"),
			row("Metadata",
					showCVX(peerStatus.getMetadata()),
					"Metadata provide by peer operator"),
			row("Storage Size",
					code(""+Cells.storageSize(peerStatus)),
					"Bytes consumed by peer status data structure")
		);
	}

	


	

	


}
