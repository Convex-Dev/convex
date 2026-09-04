package convex.peer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.cpos.Belief;
import convex.core.cpos.BeliefMerge;
import convex.core.cpos.Block;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.message.AConnection;
import convex.core.message.Message;
import convex.core.message.BoundedMessageQueue;
import convex.core.message.MessageType;
import convex.core.util.LoadMonitor;
import convex.core.util.Utils;

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
	
	/**
	 * Queue on which Beliefs messages are received from trusted connections
	 */
	// TODO: use config if provided
	private final BoundedMessageQueue beliefQueue = new BoundedMessageQueue(
		Config.BELIEF_QUEUE_SIZE,Config.BELIEF_QUEUE_BYTE_LIMIT);

	/**
	 * Small bounded queue for Beliefs from unverified inbound connections.
	 * Best-effort buffering during the brief verification round-trip.
	 */
	private final BoundedMessageQueue untrustedBeliefQueue = new BoundedMessageQueue(
		Config.UNTRUSTED_BELIEF_QUEUE_SIZE,Config.UNTRUSTED_BELIEF_QUEUE_BYTE_LIMIT);

	
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
	 * Queues a Belief Message for processing
	 * @param beliefMessage Belief Message to queue
	 * @return True if Belief is queued successfully
	 */
	public boolean queueBelief(Message beliefMessage) {
		if (log.isTraceEnabled()) {
			log.trace("Belief queued "+server.getPort()+" : "+beliefMessage.getHash());
		}
		return beliefQueue.offer(beliefMessage);
	}

	/** Interval between attempts to queue a held-back trusted message, in milliseconds. */
	private static final long QUEUE_WAIT_SLICE=1000;

	/** Time after which a held-back trusted message is logged, in milliseconds. */
	private static final long QUEUE_WAIT_WARNING=10_000;

	/**
	 * Queues a Belief or DATA message from a trusted connection, waiting as long as
	 * it takes for the queue to have room. Consensus traffic is never timed out on the
	 * receiving side: the caller has paused the connection, so waiting applies
	 * backpressure to the sender, whose own queue decides what to drop. Gives up only
	 * if this server stops or the connection closes.
	 *
	 * @param message Message to queue
	 * @return true once queued, false if the server stopped or the connection closed first
	 */
	boolean queueBeliefBlocking(Message message) {
		AConnection conn=message.getConnection();
		long waited=0;
		try {
			while (server.isLive() && (conn==null || !conn.isClosed())) {
				if (beliefQueue.offer(message,QUEUE_WAIT_SLICE,TimeUnit.MILLISECONDS)) return true;
				waited+=QUEUE_WAIT_SLICE;
				if (waited==QUEUE_WAIT_WARNING) {
					log.warn("Belief queue full for {} ms, holding back {}",waited,conn);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return false;
	}

	/** Test hook: removes and returns the oldest queued Belief message, or null if none. */
	Message pollQueuedBelief() {
		return beliefQueue.poll();
	}

	/**
	 * Queues a Belief from an unverified connection on a best-effort basis.
	 * Silently drops if the small untrusted queue is full.
	 * @param beliefMessage Belief Message to queue
	 * @return True if Belief is queued successfully
	 */
	public boolean queueUntrustedBelief(Message beliefMessage) {
		return untrustedBeliefQueue.offer(beliefMessage);
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
		boolean updated= maybeUpdateBelief(incomingBelief);
		
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
	 * as a keepalive every {@link #BELIEF_FULL_BROADCAST_DELAY}, and is withheld from
	 * a peer whose outbound queue is under pressure. Ordered delivery means data
	 * always precedes the Order that commits it, so nothing is ever superseded or
	 * resent. A peer whose queue cannot take an update requests the data it later
	 * finds missing.</p>
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
				offered|=offerUpdate(order,false);
			}
			// Belief second: relay only, so a peer under outbound pressure is skipped
			if (beliefDue) {
				List<Message> beliefUpdate=createBeliefUpdateMessages();
				lastFullBroadcastTime=ts;
				beliefChanged=false;
				offered|=offerUpdate(beliefUpdate,true);
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
	 * Offers an update to every peer in order: one message directly, a longer sequence
	 * with its root as the fallback, so a peer whose queue cannot take the data still
	 * learns the root and pulls what it lacks.
	 *
	 * @param update Messages ending with the update's root
	 * @param skipBusy true to withhold the update from peers under outbound pressure
	 * @return true if at least one peer took the root
	 */
	private boolean offerUpdate(List<Message> update, boolean skipBusy) {
		int n=update.size();
		if (n==0) return false;
		if (n==1) return server.manager.broadcast(update.get(0),skipBusy)>0;
		var result=server.manager.broadcastSequence(update,update.get(n-1),skipBusy);
		if (result.fallback()>0 || result.dropped()>0) {
			log.debug("Update of {} messages: {} peer(s) took the root only, {} took nothing",
				n,result.fallback(),result.dropped());
		}
		return result.complete()+result.fallback()>0;
	}
	
	@Override public void start() {
		belief=server.getBelief();
		super.start();
	}
	
	/**
	 * Handle general Belief update, taking belief registered in newBeliefs
	 *
	 * @return true if Peer Belief changed, false otherwise
	 * @throws InterruptedException
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
			belief=belief.proposeBlock(server.getKeyPair(),signedBlocks);
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
		if (obs!=null) {
			obs.accept(b);
		}
	}


	/**
	 * Checks for mergeable remote beliefs, and if found merge and update own
	 * belief.
	 * @param newBeliefs New beliefs to merge 
	 *
	 * @return True if Peer Belief Order was changed, false otherwise.
	 */
	protected boolean maybeMergeBeliefs(Belief... newBeliefs) {
		if ((newBeliefs==null)||(newBeliefs.length==0)) return false;
		try {
			long ts=server.getTimestamp();
			AKeyPair kp=server.getKeyPair();
			BeliefMerge mc = BeliefMerge.create(belief,kp, ts, server.getPeer().getConsensusState());
			Belief newBelief = mc.merge(newBeliefs);

			AccountKey key=mc.getAccountKey();
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
		} catch (MissingDataException e) {
			// Shouldn't happen if beliefs are correctly persisted
			// e.printStackTrace();
			throw new Error("Missing data in belief merge: " + e.getMissingHash().toHexString(), e);
		} catch (InvalidDataException e) {
			// Shouldn't happen if Beliefs are already validated
			// e.printStackTrace();
			throw new Error("Invalid data in belief merge!", e);
		}
	}
	
	/**
	 * Await incoming Belief for all incoming belief merges / potential update. This merges multiple incoming beliefs into a single Belief
	 * which compacts the number of incoming orders for the upcoming Belief Merge.
	 *
	 * This method blocks for up to AWAIT_BELIEFS_PAUSE (30ms) waiting for remote
	 * peer beliefs. On a single-peer network no beliefs ever arrive, so this always
	 * waits the full duration — adding 30ms of latency per loop iteration even when
	 * transactions are pending in the transactionQueue.
	 *
	 * @return Incoming Belief, or null if nothing arrived within time window
	 * @throws InterruptedException
	 */
	private Belief awaitBelief() throws InterruptedException {
		ArrayList<Message> beliefMessages=new ArrayList<>();

		// Pause to accumulate incoming beliefs from remote peers before merging.
		// On a single-peer network this always times out after AWAIT_BELIEFS_PAUSE ms.
		LoadMonitor.down();
		Message firstEvent=beliefQueue.poll(AWAIT_BELIEFS_PAUSE, TimeUnit.MILLISECONDS);
		LoadMonitor.up();
		if (firstEvent==null) return null; // nothing from trusted peers, don't wake up for untrusted alone

		// Drain all trusted beliefs
		beliefMessages.add(firstEvent);
		beliefQueue.drainTo(beliefMessages);

		// Peek at one untrusted belief per cycle (non-blocking, never wait)
		Message untrusted=untrustedBeliefQueue.poll();
		if (untrusted!=null) beliefMessages.add(untrusted);

		if (log.isDebugEnabled()) {
			log.debug("Belief Messages received: "+beliefMessages.size());
		}

		// Build a Map of current Orders. We compare incoming Orders to this
		// So that we can identify new information
		HashMap<AccountKey,SignedData<Order>> newOrders=belief.getOrdersHashMap();

		boolean anyOrderChanged=false;
		for (Message m: beliefMessages) {
			if (m.getType()==MessageType.DATA) {
				try {
					server.stageData(m);
					Consumer<Message> observer=dataStageObserver;
					if (observer!=null) observer.accept(m);
				} catch (Exception e) {
					log.warn("Unable to stage peer DATA message",e);
					server.getConnectionManager().alertBadMessage(m,"Invalid DATA message");
				}
				continue;
			}
			boolean changed=mergeBeliefMessage(newOrders,m);
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
		boolean changed=false;
		AccountKey myKey=server.getPeerKey();
		
		try {
			// Add to map of new Beliefs received for each Peer
			beliefReceivedCount++;			
			try {
				ACell payload=m.getPayload(getStore());
				// log.info("Merging Belief message: "+Cells.getHash(payload));
				Collection<SignedData<Order>> a = Belief.extractOrders(payload);
				for (SignedData<Order> so:a ) {
					AccountKey key=so.getAccountKey();
					try {
						
						// Check if this Order could replace existing Order
						if (Cells.equals(myKey, key)) continue; // skip own order
						if (orders.containsKey(key)) {
							Order newOrder=so.getValue();
							Order oldOrder=orders.get(key).getValue();


							boolean replace=BeliefMerge.compareOrders(oldOrder, newOrder);
							if (!replace) continue;
						}
						
						// TODO: check if Peer key is valid in current state?
						
						// Check signature before we accept Order
						if (!so.checkSignature()) {
							log.warn("Bad Order signature");
							server.getConnectionManager().alertBadMessage(m,"Bad Order Signature!!");
							break;
						};
						
						
						// Ensure we can persist newly received Order
						so=Cells.persist(so, server.getStore());
						observeOrderUpdate(so);
						orders.put(key, so);
						changed=true;
					} catch (MissingDataException e) {
						// Something missing in received Belief. This is expected for
						// Partial Belief update messages
						server.getConnectionManager().alertMissing(m,e,key);
					} catch (IOException e) {
						// This is pretty bad, probably we lost the store?
						// We certainly can't propagate the newly received order
						// throw new Error(e);
						log.warn("IO exception trying to merge Order",e);
						return changed;
					}
				}
			} catch (MissingDataException e) {
				log.debug("Missing data in Belief message "+m.getHash());
				server.getConnectionManager().alertMissing(m,e,null);
			} catch (BadFormatException e1) {
				log.debug("Malformed Belief message");
			}
		} catch (ClassCastException e) {
			// Bad message from Peer
			server.getConnectionManager().alertBadMessage(m,Utils.getClassName(e)+" merging Belief!!");
		}  
		return changed;
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
	 * delta, DATA messages precede the Order on each peer's ordered queue. Announcing
	 * here is honest because every message is offered to every peer and never
	 * superseded.
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
