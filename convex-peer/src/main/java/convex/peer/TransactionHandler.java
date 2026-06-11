package convex.peer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
import convex.core.exceptions.MissingDataException;
import convex.core.lang.Reader;
import convex.core.message.Message;
import convex.core.store.AStore;
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
	 * Default minimum delay (ms) between proposing blocks as a peer.
	 *
	 * This batching guard prevents the peer from creating a new block for every
	 * individual transaction. Without it, a peer receiving a steady stream of
	 * transactions would sign and persist a separate block for each one — wasting
	 * Ed25519 signing (~70us), persistence I/O, and network bandwidth on block
	 * overhead that dwarfs the transaction payload.
	 *
	 * The 10ms default allows transactions to accumulate in the transactionQueue
	 * between block creation attempts, producing larger (more efficient) blocks.
	 * At 10ms intervals, up to 1024 transactions can be batched per block.
	 *
	 * Configurable via the :min-block-time server config key.
	 */
	private static final long DEFAULT_MIN_BLOCK_TIME=10;
	
	/**
	 * Queue for incoming (unverified) transaction messages
	 */
	protected final ArrayBlockingQueue<Message> txMessageQueue;
	
	/**
	 * Queue for valid received Transactions submitted for clients of this Peer
	 */
	/**
	 * Queue for valid transactions awaiting block creation.
	 * Larger than txMessageQueue so the handler never blocks during normal operation.
	 * External backpressure is applied at txMessageQueue.
	 */
	ArrayBlockingQueue<SignedData<ATransaction>> transactionQueue;

	public TransactionHandler(Server server) {
		super(server);
		txMessageQueue= new ArrayBlockingQueue<>(Config.TRANSACTION_QUEUE_SIZE);
		transactionQueue=new ArrayBlockingQueue<>(3 * Config.TRANSACTION_QUEUE_SIZE);
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
	 * Offer a transaction for handling, blocking until space is available or timeout.
	 * Used as the retry predicate for backpressure.
	 * @param m Message offered
	 * @return True if queued for handling, false on timeout or interruption
	 */
	public boolean offerTransactionBlocking(Message m) {
		try {
			return txMessageQueue.offer(m, Config.DEFAULT_CLIENT_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
	
	/**
	 * Register of client interests in receiving transaction responses
	 */
	private ConcurrentHashMap<Hash, Message> interests = new ConcurrentHashMap<>();

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

	
	@SuppressWarnings("unchecked")
	private void processMessages() throws InterruptedException {
		Result problem=checkPeerState();
		if (problem!=null) {
			for (Message msg: messages) {
				msg.returnResult(problem);
			}
			return;
		}

		int n=messages.size();
		Peer peer=server.getPeer();

		// Phase 1: Extract SignedData, run cheap checks, reject failures immediately.
		// Survivors go to toVerify for parallel signature validation.
		SignedData<ATransaction>[] sigs=(SignedData<ATransaction>[]) new SignedData<?>[n];
		ArrayList<SignedData<ATransaction>> toVerify=new ArrayList<>(n);
		for (int i=0; i<n; i++) {
			Message m=messages.get(i);
			this.receivedTransactionCount++;
			try {
				AVector<ACell> v = m.getPayload();
				SignedData<ATransaction> sd = (SignedData<ATransaction>) v.get(2);
				Result error=peer.checkTransactionFast(sd);
				if (error!=null) {
					m.returnResult(error.withSource(SourceCodes.PEER));
					continue;
				}
				sigs[i]=sd;
				toVerify.add(sd);
			} catch (Exception e) {
				m.returnResult(Result.error(ErrorCodes.FORMAT, Strings.BAD_FORMAT).withSource(SourceCodes.PEER));
			}
		}

		// Phase 2: Parallel signature verification — results cached on each SignedData.
		// Wrapped defensively: a MissingDataException from one tx's verification must not
		// abort the whole batch. Any tx whose signature wasn't verified here will be
		// re-checked individually in Phase 3 under per-tx exception handling.
		try {
			Peer.preValidateSignatures(toVerify);
		} catch (Exception e) {
			// Ignore — per-tx handling in Phase 3 produces correct per-client errors
		}

		// Phase 3: Check cached signature results, persist fully, queue valid transactions.
		//
		// Persisting each SignedData into the store at intake enforces the invariant that
		// everything placed on transactionQueue is fully resolved in the local store.
		// Block production can then never throw MissingDataException — faulty/incomplete
		// transactions are rejected here with an immediate error to the client rather than
		// stalling the peer when the block proposer walks the cell tree.
		//
		// Interest is registered BEFORE queuing so that result reporting can never
		// race ahead of registration (the transaction becomes visible to
		// BeliefPropagator as soon as it enters the queue).
		// put() blocks if transactionQueue is full — this propagates backpressure:
		// TransactionHandler blocks → stops draining txMessageQueue → txMessageQueue
		// fills → connection-level backpressure kicks in → senders slow down.
		AStore store=server.getStore();
		for (int i=0; i<n; i++) {
			SignedData<ATransaction> sd=sigs[i];
			if (sd==null) continue; // already rejected in Phase 1
			Message m=messages.get(i);
			try {
				if (!sd.checkSignature()) {
					m.returnResult(Result.error(ErrorCodes.SIGNATURE, Strings.BAD_SIGNATURE).withSource(SourceCodes.PEER));
					continue;
				}
				// Force full persistence of the SignedData cell tree. If any referenced
				// cell is not present in the store (dangling Ref from a faulty or partial
				// message) this throws MissingDataException before we queue the tx.
				sd=Cells.persist(sd, store);
			} catch (MissingDataException e) {
				m.returnResult(Result.error(ErrorCodes.MISSING, Strings.create("Transaction data not available: "+e.getMissingHash())).withSource(SourceCodes.PEER));
				continue;
			} catch (IOException e) {
				m.returnResult(Result.error(ErrorCodes.IO, Strings.create("IO error persisting transaction: "+e.getMessage())).withSource(SourceCodes.PEER));
				continue;
			} catch (Exception e) {
				m.returnResult(Result.error(ErrorCodes.FORMAT, Strings.BAD_FORMAT).withSource(SourceCodes.PEER));
				continue;
			}
			registerInterest(sd.getHash(), m);
			transactionQueue.put(sd);
			observeTransactionRequest(sd);
			this.clientTransactionCount++;
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
	 * Gets the next Blocks for publication, or null if nothing to publish.
	 * Checks for pending transactions, and if found propose them as new Block(s).
	 *
	 * Called from the BeliefPropagator loop (not from TransactionHandler's own loop).
	 * The minBlockTime guard ensures we don't create blocks too frequently — allowing
	 * transactions to accumulate in the transactionQueue between calls, producing
	 * larger and more efficient blocks.
	 *
	 * @return New signed Block(s), or null if nothing to publish yet
	 */
	protected SignedData<Block>[] maybeGenerateBlocks() {
		Peer peer=server.getPeer();
		long timestamp=Utils.getCurrentTimestamp();

		if (!peer.isReadyToPublish()) return null;

		long minBlockTime=getMinBlockTime();

		// Batching guard: don't create blocks more often than minBlockTime (default 10ms).
		// This allows transactions to accumulate, producing larger blocks and reducing
		// per-block overhead (Ed25519 signing, persistence, network broadcast).
		if (timestamp<lastBlockPublishedTime+minBlockTime) return null;
			
		// possibly have own transactions to publish as a Peer
		maybeGetOwnTransactions(peer);
		
		// possibly have client transactions to publish
		transactionQueue.drainTo(newTransactions);
		
		// Count the new transactions. If there aren't any, we can safely exit
		int ntrans=newTransactions.size();
		if (ntrans==0) return null;

		try {
			
			int maxBlockSize=Constants.MAX_TRANSACTIONS_PER_BLOCK;
			int nblocks=((ntrans-1)/maxBlockSize)+1;
			
			@SuppressWarnings("unchecked")
			SignedData<Block>[] signedBlocks=new SignedData[nblocks];
		
			for (int i=0; i<nblocks; i++) {
				int start=i*maxBlockSize;
				int end=Math.min(ntrans, (i+1)*maxBlockSize);
				Block block = Block.create(timestamp, newTransactions.subList(start, end));
				SignedData<Block> signedBlock=peer.getKeyPair().signData(block);
				signedBlock=Cells.persist(signedBlock, server.getStore());
				signedBlocks[i]=signedBlock;		
			}
			newTransactions.clear();
			lastBlockPublishedTime=timestamp;
			return signedBlocks;
		} catch (Exception e) {
			log.warn("Exception preparing new block",e);
			return null;
		} finally {
			// Defence in depth: if anything in block production threw, transactions may
			// still be in newTransactions with registered interests. Clients must never
			// hang — notify each interested client of the failure and clear interests
			// before discarding. Phase 3 intake persistence is the primary guard that
			// stops faulty transactions ever reaching here; this is a safety net.
			if (!newTransactions.isEmpty()) {
				log.warn("Discarded "+newTransactions.size()+" potentially faulty / malicious transactions");
				for (SignedData<ATransaction> sd : newTransactions) {
					Hash h=sd.getHash();
					Message m=interests.remove(h);
					if (m!=null) {
						m.returnResult(Result.error(ErrorCodes.PEER, Strings.create("Block production failed")).withSource(SourceCodes.PEER));
					}
				}
				newTransactions.clear();
			}
		}
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
