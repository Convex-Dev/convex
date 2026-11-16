package convex.peer;

import convex.core.lang.RT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Acquiror;
import convex.core.cpos.Belief;
import convex.core.cpos.BeliefMerge;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.MissingDataException;
import convex.core.exceptions.ValidationException;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.BoundedMessageQueue;
import convex.core.message.MessageType;
import convex.core.store.AStore;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;
import convex.net.RequestFailures;

/**
 * Component class to handle propagation of new Beliefs from a Peer
 * 
 * Overall logic:
 * 1. We want to propagate a new Belief delta as fast as possible once one is received
 * 2. We want to pause to ensure that as many peers as possible have received the delta
 * 
 */
public class BeliefPropagator extends AThreadedComponent {
	/**
	 * Wait period for beliefs received in each iteration of Server Belief Merge loop.
	 *
	 * This pause serves two purposes:
	 * 1. In multi-peer networks: waits for incoming peer beliefs to accumulate before
	 *    performing a belief merge, reducing the number of merge operations.
	 * 2. As a side effect: controls the loop period and therefore how frequently
	 *    maybeGenerateBlocks() is called, acting as a transaction batching delay.
	 *
	 * On a single-peer network (or when no remote beliefs arrive), the full wait
	 * elapses every iteration — even when transactionQueue has pending transactions.
	 * The actual block publication rate guard is minBlockTime (default 10ms) in
	 * TransactionHandler.maybeGenerateBlocks().
	 *
	 * See TRANSACTION_PATH.md for pipeline analysis and potential improvements.
	 */
	private static final long AWAIT_BELIEFS_PAUSE = 30L;

	
	public static final int BELIEF_REBROADCAST_DELAY=300;
	
	/**
	 * Time between full Belief broadcasts
	 */
	public static final int BELIEF_FULL_BROADCAST_DELAY=500;

	
	/**
	 * Minimum delay between successive Belief broadcasts
	 */
	public static final int BELIEF_BROADCAST_DELAY=10;
	
	/**
	 * Polling period for Belief propagator loop
	 */
	public static final int BELIEF_BROADCAST_POLL_TIME=1000;
	
	/** Complete Beliefs acquired into the local store, awaiting merge. */
	private final ArrayBlockingQueue<Belief> beliefQueue = new ArrayBlockingQueue<>(
		Config.BELIEF_QUEUE_SIZE);

	/**
	 * Ordered DATA and BELIEF messages received from any connection. DATA must
	 * remain ahead of the root that references it until both reach this thread.
	 */
	// TODO: use config if provided
	private final BoundedMessageQueue propagationQueue = new BoundedMessageQueue(
		Config.BELIEF_QUEUE_SIZE,Config.BELIEF_QUEUE_BYTE_LIMIT,true);

	
	static final Logger log = LoggerFactory.getLogger(BeliefPropagator.class.getName());

	long beliefReceivedCount=0L;


	public BeliefPropagator(Server server) {
		super(server);
	}
	
	/**
	 * Time of last belief broadcast
	 */
	long lastBroadcastTime=0;
	
	/**
	 * Time of last Belief update broadcast
	 */
	long lastFullBroadcastTime=-1;

	/** True if the Belief changed (any Order) since the last Belief update was sent. */
	private boolean beliefChanged=false;
	
	private long beliefBroadcastCount=0L;
	
	public long getBeliefBroadcastCount() {
		return beliefBroadcastCount;
	}
	
	/**
	 * Queues a complete Belief already acquired into this peer's store. This queue is
	 * count-bounded only: queueing a local value must neither encode it as a wire
	 * message nor charge again for data already held by the store.
	 *
	 * @param acquiredBelief complete locally acquired Belief
	 * @return true if queued, false if the bounded queue is full
	 */
	public boolean queueBelief(Belief acquiredBelief) {
		return beliefQueue.offer(acquiredBelief);
	}

	/**
	 * Queues an ordered DATA or BELIEF wire message for processing.
	 *
	 * @param message propagation message
	 * @return true if queued, false if the bounded queue is full
	 */
	public boolean queuePropagation(Message message) {
		return propagationQueue.offer(message);
	}

	/**
	 * Tests whether status polling may add another complete Belief. This depends
	 * only on the polled Belief queue: polling the peers this peer chose is how
	 * Beliefs keep arriving while pushes from any connection fill the propagation
	 * queue. This is advisory: the queue may fill immediately afterwards, in which
	 * case the later offer is simply dropped.
	 *
	 * @return true if the polled Belief queue has capacity
	 */
	boolean hasBeliefPollCapacity() {
		return beliefQueue.remainingCapacity()>0;
	}
	
	Belief belief=null;

	private Consumer<SignedData<Order>> orderUpdateObserver;

	private Consumer<Belief> beliefUpdateObserver;

	/** Package-visible test/diagnostic hook fired after ordered DATA staging. */
	private Consumer<Message> dataStageObserver;
	
	protected void loop() throws InterruptedException {

		// Wait for some new Beliefs to accumulate up to a given time
		Belief incomingBelief = awaitBelief();

		// If consensus is frozen pending a software upgrade, do no consensus work:
		// no merge, no block proposal, no Order publication, no broadcast. Voting on
		// order is independent of applying state, so a peer that froze only its state
		// executor would keep rubber-stamping blocks past the boundary it cannot
		// validate. Full freeze avoids that. See UPGRADE.md.
		if (server.isConsensusHalted()) return;

		// Try belief update.
		// Might include new blocks published by the peer
		// Returns true if peer's Order changed (and therefore needs immediate broadcast)
		boolean updated;
		try {
			updated=maybeUpdateBelief(incomingBelief);
		} catch (MissingDataException e) {
			// The merge reads only Orders this peer holds in full: it lacks data it must
			// hold, so it cannot proceed
			server.requestShutdown("Belief merge is missing data this peer must hold: "+e.getMissingHash(),e,true);
			// Stops this thread's loop now: the shutdown runs on a thread of its own
			close();
			return;
		}

		if (updated) {
			if (log.isDebugEnabled()) {
				log.debug("Belief updated cps="+Vectors.createLongs(belief.getOrder(server.getPeerKey()).getConsensusPoints()));
			}
		}
		
	
		try {
			// Do the broadcast
			maybeBroadcast(updated);
			
			// Persist Belief in all cases, even if we didn't announce
			// This is mainly in case we get missing data / sync requests for the Belief
			// This is super cheap if already persisted, so no problem in general for each loop
			belief=Cells.persist(belief, server.getStore());
		} catch (IOException e) {
			// We might get an error while shutting down, can ignore this
			if (!server.isLive()) return;
			throw Utils.sneakyThrow(e);
		}
		
		/* Update Belief after persistence. We want to be using
		 * Latest persisted version as much as possible
		 */
		server.updateBelief(belief);

	}


	/**
	 * Broadcasts consensus updates in two layers on each peer's ordered queue.
	 *
	 * <p>The inner layer is our own signed Order together with everything a receiver
	 * needs to use it, normally one new Block; it goes out whenever our Order changed,
	 * and as a small root-only keepalive every {@link #BELIEF_REBROADCAST_DELAY} when
	 * it did not. It is our consensus vote, so it is built and offered to every peer
	 * before any relay work starts. The outer layer is the Belief, built only once
	 * the Order has been offered; its delta omits whatever the Order update announced
	 * and so carries only the Orders of other peers, and their Blocks the first time
	 * this peer relays them. It goes out whenever any Order in the Belief changed and
	 * as a keepalive every {@link #BELIEF_FULL_BROADCAST_DELAY}. Each message is
	 * offered non-blockingly; a peer whose queue refuses one misses the rest of that
	 * update and recovers from later propagation.</p>
	 *
	 * @param updated true if our own Order changed this loop
	 * @return true if an update was offered to at least one peer
	 */
	protected boolean maybeBroadcast(boolean updated) {
		long ts=server.getTimestamp();
		boolean orderDue=updated||(ts>lastBroadcastTime+BELIEF_REBROADCAST_DELAY);
		boolean beliefDue=beliefChanged||(lastFullBroadcastTime<0)
			||(ts>lastFullBroadcastTime+BELIEF_FULL_BROADCAST_DELAY);
		if (!(orderDue||beliefDue)) return false;
		boolean offered=false;
		try {
			// Own Order first: offered to every peer before the Belief is even built
			if (orderDue) {
				List<Message> order=createOrderUpdateMessages();
				lastBroadcastTime=ts;
				offered|=offerUpdate(order);
			}
			// Belief second: relay anything not announced with our own Order
			if (beliefDue) {
				List<Message> beliefUpdate=createBeliefUpdateMessages();
				lastFullBroadcastTime=ts;
				beliefChanged=false;
				offered|=offerUpdate(beliefUpdate);
			}
		} catch (Exception e) {
			if (server.isLive()) {
				log.warn("Error attempting to create broadcast message",e);
			}
		}
		if (offered) beliefBroadcastCount++;
		return offered;
	}

	/**
	 * Offers an update to every peer in order. A peer that refuses one message receives
	 * no more of that update; later updates and other peers provide recovery.
	 *
	 * @param update Messages ending with the update's root
	 * @return true if at least one peer accepted the complete update
	 */
	private boolean offerUpdate(List<Message> update) {
		int n=update.size();
		if (n==0) return false;
		if (n==1) return server.manager.broadcast(update.get(0))>0;
		return server.manager.broadcastSequence(update)>0;
	}
	
	@Override public void start() {
		belief=server.getBelief();
		super.start();
	}
	
	/**
	 * Handle general Belief update, taking belief registered in newBeliefs
	 *
	 * @return true if Peer Belief changed, false otherwise
	 * @throws MissingDataException if this peer lacks data the merge reads
	 */
	protected boolean maybeUpdateBelief(Belief newBelief) {

		// we are in full consensus if there are no unconfirmed blocks after the consensus point
		//boolean inConsensus=peer.getConsensusPoint()==peer.getPeerOrder().getBlockCount();

		// only do belief merge if needed either after:
		// - publishing a new block
		// - incoming beliefs
		// - not in full consensus yet
		//if (inConsensus&&(!published) && newBeliefs.isEmpty()) return false;

		boolean updated = maybeMergeBeliefs(newBelief);
		
		// publish new Block if needed. Guaranteed to change Belief / Order if this happens
		boolean published=false;
		SignedData<Block>[] signedBlocks= server.transactionHandler.maybeGenerateBlocks(); 
		if (signedBlocks!=null) {
			try {
				belief=belief.proposeBlock(server.getKeyPair(),signedBlocks);
			} catch (Throwable e) {
				// The Blocks' transactions have left the queue: fail their clients, who would
				// otherwise wait for ever. Proposing changes this peer's own state, so the
				// failure itself still goes to the loop.
				server.transactionHandler.failBlocks(signedBlocks,e);
				throw e;
			}
			beliefChanged=true;
			published=true;
			
			if (log.isDebugEnabled()) {
				log.debug("Blocks proposed: "+Vectors.of((Object[])signedBlocks).map(sb->sb.getHash()));
			}
		}
		
		// Return true iff we published a new Block or updated our own Order
		if (updated||published) {
			observeBeliefUpdate(belief);
			return true;
		} else {
			return false;
		}
	}

	
	private void observeBeliefUpdate(Belief b) {
		Consumer<Belief> obs=beliefUpdateObserver;
		if (obs==null) return;
		try {
			obs.accept(b);
		} catch (Throwable e) {
			Utils.checkRequestFailure(e);
			// An observer's failure is not the Belief's: propagation carries on
			observerFailures.log(e,"Belief update observer failed");
		}
	}


	/**
	 * Checks for mergeable remote beliefs, and if found merge and update own
	 * belief. A newly received Order that cannot be merged is dropped, and the
	 * others are merged without it.
	 * @param newBeliefs New beliefs to merge
	 *
	 * @return True if Peer Belief Order was changed, false otherwise.
	 * @throws MissingDataException if this peer lacks data the merge reads
	 */
	protected boolean maybeMergeBeliefs(Belief... newBeliefs) {
		if ((newBeliefs==null)||(newBeliefs.length==0)) return false;
		long ts=server.getTimestamp();
		AKeyPair kp=server.getKeyPair();
		State state=server.getPeer().getConsensusState();
		Belief newBelief=tryMerge(ts,kp,state,newBeliefs);
		if (newBelief==null) {
			newBelief=tryMerge(ts,kp,state,withoutUnmergeableOrders(ts,kp,state,newBeliefs));
		}
		if (newBelief==null) {
			log.error("Belief merge failed even without the Orders that cannot be merged on their own");
			return false;
		}

		AccountKey key=kp.getAccountKey();
		Order oldOrder=belief.getOrder(key);
		Order newOrder=newBelief.getOrder(key);

		boolean ownChanged=false;
		if (oldOrder==null) {
			ownChanged=newOrder!=null;
		} else {
			if (newOrder==null) {
				ownChanged=true; // old order must have been removed
			} else {
				ownChanged=!newOrder.consensusEquals(oldOrder);
			}
		}
		// Any change to the Belief, including other peers' Orders, is worth relaying
		if (newBelief!=belief) beliefChanged=true;
		belief=newBelief;

		return ownChanged;
	}

	/**
	 * Merges Beliefs into this peer's Belief.
	 *
	 * @return Merged Belief, or null if the merge fails
	 * @throws MissingDataException if this peer lacks data the merge reads
	 */
	private Belief tryMerge(long ts, AKeyPair kp, State state, Belief... beliefs) {
		try {
			return BeliefMerge.create(belief,kp,ts,state).merge(beliefs);
		} catch (MissingDataException e) {
			// Not a failure of the received Orders: this peer's own fault, for the caller
			throw e;
		} catch (Throwable e) {
			// The merge builds a new Belief and changes nothing else, and only its work
			// held the stack or memory, so this peer's Belief is as it was, unless the JVM
			// itself has failed
			Utils.checkRequestFailure(e);
			mergeFailures.log(e,"Belief merge failed");
			return null;
		}
	}

	/**
	 * Gets a Belief with this peer's Orders and those received Orders that can each be
	 * merged into this peer's Belief on their own.
	 */
	private Belief withoutUnmergeableOrders(long ts, AKeyPair kp, State state, Belief[] beliefs) {
		Index<AccountKey, SignedData<Order>> current=belief.getOrders();
		Index<AccountKey, SignedData<Order>> usable=current;
		for (Belief b: beliefs) {
			if (b==null) continue;
			Index<AccountKey, SignedData<Order>> orders=b.getOrders();
			long n=orders.count();
			for (long i=0; i<n; i++) {
				MapEntry<AccountKey, SignedData<Order>> e=orders.entryAt(i);
				if (Cells.equals(e.getValue(),current.get(e.getKey()))) continue;
				if (tryMerge(ts,kp,state,Belief.create(current.assocEntry(e)))==null) {
					log.debug("Dropped an Order from peer {} that cannot be merged",e.getKey());
					continue;
				}
				usable=usable.assocEntry(e);
			}
		}
		return Belief.create(usable);
	}
	
	/**
	 * Awaits propagation messages or complete locally acquired Beliefs and accumulates
	 * all their usable Orders into one Belief for the upcoming merge.
	 *
	 * This method blocks for up to AWAIT_BELIEFS_PAUSE (30ms) waiting for a wire
	 * propagation event when no acquired Belief is ready. On a single-peer network
	 * no remote input arrives, so this waits the full duration each iteration.
	 *
	 * @return Incoming Belief, or null if nothing arrived within time window
	 * @throws InterruptedException
	 */
	private Belief awaitBelief() throws InterruptedException {
		ArrayList<Message> propagationMessages=new ArrayList<>();
		ArrayList<Belief> acquiredBeliefs=new ArrayList<>();

		// A complete local acquisition is ready immediately. Otherwise wait briefly for
		// one wire event. Recheck the local queue afterwards so an acquisition that
		// completed concurrently is included in this cycle.
		Belief acquired=beliefQueue.poll();
		Message firstEvent=null;
		if (acquired==null) {
			LoadMonitor.down();
			firstEvent=propagationQueue.poll(AWAIT_BELIEFS_PAUSE,TimeUnit.MILLISECONDS);
			LoadMonitor.up();
			acquired=beliefQueue.poll();
		} else {
			firstEvent=propagationQueue.poll();
		}

		if (acquired!=null) acquiredBeliefs.add(acquired);
		beliefQueue.drainTo(acquiredBeliefs);
		if (firstEvent!=null) propagationMessages.add(firstEvent);
		propagationQueue.drainTo(propagationMessages);
		if (acquiredBeliefs.isEmpty() && propagationMessages.isEmpty()) return null;

		if (log.isDebugEnabled()) {
			log.debug("Beliefs acquired: {}, propagation messages received: {}",
				acquiredBeliefs.size(),propagationMessages.size());
		}

		// Build a Map of current Orders. We compare incoming Orders to this
		// So that we can identify new information
		HashMap<AccountKey,SignedData<Order>> newOrders=belief.getOrdersHashMap();

		boolean anyOrderChanged=false;
		for (Message m: propagationMessages) {
			if (m.getType()==MessageType.DATA) {
				try {
					server.stageData(m);
					Consumer<Message> observer=dataStageObserver;
					if (observer!=null) observer.accept(m);
				} catch (Throwable e) {
					// Guard for each DATA message: a failure drops only this message, unless
					// the JVM itself has failed
					Utils.checkRequestFailure(e);
					rejectReceived(m,e,"Invalid DATA message");
				}
				continue;
			}
			boolean changed=mergeBeliefMessage(newOrders,m);
			if (changed) anyOrderChanged=true;
		}
		for (Belief b: acquiredBeliefs) {
			boolean changed=mergeAcquiredBelief(newOrders,b);
			if (changed) anyOrderChanged=true;
		}
		if (!anyOrderChanged) return null;

		Belief newBelief= Belief.create(newOrders);
		// log.info("New Belief received");
		return newBelief;
	}
	
	/**
	 * Merge a single Belief message into a map of accumulated latest Orders
	 * @param orders
	 * @param m
	 * @return true if there was any updated order Order, false otherwise
	 */
	protected boolean mergeBeliefMessage(HashMap<AccountKey, SignedData<Order>> orders, Message m) {
		try {
			try {
				ACell payload=m.getPayload(getStore());
				// Before merging, which keeps only what it takes: the sender counts every
				// cell as announced and leaves it out of the updates that follow
				stageUpdate(payload);
				return mergeBeliefValue(orders,payload,m);
			} catch (MissingDataException e) {
				log.debug("Missing data in Belief message "+m.getHash());
				fetchMissing(m);
			} catch (BadFormatException e1) {
				rejectReceived(m,e1,"Malformed Belief message");
			}
		} catch (Throwable e) {
			// Any other failure processing the message: reject the whole message, unless
			// the JVM itself has failed
			Utils.checkRequestFailure(e);
			rejectReceived(m,e,"Unusable Belief message");
		}
		return false;
	}

	/**
	 * Stores every cell a decoded update carries at STORED status, its top cell included.
	 * A failure stops it and is logged, not thrown.
	 *
	 * @param update Decoded payload of a received update
	 */
	private void stageUpdate(ACell update) {
		if (update==null) return;
		try {
			AStore store=getStore();
			ArrayList<ACell> stack=new ArrayList<>();
			// Each cell once, however often the update refers to it
			Set<ACell> seen=Collections.newSetFromMap(new IdentityHashMap<>());
			Cells.store(update,store);
			stack.add(update);
			while (!stack.isEmpty()) {
				ACell cell=stack.remove(stack.size()-1);
				if (!seen.add(cell)) continue;
				if (!cell.isEmbedded()) Cells.store(cell,store);
				int n=cell.getRefCount();
				for (int i=0; i<n; i++) {
					Ref<ACell> ref=cell.getRef(i);
					// Decoding refers directly to the cells an update carries, and through the
					// store to the rest: those are held already or missing
					if (!ref.isDirect()) continue;
					ACell child=ref.getValue();
					if (child!=null) stack.add(child);
				}
			}
		} catch (Throwable e) {
			// Keeping the cells only spares later fetches: the merge goes ahead all the
			// same, unless the JVM itself has failed
			Utils.checkRequestFailure(e);
			stageFailures.log(e,"Unable to keep a received update's cells");
		}
	}

	/** Accumulates one completed poll while preserving the complete-value invariant. */
	private boolean mergeAcquiredBelief(HashMap<AccountKey, SignedData<Order>> orders, Belief acquired) {
		try {
			return mergeBeliefValue(orders,acquired,null);
		} catch (MissingDataException e) {
			log.warn("Locally acquired Belief is incomplete; missing {}",e.getMissingHash());
		} catch (Throwable e) {
			// Guard for each polled Belief, unless the JVM itself has failed
			Utils.checkRequestFailure(e);
			rejectReceived(null,e,"Unusable polled Belief");
		}
		return false;
	}

	/**
	 * Validates and accumulates the signed Orders carried by one local value. The
	 * source message is present only for a partial wire value, allowing missing data
	 * to be requested from its sender. A polled Belief has no source message because
	 * acquisition has already completed it in the local store.
	 */
	private boolean mergeBeliefValue(HashMap<AccountKey, SignedData<Order>> orders, ACell value, Message source) {
		boolean changed=false;
		AccountKey myKey=server.getPeerKey();
		State state=server.getPeer().getConsensusState();
		beliefReceivedCount++;
		Collection<SignedData<Order>> incoming=Belief.extractOrders(value);
		for (SignedData<Order> so: incoming) {
			try {
				AccountKey key=so.getAccountKey();
				// Keep the best candidate for every Peer across the whole merge batch.
				if (Cells.equals(myKey,key)) continue;
				// The merge ignores Orders from unstaked keys, which anyone can make:
				// drop them before the costly signature check
				if (!BeliefMerge.isValidPeer(state,key)) continue;
				Order newOrder=RT.ensureCVM(so.getValue(),Order.class);
				if (newOrder==null) {
					rejectReceived(source,new BadFormatException("Signed value in a Belief is not an Order"),"Unusable Order");
					continue;
				}
				if (orders.containsKey(key)) {
					Order oldOrder=RT.ensureCVM(orders.get(key).getValue(),Order.class);
					if (!BeliefMerge.compareOrders(oldOrder,newOrder)) continue;
				}

				if (!so.checkSignature()) {
					// Held against the connection that sent it, not the key the Order names. The
					// rest of a Belief holding a false Order is not trusted either
					rejectReceived(source,new BadSignatureException("Bad Order signature",so),"Bad Order");
					break;
				}

				// The merge reads only Orders persisted in full
				so=Cells.persist(so,getStore());
				observeOrderUpdate(so);
				orders.put(key,so);
				changed=true;
			} catch (MissingDataException e) {
				awaitMissing(source,e.getMissingHash());
			} catch (IOException e) {
				log.warn("IO exception trying to merge Order",e);
				return changed;
			} catch (Throwable e) {
				// Any other failure processing this Order: reject it and carry on with the
				// rest, unless the JVM itself has failed
				Utils.checkRequestFailure(e);
				rejectReceived(source,e,"Unusable Order");
			}
		}
		return changed;
	}

	/**
	 * Handles a received value that refers to data not held yet: fetches it from the
	 * sender of a wire value, or reports a locally acquired value as incomplete.
	 */
	private void awaitMissing(Message source, Hash missing) {
		if (source!=null) {
			fetchMissing(source);
		} else {
			log.warn("Locally acquired Belief is incomplete at {}",missing);
		}
	}

	/**
	 * Rejects one received message or Order whose processing failed: it is dropped and
	 * logged, and bad input goes to the connection manager to handle.
	 */
	private void rejectReceived(Message source, Throwable e, String reason) {
		receiveFailures.log(e,"{}: {}",reason,(source==null)?"polled Belief":source.getConnection());
		// Every validation failure reaching here is in the received input itself. A polled
		// Belief's peer is held to account where it was polled
		if ((source!=null)&&(e instanceof ValidationException bad)) {
			server.getConnectionManager().handleBadInput(source.getConnection(),bad);
		}
	}

	/** Separate logs, so each site warns once of each kind of failure. */
	private final RequestFailures receiveFailures=new RequestFailures(log);
	private final RequestFailures mergeFailures=new RequestFailures(log);
	private final RequestFailures fetchFailures=new RequestFailures(log);
	private final RequestFailures stageFailures=new RequestFailures(log);
	private final RequestFailures observerFailures=new RequestFailures(log);

	/** Set while a fetch of data missing from an update is outstanding: there is at most one. */
	private final AtomicBoolean fetching=new AtomicBoolean();

	/** The latest fetch, completing once it has been dropped or its update queued again. */
	private volatile CompletableFuture<?> missingDataFetch=null;

	/**
	 * Fetches what an update lacks from the connection it arrived on: whoever sent a
	 * delta holds everything it refers to (CAD015). At most one fetch is outstanding.
	 * A miss while one is under way is ignored, because the same Orders usually arrive
	 * soon from other peers. The update's own cells were kept when it was received, so
	 * normally only what it left out is requested. Once that has arrived the update is
	 * queued again to merge.
	 * A fetch that fails, or takes longer than the poll delay, is dropped.
	 *
	 * <p>The request goes only to the sender, so a sender that never answers delays
	 * nothing beyond the poll: it holds the one fetch at most for the poll delay.</p>
	 *
	 * @param m Update that could not be merged
	 */
	private void fetchMissing(Message m) {
		AConnection connection=m.getConnection();
		if ((connection==null)||!connection.supportsMessage()) return;
		if (!fetching.compareAndSet(false,true)) return;
		try {
			AStore store=getStore();
			ACell update=m.getPayload(store);
			long timeout=server.getConnectionManager().getPollDelay();
			Acquiror acquiror=Acquiror.fromMessages(update.getHash(),store,hashes ->
				connection.request(Message.createDataRequest(null,hashes),timeout));
			missingDataFetch=acquiror.<ACell>getFuture()
				.orTimeout(timeout,TimeUnit.MILLISECONDS)
				.handle((value,error) -> {
					acquiror.close();
					fetching.set(false);
					if (error==null) {
						if (!queuePropagation(m)) log.debug("Propagation queue full: fetched update dropped");
					} else {
						log.debug("Fetch of missing update data dropped: {}",error.toString());
						// Fetched only from the update's sender, so bad input in the reply is its own
						if (error instanceof ValidationException bad) server.getConnectionManager().handleBadInput(connection,bad);
					}
					return null;
				});
		} catch (Throwable e) {
			// Any failure starting this update's fetch drops it, and must clear the flag
			// or no later fetch could start. First, so that holds even for a JVM failure
			fetching.set(false);
			Utils.checkRequestFailure(e);
			fetchFailures.log(e,"Unable to fetch missing update data");
		}
	}

	/**
	 * Gets the latest fetch of data missing from an update, or null if none has started.
	 * It completes once the fetch has been dropped or its update queued again.
	 */
	CompletableFuture<?> getMissingDataFetch() {
		return missingDataFetch;
	}

	private void observeOrderUpdate(SignedData<Order> so) {
		Consumer<SignedData<Order>> obs=orderUpdateObserver;
		if (obs!=null) {
			obs.accept(so);
		}
	}
	
	/**
	 * Maximum encoded bytes materialised for one update: what a peer's queue can
	 * absorb. Novelty beyond it stays in the store for receivers to pull.
	 */
	static final long MAX_UPDATE_BYTES=Config.PEER_OUTBOUND_QUEUE_BYTE_LIMIT;

	/**
	 * Creates the own-Order update: our signed Order and everything reachable from it
	 * that this peer has not yet announced, normally one new Block with its
	 * transactions. A Block of any size is carried: when the update does not fit one
	 * delta, DATA messages precede the Order on each peer's ordered queue. Every
	 * message is offered to every connected peer; a peer that misses one, or connects
	 * later, fetches what it lacks from whoever sends it the next update.
	 *
	 * @return the update's messages, ending with the Order; empty if this peer has no Order yet
	 */
	protected List<Message> createOrderUpdateMessages() throws IOException {
		AccountKey key=server.getPeerKey();
		Index<AccountKey, SignedData<Order>> orders = belief.getOrders();
		SignedData<Order> order=orders.get(key);
		if (order==null) return List.of();
		UpdateAccumulator update=newUpdate(order);
		order=Cells.announce(order,update,server.getStore());
		belief=belief.withOrders(orders.assoc(key,order));
		return finishUpdate(update,order,"Own Order");
	}

	/**
	 * Creates the Belief update: the Belief and everything not yet announced. Built
	 * only once the own-Order update has been offered, so it carries other peers'
	 * Orders and, the first time this peer relays them, their Blocks, never our own
	 * Order's novelty. Shaped like the Order update: one delta when it fits, else
	 * DATA messages then the Belief.
	 *
	 * @return the update's messages, ending with the Belief
	 */
	protected List<Message> createBeliefUpdateMessages() throws IOException {
		UpdateAccumulator update=newUpdate(belief);
		belief=Cells.announce(belief,update,server.getStore());
		return finishUpdate(update,belief,"Belief");
	}

	private UpdateAccumulator newUpdate(ACell root) {
		int limit=Config.getBeliefDeltaMessageSize(server.getConfig());
		return new UpdateAccumulator(limit,MAX_UPDATE_BYTES,root.getHash());
	}

	private List<Message> finishUpdate(UpdateAccumulator update, ACell root, String what) {
		List<Message> messages=update.toMessages(root);
		if (update.getOmittedCount()>0) {
			log.debug("{} novelty exceeds the {} byte update budget; {} cell(s) left to pull",
				what,MAX_UPDATE_BYTES,update.getOmittedCount());
		}
		return messages;
	}


	@Override
	protected String getThreadName() {
		return "Belief propagator thread on port "+server.getPort();
	}

	/**
	 * Sets the observer for order updates
	 * @param orderUpdateObserver New Observer for ORder updates
	 */
	public void setOrderUpdateObserver(Consumer<SignedData<Order>> orderUpdateObserver) {
		this.orderUpdateObserver = orderUpdateObserver;
	}

	void setDataStageObserver(Consumer<Message> observer) {
		this.dataStageObserver=observer;
	}
	
	/**
	 * Sets the observer for belief updates
	 * @param observer New Observer for Belief updates
	 */
	public void setBeliefUpdateObserver(Consumer<Belief> observer) {
		this.beliefUpdateObserver = observer;
	}
}
