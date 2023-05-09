package convex.core;

import convex.core.crypto.AKeyPair;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.BlobMap;
import convex.core.data.MapEntry;
import convex.core.data.SignedData;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Counters;

/**
 * Class representing the context to be used for a Belief merge/update function. This
 * context must be created by a Peer to perform a valid Belief merge. It can be safely
 * discarded after use.
 * 
 * SECURITY: contains a hot key pair! We need this to sign new belief updates
 * including any chains we want to communicate. Don't allow this to leak
 * anywhere!
 *
 */
public class BeliefMerge {

	private final Belief initialBelief;
	private final AccountKey publicKey;
	private final State state;
	private final AKeyPair keyPair;
	private final long timestamp;

	private BeliefMerge(Belief belief, AKeyPair peerKeyPair, long mergeTimestamp, State consensusState) {
		this.initialBelief=belief;
		this.state = consensusState;
		this.publicKey = peerKeyPair.getAccountKey();
		this.keyPair = peerKeyPair;
		this.timestamp = mergeTimestamp;
	}

	/**
	 * Create a Belief Merge context
	 * @param belief Initial Belief
	 * @param kp Keypair for Belief Merge
	 * @param timestamp Timestamp
	 * @param s Consensus State
	 * @return New MergeContext instance
	 */
	public static BeliefMerge create(Belief belief, AKeyPair kp, long timestamp, State s) {
		return new BeliefMerge(belief, kp, timestamp, s);
	}
	
	/**
	 * The Belief merge function
	 * 
	 * @param mc MergeContext for Belief Merge
	 * @param beliefs An array of Beliefs. May contain nulls, which will be ignored.
	 * @return The updated merged belief with latest timestamp, or the same Belief if there is no change to Orders.
	 * @throws InvalidDataException In case of invalid data
	 */
	public Belief merge(Belief... beliefs) throws InvalidDataException {
		Counters.beliefMerge++;

		// accumulate combined list of latest Orders for all peers
		final BlobMap<AccountKey, SignedData<Order>> accOrders = accumulateOrders(beliefs);

		// vote for new proposed chain
		final BlobMap<AccountKey, SignedData<Order>> resultOrders = initialBelief.vote(this, accOrders);
		if (resultOrders == null) return initialBelief;

		// update my belief with the resulting Orders
		if (initialBelief.getOrders() == resultOrders) return initialBelief;
		final Belief result = new Belief(resultOrders);

		return result;
	}
	
	/**
	 * Update a map of orders from all peers by merging from each Belief received
	 * @param belief Belief from which to merge orders
	 * @return Updated map of orders
	 */
	BlobMap<AccountKey, SignedData<Order>> accumulateOrders(BlobMap<AccountKey, SignedData<Order>> orders,Belief belief) {
		BlobMap<AccountKey, SignedData<Order>> result=orders;
		
		BlobMap<AccountKey, SignedData<Order>> bOrders = belief.getOrders();
		// Iterate over each Peer's ordering conveyed in this Belief
		long bcount=bOrders.count();
		for (long i=0; i<bcount; i++) {
			MapEntry<AccountKey,SignedData<Order>> be=bOrders.entryAt(i);
			ABlob key=be.getKey();
			
			SignedData<Order> b=be.getValue();
			if (b == null) continue; // If there is no incoming Order skip, though shouldn't happen
			Order bc = b.getValue();
			if (bc.getTimestamp()>getTimestamp()) continue; // ignore future Orders
			
			SignedData<Order> a=result.get(key);
			if (a == null) {
				// This is a new order to us, so include if valid
				result=result.assocEntry(be); 
				continue;
			}
			
			if (a.equals(b)) continue; // PERF: fast path for no changes

			Order ac = a.getValue();

			boolean shouldReplace=compareOrders(ac,bc);
			if (shouldReplace) {

				
				result=result.assocEntry(be); 
				continue;
			}
		}
		return result;
	}
	


	
	/**
	 * Checks if a new Order should replace the current order when collecting Peer orders
	 * @param oldOrder Current Order
	 * @param newOrder Potential new ORder
	 * @return
	 */
	public static boolean compareOrders(Order oldOrder, Order newOrder) {
		if (newOrder==null) return false;
		if (oldOrder==null) return true;
		
		int tsComp=Long.compare(oldOrder.getTimestamp(), newOrder.getTimestamp());
		if (tsComp>0) return false; // Keep current order if more recent
		
		if (tsComp<0) {
			// new Order is more recent, so switch to this
			return true;
		} else {
			// This probably shouldn't happen if peers are sticking to timestamps
			// But we compare anyway
			// Prefer advanced consensus
			if (newOrder.getConsensusPoint()>oldOrder.getConsensusPoint()) return true;
			
			// Then prefer advanced proposal
			if (newOrder.getProposalPoint()>oldOrder.getProposalPoint()) return true;

			// Finally prefer more blocks
			AVector<SignedData<Block>> abs=oldOrder.getBlocks();
			AVector<SignedData<Block>> bbs=newOrder.getBlocks();
			if(abs.count()<bbs.count()) return true;
		}
		return false;
	}
	

	/**
	 * Assemble the latest map of Orders from all peers by merging from each Belief received
	 * @param beliefs Set of Beliefs from which to merge orders
	 * @return
	 */
	private BlobMap<AccountKey, SignedData<Order>> accumulateOrders(Belief[] beliefs) {
		// Initialise result with existing Orders from this Belief
		BlobMap<AccountKey, SignedData<Order>> result = initialBelief.getOrders();
		
		// Iterate over each received Belief
		for (Belief belief : beliefs) {
			if (belief == null) continue; // ignore null Beliefs, might happen if invalidated
			
			result=accumulateOrders(result, belief);
		}
		return result;
	}

	/**
	 * Get the address of the current Peer (the one performing the merge)
	 * 
	 * @return The Address of the peer.
	 */
	public AccountKey getAccountKey() {
		return publicKey;
	}

	/**
	 * Sign a value using the keypair for this MergeContext
	 * @param <T> Type of value
	 * @param value Value to sign
	 * @return Signed value
	 */
	public <T extends ACell> SignedData<T> sign(T value) {
		return SignedData.create(keyPair, value);
	}

	/**
	 * Gets the timestamp of this merge
	 * @return Timestamp
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Updates the timestamp of this MergeContext
	 * @param newTimestamp New timestamp
	 * @return Updated MergeContext
	 */
	public BeliefMerge withTimestamp(long newTimestamp) {
		if (timestamp==newTimestamp) return this;
		return new BeliefMerge(initialBelief,keyPair, newTimestamp, state);
	}

	/**
	 * Gets the Consensus State for this merge
	 * @return Consensus State
	 */
	public State getConsensusState() {
		return state;
	}

}
