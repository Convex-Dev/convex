package convex.peer;

import convex.core.lang.RT;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
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
import convex.core.cvm.Juice;
import convex.core.cvm.Keywords;
import convex.core.cvm.Migrations;
import convex.core.cvm.Peer;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ValidationException;
import convex.core.lang.Reader;
import convex.core.message.Message;
import convex.core.store.AStore;
import convex.core.util.LoadMonitor;
import convex.core.util.ThreadUtils;
import convex.core.util.Utils;
import convex.net.RequestFailures;

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

	/** #597: earliest / latest lead time (ms before an upgrade activation) within which a
	 * peer that cannot apply the upgrade sheds its own stake. Randomised per peer so a
	 * whole cohort does not unstake at one instant. */
	private static final long WITHDRAW_PREWINDOW_MIN = 5*60*1000;   // 5 minutes before
	private static final long WITHDRAW_PREWINDOW_MAX = 10*60*1000;  // 10 minutes before

	/** Activation timestamp we have computed a withdrawal instant for (-1 = none). */
	private long withdrawalActivation = -1;
	/** Randomised wall-clock instant at which to attempt withdrawal for {@link #withdrawalActivation}. */
	private long withdrawalInstant = -1;
	/** Whether we have already attempted (queued) the withdrawal for {@link #withdrawalActivation}. */
	private boolean withdrawalQueued = false;

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
	 * Used as the retry predicate for backpressure. Does not wait on this handler's
	 * own thread, for example in a local client's callback for a transaction result.
	 * @param m Message offered
	 * @return True if queued for handling, false on timeout or interruption
	 */
	public boolean offerTransactionBlocking(Message m) {
		return offerBlocking(txMessageQueue, m);
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
	private boolean registerInterest(Hash signedTransactionHash, Message m) {
		if (interests.putIfAbsent(signedTransactionHash,m)==null) return true;
		m.returnResult(Result.error(ErrorCodes.STATE,"Transaction already pending; recover its result by transaction hash")
				.withSource(SourceCodes.PEER).withInfo(Keywords.TX,signedTransactionHash));
		return false;
	}
	
	private static final Result ERR_NOT_LIVE=Result.error(ErrorCodes.STATE, Strings.create("Server is not live")).withSource(SourceCodes.PEER);
	private static final Result ERR_NOT_REGISTERED=Result.error(ErrorCodes.STATE, Strings.create("Peer not registered in global state")).withSource(SourceCodes.PEER);
	private static final Result ERR_NOT_STAKED=Result.error(ErrorCodes.STATE, Strings.create("Peer not sufficiently staked to publish transactions")).withSource(SourceCodes.PEER);

	private final RequestFailures failures=new RequestFailures(log);

	/**
	 * For both observers, apart from intake's log, so an observer's defect does not use
	 * up intake's one warning.
	 */
	private final RequestFailures observerFailures=new RequestFailures(log);

	private static final Result ERR_RESULT_UNAVAILABLE=Result.error(ErrorCodes.PEER,
		Strings.intern("TX executed, but result not delivered")).withSource(SourceCodes.PEER);

	/** Separate logs, so each site warns once of each kind of failure. */
	private final RequestFailures resultFailures=new RequestFailures(log);
	private final RequestFailures deliveryFailures=new RequestFailures(log);

	private static final Result ERR_BLOCK_FAILED=Result.error(ErrorCodes.PEER, Strings.intern("Block production failed")).withSource(SourceCodes.PEER);

	/** Separate from intake's log, so each site warns once of each kind of failure. */
	private final RequestFailures blockFailures=new RequestFailures(log);

	/**
	 * Gets the signed value a transaction message carries, or null if the message does
	 * not have that shape.
	 */
	@SuppressWarnings("unchecked")
	private static SignedData<ATransaction> transactionOf(Message m) {
		AVector<ACell> v=RT.ensureVector(m.getPayload());
		if ((v==null)||(v.count()<3)) return null;
		return (v.get(2) instanceof SignedData<?> sd)?(SignedData<ATransaction>)sd:null;
	}

	/** Rejects one client transaction whose handling failed. */
	private void reject(Message m, Throwable e) {
		failures.log(e,"Rejected transaction for connection {}",m.getConnection());
		sendRejection(m,RequestFailures.reply(e));
		// After the reply, for a local client that stays open; a remote one may be closed
		// before it is written. Every validation failure here is in the client's own input
		if (e instanceof ValidationException bad) server.getConnectionManager().handleBadInput(m.getConnection(),bad);
	}

	/**
	 * Sends a rejection to a client, dropping it if it cannot be sent, unless the JVM
	 * itself has failed.
	 */
	private static void sendRejection(Message m, Result r) {
		try {
			m.returnResult(r);
		} catch (Throwable e) {
			Utils.checkRequestFailure(e);
		}
	}
	
	private Result checkPeerState() {
		try {
			if (!server.isLive()) {
				return ERR_NOT_LIVE;
			}
			Peer p=server.getPeer();
			State s=p.getConsensusState();
			PeerStatus ps=s.getPeer(p.getPeerKey());
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
				// Each reply guarded: one client's failing reply must not cost the others theirs
				sendRejection(msg,problem);
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
				SignedData<ATransaction> sd=transactionOf(m);
				// Checked explicitly, so that FORMAT is never inferred from an arbitrary failure below
				if (sd==null) throw new BadFormatException("Transaction message without a signed transaction");
				Result error=peer.checkTransactionFast(sd);
				if (error!=null) {
					m.returnResult(error.withSource(SourceCodes.PEER));
					continue;
				}
				sigs[i]=sd;
				toVerify.add(sd);
			} catch (Throwable e) {
				// Guard for each received transaction, with the one in Phase 3: any failure
				// rejects only this transaction, unless the JVM itself has failed
				Utils.checkRequestFailure(e);
				reject(m,e);
			}
		}

		// Phase 2: Parallel signature verification — results cached on each SignedData.
		// Wrapped defensively: a MissingDataException from one tx's verification must not
		// abort the whole batch. Any tx whose signature wasn't verified here will be
		// re-checked individually in Phase 3 under per-tx exception handling.
		try {
			Peer.preValidateSignatures(toVerify);
		} catch (Throwable e) {
			// Ignore, unless the JVM itself has failed: Phase 3 checks each signature
			// again under each transaction's own guard
			Utils.checkRequestFailure(e);
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
			Hash h=null; // set once this message may have registered an interest
			try {
				if (!sd.checkSignature()) throw new BadSignatureException("Bad transaction signature",sd);
				Result affordability=checkTransactionAffordability(
					server.getPeer().getConsensusState(),sd.getValue());
				if (affordability!=null) {
					m.returnResult(affordability);
					continue;
				}
				// Force full persistence of the SignedData cell tree. If any referenced
				// cell is not present in the store (dangling Ref from a faulty or partial
				// message) this throws MissingDataException before we queue the tx, and
				// a tree with invalid structure is the client's malformed input
				sd=Cells.persistReceived(sd, store);
				// Inside the guard: the reply to a duplicate can fail, and must cost
				// only this transaction
				h=sd.getHash();
				if (!registerInterest(h, m)) continue;
			} catch (MissingDataException e) {
				// sendRejection: a reply that fails must not escape the guard from here
				sendRejection(m,Result.error(ErrorCodes.MISSING, Strings.create("Transaction data not available: "+e.getMissingHash())).withSource(SourceCodes.PEER));
				continue;
			} catch (IOException e) {
				sendRejection(m,Result.error(ErrorCodes.IO, Strings.create("IO error persisting transaction: "+e.getMessage())).withSource(SourceCodes.PEER));
				continue;
			} catch (Throwable e) {
				// Guard for each received transaction, with the one in Phase 1: any failure
				// rejects only this transaction, unless the JVM itself has failed
				Utils.checkRequestFailure(e);
				// Only this message's interest: one left behind would refuse the
				// client's resubmission as already pending
				if (h!=null) interests.remove(h,m);
				reject(m,e);
				continue;
			}
			// Outside the guard: an ArrayBlockingQueue allocates nothing here, and an
			// interrupt must reach the loop so the peer can shut down
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
			try {
				observer.accept(sd);
			} catch (Throwable e) {
				// The transaction is already queued: the observer's failure costs nothing else
				Utils.checkRequestFailure(e);
				observerFailures.log(e,"Transaction request observer failed");
			}
		}
	}

	long reportedConsensusPoint;

	private BiConsumer<SignedData<ATransaction>, Result> responseObserver;

	public void maybeReportTransactions(Peer peer) {
		// Report transaction results of Blocks executed so far
		long newConsensusPoint = Math.min(peer.getFinalityPoint(), peer.getStatePosition());
		if (newConsensusPoint > reportedConsensusPoint) {
			log.debug("Consensus point update from {} to {}" ,reportedConsensusPoint , newConsensusPoint);
			for (long i = reportedConsensusPoint; i < newConsensusPoint; i++) {
				// only report our own transactions!
				Block block = peer.getOwnBlock(i);
				if (block != null) {
					BlockResult br = peer.getBlockResult(i);
					reportTransactions(block, br,i);
				}
			}
			reportedConsensusPoint=newConsensusPoint;
		}
	}
	
	/**
	 * Reports the results of one of this peer's blocks to the clients waiting for them.
	 * The calling thread, normally the CVM executor, only collects the results and
	 * clears their interests; a virtual thread of their own delivers them. So neither
	 * the encoding of a large result nor an in-process client's continuations hold up
	 * the peer's state, and each block's delivery is independent of the others'.
	 */
	void reportTransactions(Block block, BlockResult br, long blockNum) {
		// TODO: consider culling old interests after some time period
		int nTrans = block.length();
		ArrayList<Message> recipients=new ArrayList<>();
		ArrayList<Result> results=new ArrayList<>();
		HashMap<Keyword,ACell> extInfo=new HashMap<>(5);
		try {
			for (long j = 0; j < nTrans; j++) {
				SignedData<ATransaction> t = block.getTransactions().get(j);
				Hash h = t.getHash();
				Message m = interests.remove(h);
				if (m == null) continue;
				// Listed at once, the error a placeholder: whatever fails below, this client
				// is answered, as its interest is gone
				recipients.add(m);
				results.add(ERR_RESULT_UNAVAILABLE);
				Result res;
				try {
					res=br.getResults().get(j);
					extInfo.put(Keywords.LOC,Vectors.createLongs(blockNum,j));
					extInfo.put(Keywords.TX,h);
					res=res.withExtraInfo(extInfo);
				} catch (Throwable e) {
					// Guard for each client's result. The transaction has executed and its
					// interest is gone, so the client gets an error rather than nothing.
					Utils.checkRequestFailure(e);
					resultFailures.log(e,"Failed to produce result for transaction {}",h);
					// PEER even for running out of memory: LOAD would invite a retry of a
					// transaction that has already executed
					res=ERR_RESULT_UNAVAILABLE;
				}
				observeTransactionResponse(t,res);
				results.set(results.size()-1,res);
			}
		} finally {
			// Even if the loop failed: every client listed has lost its interest
			if (!recipients.isEmpty()) {
				Thread delivery=Thread.ofVirtual().name("Transaction results for block "+blockNum).unstarted(() -> {
					try {
						deliverResults(recipients,results);
					} finally {
						deliveries.remove(Thread.currentThread());
					}
				});
				// Tracked before it starts, so a wait for deliveries cannot miss it
				deliveries.add(delivery);
				delivery.start();
			}
		}
	}

	/** Threads delivering a block's results, which the peer's shutdown waits for */
	private final Set<Thread> deliveries=ConcurrentHashMap.newKeySet();

	/**
	 * Waits at most the given time for result deliveries under way to finish. An
	 * interrupt does not end the wait, and is restored once it is over.
	 *
	 * @return true if none is still running
	 */
	boolean awaitDeliveriesStopped(long timeoutMillis) {
		long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		for (Thread delivery:List.copyOf(deliveries)) {
			Duration left=Duration.ofNanos(Math.max(0,deadline-System.nanoTime()));
			if (!ThreadUtils.joinUninterruptibly(delivery,left)) return false;
		}
		return true;
	}

	/**
	 * Delivers one block's results in order, never waiting. A result is refused if its
	 * client's connection is closed or its reader is not draining, which is normal
	 * operation, or if the server is shedding load.
	 */
	private void deliverResults(ArrayList<Message> recipients, ArrayList<Result> results) {
		for (int i=0; i<recipients.size(); i++) {
			Message m=recipients.get(i);
			boolean reported;
			try {
				reported=m.returnResult(results.get(i));
			} catch (Throwable e) {
				// Guard for each result: one that cannot be sent costs no other client its result
				Utils.checkRequestFailure(e);
				deliveryFailures.log(e,"Failed to deliver transaction result for connection {}",m.getConnection());
				continue;
			}
			if (!reported && log.isDebugEnabled()) {
				log.debug("Dropped transaction result for connection {}", m.getConnection());
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
			try {
				observer.accept(sd,r);
			} catch (Throwable e) {
				// Runs on the CVM executor's thread: the observer's failure must cost
				// neither this client's result nor the peer
				Utils.checkRequestFailure(e);
				observerFailures.log(e,"Transaction response observer failed");
			}
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
		long timestamp=server.getTimestamp();

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
			// Blocks are bounded by transaction count only. Their size is the messaging
			// layer's concern: an Order update of any size is carried in bounded messages.
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
		} catch (Throwable e) {
			// Guard for Block production from received transactions, which runs on the
			// Belief propagator's thread: a failure fails these transactions, not the thread
			Utils.checkRequestFailure(e);
			try {
				failTransactions(newTransactions,e);
			} finally {
				// Never carried into a later Block: their clients may already have been
				// told they failed
				newTransactions.clear();
			}
			// Last: logging can itself run out of memory
			blockFailures.log(e,"Block production failed; {} transactions rejected",ntrans);
			return null;
		}
	}

	/**
	 * Fails the clients of transactions taken from the queue for Blocks that could not
	 * be produced or proposed: running out of memory is answered as load, any other
	 * failure as a peer error.
	 */
	void failTransactions(Iterable<SignedData<ATransaction>> txs, Throwable cause) {
		// LOAD as at intake: running out of memory says nothing about the transactions
		Result r=(cause instanceof OutOfMemoryError)?Result.SERVER_LOADED:ERR_BLOCK_FAILED;
		for (SignedData<ATransaction> sd: txs) {
			// Removing the interest lets the client resubmit; the peer's own transactions have none
			Message m=interests.remove(sd.getHash());
			if (m!=null) sendRejection(m,r);
		}
	}

	/** Fails the clients of the transactions in Blocks this peer could not propose. */
	void failBlocks(SignedData<Block>[] blocks, Throwable cause) {
		for (SignedData<Block> b: blocks) failTransactions(b.getValue().getTransactions(),cause);
		// Last: logging can itself run out of memory
		blockFailures.log(cause,"Block proposal failed; its transactions rejected");
	}

	/**
	 * Applies the peer's intake policy for the mandatory transaction-size fee. This
	 * is deliberately not a consensus validity check: the execution allowance has
	 * its own limit, while a sufficiently funded account may pay a larger size fee.
	 *
	 * @param state current consensus state used for intake policy
	 * @param tx transaction to assess
	 * @return a peer-sourced error when the origin cannot cover the fee, otherwise null
	 */
	static Result checkTransactionAffordability(State state, ATransaction tx) {
		AccountStatus account=state.getAccount(tx.getOrigin());
		if (account==null) {
			return Result.error(ErrorCodes.NOBODY,Strings.NO_SUCH_ACCOUNT).withSource(SourceCodes.PEER);
		}
		long sizeFee=Juice.mul(Juice.priceTransaction(tx),state.getJuicePrice().longValue());
		if (sizeFee>account.getBalance()) {
			return Result.error(ErrorCodes.JUICE,
				Strings.create("Insufficient balance for transaction size fee of "+sizeFee))
				.withSource(SourceCodes.PEER);
		}
		return null;
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
		long ts=server.getTimestamp();

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

		// #597: best-efforts stake withdrawal ahead of an unsupported upgrade takes
		// priority over routine self-management. If we queue one, do nothing else.
		if (maybeWithdrawStakeBeforeUpgrade(p, s, peerKey, ps, ts)) {
			lastOwnTransactionTimestamp = ts;
			return;
		}

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

	/**
	 * #597: best-efforts stake withdrawal ahead of an unsupported network upgrade.
	 *
	 * <p>When the consensus schedule contains an upgrade this release cannot apply, this
	 * peer will withdraw (freeze) at the activation. Until then its stake still counts
	 * toward consensus thresholds, so a cohort of soon-to-freeze peers can prevent the
	 * remaining upgraded peers from reaching supermajority. To avoid that, the peer sheds
	 * its own stake in a randomised window shortly before the activation, while it can
	 * still transact.</p>
	 *
	 * <p>Best-efforts and conservative:</p>
	 * <ul>
	 * <li>triggered from the early on-chain signal (not the boundary, where it is already
	 *     frozen), at a randomised instant in the pre-window so a cohort does not all
	 *     unstake at one instant and jolt the weighting;</li>
	 * <li>never if it would leave no other viable peer — the network is already
	 *     non-viable, and destroying the last stake is strictly worse, so we just freeze;</li>
	 * <li>attempted once; a failed attempt is harmless, as the peer freezes at the
	 *     boundary regardless.</li>
	 * </ul>
	 *
	 * Gated (like all peer self-management here) on {@code :auto-manage}, default on.
	 *
	 * @return true if a withdrawal transaction was queued this round
	 */
	private boolean maybeWithdrawStakeBeforeUpgrade(Peer p, State s, AccountKey peerKey, PeerStatus ps, long ts) {
		Migrations.UpgradeWarning w = server.getUpgradeWarning();
		if (w == null) {
			// No pending unsupported upgrade: clear any scheduled withdrawal
			withdrawalActivation = -1;
			withdrawalQueued = false;
			return false;
		}

		// Compute a randomised withdrawal instant once per activation
		if (w.activation != withdrawalActivation) {
			withdrawalActivation = w.activation;
			withdrawalQueued = false;
			long window = WITHDRAW_PREWINDOW_MAX - WITHDRAW_PREWINDOW_MIN;
			long offset = WITHDRAW_PREWINDOW_MIN + (long) (Math.random() * window);
			withdrawalInstant = w.activation - offset;
		}

		if (withdrawalQueued) return false;          // already attempted for this activation
		if (ts < withdrawalInstant) return false;    // not yet in the pre-window
		if (ts >= w.activation) { withdrawalQueued = true; return false; } // too late; will freeze
		if (ps.getPeerStake() <= 0) { withdrawalQueued = true; return false; } // nothing to withdraw

		// Never the last viable peer
		if (!leavesAnotherViablePeer(s, peerKey)) {
			Server.upgradeLog.warn("Not auto-withdrawing stake before upgrade to protocol version {}: no other viable peer, so the network is already non-viable. Freezing at the boundary instead.", w.version);
			withdrawalQueued = true;
			return false;
		}

		// Must hold the controller key to submit the self-transaction (as for hostname management)
		Address controller = ps.getController();
		if (controller == null) return false;
		AccountStatus as = s.getAccount(controller);
		if ((as == null) || !Cells.equals(peerKey, as.getAccountKey())) return false;

		Server.upgradeLog.warn("Best-efforts: withdrawing peer stake ahead of unsupported upgrade to protocol version {} scheduled at {}", w.version, w.activation);
		ACell message = Reader.read(String.format("(set-peer-stake %s 0)", peerKey));
		ATransaction transaction = Invoke.create(controller, as.getSequence()+1, message);
		newTransactions.add(p.getKeyPair().signData(transaction));
		withdrawalQueued = true;
		return true;
	}

	/**
	 * #597: true if some peer OTHER than {@code myKey} holds at least the minimum
	 * effective stake, i.e. withdrawing {@code myKey}'s stake would not leave the network
	 * with no viable peer.
	 */
	static boolean leavesAnotherViablePeer(State s, AccountKey myKey) {
		Index<AArrayBlob, PeerStatus> peers = s.getPeers();
		long n = peers.count();
		for (long i = 0; i < n; i++) {
			MapEntry<AArrayBlob, PeerStatus> e = peers.entryAt(i);
			if (Cells.equals(e.getKey(), myKey)) continue;
			if (RT.ensureCVM(e.getValue(),PeerStatus.class).getPeerStake() >= CPoSConstants.MINIMUM_EFFECTIVE_STAKE) return true;
		}
		return false;
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
