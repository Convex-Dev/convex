package convex.peer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.Reader;
import convex.core.message.Message;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;

/**
 * Server component for handling client transactions and producing Blocks
 * 
 * Main loop for this component handles client transaction messages, validates them and 
 * prepares them for inclusion in a Block
 */
public class TransactionHandler extends AThreadedComponent {
	
	static final Logger log = LoggerFactory.getLogger(TransactionHandler.class.getName());
	
	/**
	 * Default minimum delay between proposing own transactions as a peer
	 */
	private static final long OWN_BLOCK_DELAY=10000;

	/**
	 * Default minimum delay between proposing a block as a peer
	 */
	private static final long DEFAULT_MIN_BLOCK_TIME=10;
	
	/**
	 * Queue for incoming (unverified) transaction messages
	 */
	protected final ArrayBlockingQueue<Message> txMessageQueue;
	
	/**
	 * Queue for valid received Transactions submitted for clients of this Peer
	 */
	ArrayBlockingQueue<SignedData<ATransaction>> transactionQueue;
	
	public TransactionHandler(Server server) {
		super(server);	
		txMessageQueue= new ArrayBlockingQueue<>(Config.TRANSACTION_QUEUE_SIZE);
		transactionQueue=new ArrayBlockingQueue<>(Config.TRANSACTION_QUEUE_SIZE);	
	}
	
	/**
	 * Offer a transaction for handling
	 * @param m Message offered
	 * @return True if queued for handling, false otherwise
	 */
	public boolean offerTransaction(Message m) {
		return txMessageQueue.offer(m);
	}
	
	/**
	 * Register of client interests in receiving transaction responses
	 */
	private HashMap<Hash, Message> interests = new HashMap<>();

	public long clientTransactionCount=0;
	public long receivedTransactionCount=0;

	private Consumer<SignedData<ATransaction>> requestObserver;

	/**
	 * Register interest in receiving a result for a transaction
	 * @param signedTransactionHash
	 * @param m
	 */
	private void registerInterest(Hash signedTransactionHash, Message m) {
		interests.put(signedTransactionHash, m);
	}
	
	private static final Result ERR_NOT_LIVE=Result.error(ErrorCodes.STATE, Strings.create("Server is not live")).withSource(SourceCodes.PEER);
	private static final Result ERR_NOT_REGISTERED=Result.error(ErrorCodes.STATE, Strings.create("Peer not registered in global state")).withSource(SourceCodes.PEER);
	private static final Result ERR_NOT_STAKED=Result.error(ErrorCodes.STATE, Strings.create("Peer not sufficiently staked to publish transactions")).withSource(SourceCodes.PEER);
	
	private Result checkPeerState() {
		try {
			if (!server.isLive()) {
				return ERR_NOT_LIVE;
			}
			Peer p=server.getPeer();
			State s=p.getConsensusState();
			PeerStatus ps=s.getPeers().get(p.getPeerKey());
			if (ps==null) {
				return ERR_NOT_REGISTERED;
			}
			if (ps.getBalance()<CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
				return ERR_NOT_STAKED;
			}

			return null;
		} catch (Exception e) {
			return Result.error(ErrorCodes.STATE, Strings.create("Peer problem: "+e.getMessage())).withSource(SourceCodes.PEER);
		}
	}

	
	private void processMessages() throws InterruptedException {
		Result problem=checkPeerState();
		for (Message msg: messages) {
			if (problem==null) {
				processMessage(msg);
			} else {
				msg.returnResult(problem);
			}
		}
	}
	
	protected void processMessage(Message m) throws InterruptedException {
		try {
			this.receivedTransactionCount++;
			// log.info("Got TX message: "+m);
			
			// Transaction is a vector [:TX, id , signed-tx]
			AVector<ACell> v = m.getPayload();
			@SuppressWarnings("unchecked")
			SignedData<ATransaction> sd = (SignedData<ATransaction>) v.get(2);
			
			// Check our transaction is valid and we want to process it
			Result error=server.getPeer().checkTransaction(sd);
			if (error!=null) {
				m.returnResult(error.withSource(SourceCodes.PEER));
				return;
			}
	
			// Put on Server's transaction queue. We are OK to block here
			LoadMonitor.down();
			transactionQueue.put(sd);
			observeTransactionRequest(sd);
			LoadMonitor.up();
			this.clientTransactionCount++;
			
			registerInterest(sd.getHash(), m);		
		} catch (BadFormatException e) {
			log.warn("Unhandled exception in transaction handler",e);
			m.closeConnection();
		} catch (MissingDataException e) {
			m.returnResult(Result.fromException(e).withSource(SourceCodes.PEER));
			return;
		}
	}
	

	
	/**
	 * Sets a request observer, which will be called whenever the Peer
	 * processes a valid client transaction request
	 * @param observer Consumer to receive observed transaction
	 */
	public void setRequestObserver(Consumer<SignedData<ATransaction>> observer) {
		this.requestObserver=observer;
	}
	
	private void observeTransactionRequest(SignedData<ATransaction> sd) {
		Consumer<SignedData<ATransaction>> observer=this.requestObserver;
		if (observer!=null) {
			observer.accept(sd);
		}
	}

	long reportedConsensusPoint;

	private BiConsumer<SignedData<ATransaction>, Result> responseObserver;

	public void maybeReportTransactions(Peer peer) {
		// Report transaction results
		long newConsensusPoint = peer.getFinalityPoint();
		if (newConsensusPoint > reportedConsensusPoint) {
			log.debug("Consensus point update from {} to {}" ,reportedConsensusPoint , newConsensusPoint);
			for (long i = reportedConsensusPoint; i < newConsensusPoint; i++) {
				SignedData<Block> block = peer.getPeerOrder().getBlock(i);
				// only report our own transactions!
				if (block.getAccountKey().equals(peer.getPeerKey())) {
					BlockResult br = peer.getBlockResult(i);
					reportTransactions(block.getValue(), br,i);
				}
			}
			reportedConsensusPoint=newConsensusPoint;
		}
	}
	
	private void reportTransactions(Block block, BlockResult br, long blockNum) {
		// TODO: consider culling old interests after some time period
		int nTrans = block.length();
		HashMap<Keyword,ACell> extInfo=new HashMap<>(5);
		for (long j = 0; j < nTrans; j++) {
			SignedData<ATransaction> t = block.getTransactions().get(j);
			Hash h = t.getHash();
			Message m = interests.get(h);
			if (m != null) {
				// ACell id = m.getID();
				// log.info("Returning transaction result ID {}", id);
				Result res = null;
				
				try {
					res=br.getResults().get(j);
					extInfo.put(Keywords.LOC,Vectors.createLongs(blockNum,j));
					extInfo.put(Keywords.TX,t.getHash());
					
					res=res.withExtraInfo(extInfo);
				} catch (Exception e) {
					res=Result.error(ErrorCodes.FATAL, "Failed to produce result").withSource(SourceCodes.PEER);
				}

				boolean reported = m.returnResult(res);
				if (!reported) {
					// ignore?
				}
				observeTransactionResponse(t,res);
				interests.remove(h);
			}
		}
	}
	
	/**
	 * Sets a request observer, which will be called whenever the Peer
	 * processes a valid client transaction request
	 * @param observer Consumer to receive observed transaction
	 */
	public void setResponseObserver(BiConsumer<SignedData<ATransaction>,Result> observer) {
		this.responseObserver=observer;
	}
	
	private void observeTransactionResponse(SignedData<ATransaction> sd, Result r) {
		BiConsumer<SignedData<ATransaction>,Result> observer=this.responseObserver;
		if (observer!=null) {
			observer.accept(sd,r);
		}
	}
	
	/**
	 * Gets the next Blocks for publication, or null if nothing to publish
	 * Checks for pending transactions, and if found propose them as new Block(s).
	 *
	 * @return New signed Block, or null if nothing to publish yet
	 */
	protected SignedData<Block>[] maybeGenerateBlocks() {
		Peer peer=server.getPeer();
		long timestamp=Utils.getCurrentTimestamp();

		if (!peer.isReadyToPublish()) return null;
		
		long minBlockTime=getMinBlockTime();
		
		if (timestamp<lastBlockPublishedTime+minBlockTime) return null;
			
		// possibly have own transactions to publish as a Peer
		maybeGetOwnTransactions(peer);
			
		// possibly have client transactions to publish
		transactionQueue.drainTo(newTransactions);

		if (newTransactions.isEmpty()) return null;
		
		int ntrans=newTransactions.size();
		int bsize=Constants.MAX_TRANSACTIONS_PER_BLOCK;
		int nblocks=((ntrans-1)/bsize)+1;
		
		@SuppressWarnings("unchecked")
		SignedData<Block>[] signedBlocks=new SignedData[nblocks];
		
		for (int i=0; i<nblocks; i++) {
		
			int start=i*bsize;
			int end=Math.min(ntrans, (i+1)*bsize);
			Block block = Block.create(timestamp, newTransactions.subList(start, end));
			SignedData<Block> signedBlock=peer.getKeyPair().signData(block);
			
			try {
				signedBlock=Cells.persist(signedBlock);
			} catch (Exception e) {
				log.warn("Exception preparing new block",e);
				return null;
			}
			signedBlocks[i]=signedBlock;		
		}
		newTransactions.clear();
		lastBlockPublishedTime=timestamp;
		return signedBlocks;
	}

	Long minBlockTime=null;
	
	/** 
	 * Get the minimum time between proposing blocks. Default 10ms. 
	 * @return
	 */
	private long getMinBlockTime() {
		if (minBlockTime==null) {
			HashMap<Keyword, Object> config = server.getConfig();
			CVMLong mbt=CVMLong.parse(config.get(Keywords.MIN_BLOCK_TIME));
			minBlockTime =(mbt==null)?DEFAULT_MIN_BLOCK_TIME:mbt.longValue();
		}
		return minBlockTime;
	}

	/**
	 * The list of new transactions to be added to the next Block. Accessed only in update loop
	 *
	 * Must all have been fully persisted.
	 */
	private ArrayList<SignedData<ATransaction>> newTransactions = new ArrayList<>();

	/**
	 * Last time at which the Peer's own transactions was submitted 
	 */
	private long lastOwnTransactionTimestamp=0L;

	/**
	 * Time at which last Block was published by this Peer
	 */
	protected long lastBlockPublishedTime=0L;


	
	/**
	 * Check if the Peer want to send any of its own transactions
	 * @param transactionList List of transactions to add to.
	 */
	void maybeGetOwnTransactions(Peer p) {
		long ts=Utils.getCurrentTimestamp();

		// If we already posted own transaction recently, don't try again
		if (ts<(lastOwnTransactionTimestamp+OWN_BLOCK_DELAY)) return;

		// NOTE: beyond this point we only execute stuff when AUTO_MANAGE is set
		if (!Utils.bool(server.getConfig().get(Keywords.AUTO_MANAGE))) return;
		
		// No point publishing if low staked etc.
		if (!p.isReadyToPublish()) return;

		State s=p.getConsensusState();
		AccountKey peerKey=p.getPeerKey();
		PeerStatus ps=s.getPeer(peerKey);
		if (ps==null) return; // No peer record in consensus state?


		AString chn=ps.getHostname();
		String currentHostname=(chn==null)?null:chn.toString();
		String desiredHostname=server.getHostname(); // Intended hostname
		
		// Try to set hostname if not correctly set
		trySetHostname:
		if ((desiredHostname!=null)&&!Utils.equals(desiredHostname, currentHostname)) {
			Address address=ps.getController();
			if (address==null) break trySetHostname;
			AccountStatus as=s.getAccount(address);
			if (as==null) break trySetHostname;
			// if we haven't got the controller key, just skip this
			if (!Cells.equals(peerKey, as.getAccountKey())) break trySetHostname;
			
			log.info("Trying to update own hostname from: {} to {}",currentHostname,desiredHostname);

			String code;
			code = String.format("(set-peer-data %s {:url \"%s\"})", peerKey, desiredHostname);
			ACell message = Reader.read(code);
			ATransaction transaction = Invoke.create(address, as.getSequence()+1, message);
			newTransactions.add(p.getKeyPair().signData(transaction));
			lastOwnTransactionTimestamp=ts; // mark this timestamp
		}
	}

	public void start() {
		this.reportedConsensusPoint=server.getPeer().getFinalityPoint();
		super.start();

	}

	public boolean isAwaitingResults() {
		return interests.size()>0;
	}

	public int countInterests() {
		return interests.size();
	}
	
	ArrayList<Message> messages=new ArrayList<>();

	/**
	 * Loops for handling incoming client transactions
	 */
	@Override
	protected void loop() throws InterruptedException {
		long BLOCKTIME=getMinBlockTime();
		try {
			LoadMonitor.down();
			Message m = txMessageQueue.poll(BLOCKTIME, TimeUnit.MILLISECONDS);
			LoadMonitor.up();
			if (m==null) return;
			
			LoadMonitor.down();
			// Brief pause in case more transactions are coming in
			Thread.sleep(1);
			LoadMonitor.up();

			
			// We have at least one transaction to handle, drain queue to get the rest
			messages.add(m);
			txMessageQueue.drainTo(messages);
			// log.info("Transaction Messages received: "+messages.size());
			
			// Process transaction messages
			// This might block if we aren't generating blocks fast enough
			// Which is OK, since we transfer backpressure to clients
			processMessages();
		} finally {
			messages.clear();
		}
	}


	@Override
	protected String getThreadName() {
		return "Transaction handler on port: "+server.getPort();
	}

}
