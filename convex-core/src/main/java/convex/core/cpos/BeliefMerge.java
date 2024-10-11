package convex.core.cpos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.SignedData;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Counters;
import convex.core.util.Utils;

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
	private final Index<AccountKey, PeerStatus> peers;

	private BeliefMerge(Belief belief, AKeyPair peerKeyPair, long mergeTimestamp, State consensusState) {
		this.initialBelief=belief;
		this.state = consensusState;
		this.publicKey = peerKeyPair.getAccountKey();
		this.keyPair = peerKeyPair;
		this.timestamp = mergeTimestamp;
		this.peers = state.getPeers();
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
	 * @param beliefs An array of Beliefs. May contain nulls, which will be ignored.
	 * @return The updated merged belief with latest timestamp, or the same Belief if there is no change to Orders.
	 * @throws InvalidDataException In case of invalid data
	 */
	public Belief merge(Belief... beliefs) throws InvalidDataException {
		Counters.beliefMerge++;

		// accumulate combined list of latest Orders for all peers
		final Index<AccountKey, SignedData<Order>> accOrders = accumulateOrders(beliefs);

		// vote for new proposed chain
		final Index<AccountKey, SignedData<Order>> resultOrders = vote(accOrders);
		if (resultOrders == null) return initialBelief;

		// update my belief with the resulting Orders
		if (initialBelief.getOrders() == resultOrders) return initialBelief;
		final Belief result = new Belief(resultOrders);
		return result;
	}
	
	/**
	 * Merge orders from a second Belief
	 * @param b Belief from which to merge order
	 * @return Belief with updated orders (or the same Belief if unchanged)
	 */
	public Belief mergeOrders(Belief b) {
		Index<AccountKey, SignedData<Order>> orders = initialBelief.getOrders();
		Index<AccountKey, SignedData<Order>> newOrders=accumulateOrders(orders,b);
		return initialBelief.withOrders(newOrders);
	}
	
	/**
	 * Update a map of orders from all peers by merging from each Belief received
	 * @param belief Belief from which to merge orders
	 * @return Updated map of orders
	 */
	Index<AccountKey, SignedData<Order>> accumulateOrders(Index<AccountKey, SignedData<Order>> orders,Belief belief) {
		Index<AccountKey, SignedData<Order>> result=orders;
		
		Index<AccountKey, SignedData<Order>> bOrders = belief.getOrders();
		// Iterate over each Peer's ordering conveyed in this Belief
		long bcount=bOrders.count();
		for (long i=0; i<bcount; i++) {
			MapEntry<AccountKey,SignedData<Order>> be=bOrders.entryAt(i);
			ABlob key=be.getKey();
			
			if (!isValidPeer(key)) continue;
			
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
	 * Tests is a Peer key should be considered in the current belief merge. Includes testing for 
	 * minimum effective stake.
	 * 
	 * @param key Peer Key to test
	 * @return True if valid Peer, false otherwise.
	 */
	private boolean isValidPeer(ABlob key) {
		PeerStatus ps=peers.get(key);
		if (ps==null) return false;
		
		if (ps.getPeerStake()<CPoSConstants.MINIMUM_EFFECTIVE_STAKE) return false;
		
		return true;
	}

	/**
	 * Conducts a stake-weighted vote across a map of consistent chains, in the
	 * given merge context
	 * 
	 * @param accOrders Accumulated map for latest Orders received from all Peer Beliefs
	 * @param mc Merge context
	 * @param filteredChains
	 * @return Updates Orders, or null if no vote result (e.g. no voting stake available)
	 * @throws BadSignatureException @
	 */
	Index<AccountKey, SignedData<Order>> vote( final Index<AccountKey, SignedData<Order>> accOrders) {
		AccountKey myAddress = getAccountKey();

		// get current Order for this peer.
		final Order myOrder = getMyOrder();
		assert (myOrder != null); // we should always have a Order!


		// filter Orders for compatibility with current Order for inclusion in Voting Set
		// TODO: figure out what to do with new blocks filtered out?
		Index<AccountKey, SignedData<Order>> filteredOrders=accOrders;
		
		if (!CPoSConstants.ENABLE_FORK_RECOVERY) {
			filteredOrders= accOrders.filterValues(signedOrder -> {
				Order otherOrder = signedOrder.getValue();
				return myOrder.checkConsistent(otherOrder);
			});
		}

		// Current Consensus Point
		long consensusPoint = myOrder.getConsensusPoint();

		// Compute stake for all peers in consensus state
		HashMap<AccountKey, Double> weightedStakes = state.computeStakes();
		double totalStake = weightedStakes.get(null);

		// Extract unique proposed chains from provided map, computing vote for each.
		// compute the total weighted vote at the same time in accumulator
		// Peers with no stake should be ignored (might be old peers etc.)
		HashMap<Order, Double> stakedOrders = new HashMap<>(peers.size());
		double consideredStake = prepareStakedOrders(filteredOrders, weightedStakes, stakedOrders);

		// Get the winning chain for this peer, including new blocks encountered
		AVector<SignedData<Block>> winningBlocks = computeWinningOrder(stakedOrders, consensusPoint, consideredStake);
		if (winningBlocks == null) return null; // if no voting stake on any order
		
		winningBlocks=filterBlocks(winningBlocks,consensusPoint);

		// Take winning blocks into my Order
		// winning chain should have same consensus as my initial chain
		Order winningOrder = myOrder.withBlocks(winningBlocks);

		final Order consensusOrder = updateConsensus(winningOrder,stakedOrders, totalStake);

		Index<AccountKey, SignedData<Order>> resultOrders = filteredOrders;
		if (!consensusOrder.consensusEquals(myOrder)) {
			// We have a different Order to propose
			// First check how consistent this is with our current Order
			long match = consensusOrder.getBlocks().commonPrefixLength(myOrder.getBlocks());
			
			// We always want to replace our Order if consistent with our current proposal
			boolean shouldReplace=match>=myOrder.getProposalPoint();
			
			// If we need to switch proposals be careful!
			// We only do this after sufficient time has elapsed
			if (!shouldReplace) {
				// Replace if we observe a consensus elsewhere??
				//long newConsensusPoint=consensusOrder.getConsensusPoint();
				//if (newConsensusPoint>consensusPoint) {
				//	shouldReplace=true;
				//}
				
				long keepProposalTime=CPoSConstants.KEEP_PROPOSAL_TIME; // TODO: needs consideration, maybe randomise?
				if (getTimestamp()>myOrder.getTimestamp()+keepProposalTime) {
					shouldReplace=true;
				}
			}
			
			if (shouldReplace) {
				// Update timestamp
				long ts=getTimestamp();
				Order myNewOrder=consensusOrder.withTimestamp(ts);
			
				// Only sign and update Order if it has changed
				final SignedData<Order> signedOrder = sign(myNewOrder);
				resultOrders = resultOrders.assoc(myAddress, signedOrder);
			}
		}
		return resultOrders;
	}
	
	/**
	 * Compute the total stake for every distinct Order seen. Stores results in
	 * a map of Orders to staked value.
	 * 
	 * @param peerOrders A map of peer addresses to signed proposed Orders
	 * @param peerStakes A map of peers addresses to weighted stakes for each peer
	 * @param dest       Destination hashmap to store the stakes for each Order
	 * @return The total stake of all chains among peers under consideration 
	 */
	public static double prepareStakedOrders(AMap<AccountKey, SignedData<Order>> peerOrders,
			HashMap<AccountKey, Double> peerStakes, HashMap<Order, Double> dest) {
		return peerOrders.reduceValues((acc, signedOrder) -> {
			// Get the Order for this peer
			Order order = signedOrder.getValue();
			AccountKey cAddress = signedOrder.getAccountKey();
			Double cStake = peerStakes.get(cAddress);
			if ((cStake == null) || (cStake == 0.0)) return acc;
			Double stake = dest.get(order);
			if (stake == null) {
				dest.put(order, cStake); // new Order to consider
			} else {
				dest.put(order, stake + cStake); // add stake to existing Order
			}
			return acc + cStake;
		}, 0.0);
	}

	/**
	 * Gets an ordered list of new blocks from a collection of orderings. Ordering is a
	 * partial order based on when a block is first observed. This is an important
	 * heuristic (to avoid re-ordering new blocks from the same peer).
	 */
	private ArrayList<SignedData<Block>> collectNewBlocks(Collection<AVector<SignedData<Block>>> orders, long consensusPoint) {
		// We want to preserve order, remove duplicates
		HashSet<SignedData<Block>> newBlocks = new HashSet<>();
		ArrayList<SignedData<Block>> newBlocksOrdered = new ArrayList<>();
		for (AVector<SignedData<Block>> blks : orders) {
			if (blks.count()<=consensusPoint) continue;
			Iterator<SignedData<Block>> it = blks.listIterator(consensusPoint);
			while (it.hasNext()) {
				SignedData<Block> b = it.next();
				if (!newBlocks.contains(b)) {
					newBlocks.add(b);
					newBlocksOrdered.add(b);
				}
			}
		}
		return newBlocksOrdered;
	}

	/**
	 * Compute the new winning Order for this Peer, including any new blocks
	 * encountered
	 * 
	 * @param stakedOrders Amount of stake on each distinct Order
	 * @param consensusPoint Current consensus point
	 * @param initialTotalStake Total stake under consideration
	 * @return Vector of Blocks in winning Order
	 */
	public AVector<SignedData<Block>> computeWinningOrder(HashMap<Order, Double> stakedOrders, long consensusPoint,
			double initialTotalStake) {
		assert (!stakedOrders.isEmpty());
		// Get the Voting Set. Will be updated each round to winners of previous round.
		HashMap<AVector<SignedData<Block>>, Double> votingSet = combineToBlocks(stakedOrders);

		// Accumulate new blocks.
		ArrayList<SignedData<Block>> newBlocksOrdered = collectNewBlocks(votingSet.keySet(), consensusPoint);

		double totalStake = initialTotalStake;
		long point = consensusPoint;
		
		findWinner:
		while (votingSet.size() > 1) {
			// Accumulate candidate winning Blocks for this round, indexed by next Block
			HashMap<SignedData<Block>, HashMap<AVector<SignedData<Block>>, Double>> blockVotes = new HashMap<>();
			
			for (Map.Entry<AVector<SignedData<Block>>, Double> me : votingSet.entrySet()) {
				AVector<SignedData<Block>> blocks = me.getKey();
				long cCount = blocks.count();

				if (cCount <= point) continue; // skip Ordering with insufficient blocks: cannot win this round

				SignedData<Block> b = blocks.get(point);

				// update hashmap of Orders voting for each block (i.e. agreed on current Block)
				HashMap<AVector<SignedData<Block>>, Double> agreedOrders = blockVotes.get(b);
				if (agreedOrders == null) {
					agreedOrders = new HashMap<>();
					blockVotes.put(b, agreedOrders);
				}
				Double stake = me.getValue();
				agreedOrders.put(blocks, stake);
				if (stake > totalStake * 0.5) {
					// have a winner for sure, no point continuing so populate final Voting set and break
					votingSet.clear();
					votingSet.put(blocks, stake);
					break findWinner; 
				}
			}

			if (blockVotes.size() == 0) {
				// we have multiple chains, but no more blocks - so they should be all equal
				// we can break loop and continue with an arbitrary choice
				break findWinner;
			}

			Map.Entry<SignedData<Block>, HashMap<AVector<SignedData<Block>>, Double>> winningResult = null;
			double winningVote = Double.NEGATIVE_INFINITY;
			for (Map.Entry<SignedData<Block>, HashMap<AVector<SignedData<Block>>, Double>> me : blockVotes.entrySet()) {
				HashMap<AVector<SignedData<Block>>, Double> agreedChains = me.getValue();
				double blockVote = computeVote(agreedChains);
				if ((winningResult==null)||(blockVote > winningVote)) {
					winningVote = blockVote;
					winningResult = me;
				} else if (blockVote==winningVote) {
					// tie break special case, choose lowest hash
					if (me.getKey().getHash().compareTo(winningResult.getKey().getHash())<0) {
						winningResult=me;
					}
				}
			}

			if (winningResult==null) throw new Error("null winning Order shouldn't happen!");
			votingSet = winningResult.getValue(); // Update Orderings to be included in next round
			totalStake = winningVote; // Total Stake among winning Orderings
			
			// advance to next block position for next round
			point++; 
		}
		
		if (votingSet.size() == 0) {
			// no vote for any Order. Might happen if the peer doesn't have any stake
			// and doesn't have any Orders from other peers with stake?
			return null;
		}
		AVector<SignedData<Block>> winningBlocks = votingSet.keySet().iterator().next();

		// add new blocks back to winning Order (if not already included)
		winningBlocks = appendNewBlocks(winningBlocks, newBlocksOrdered, consensusPoint);
		
		return winningBlocks;
	}
	
	/**
	 * Filter blocks based on validity / timestamps
	 * @param blks Blocks to filer
	 * @param cp Point at which to start filtering (should be consensus point)
	 * @return Updated blocks, or same blocks if no change
	 */
	private AVector<SignedData<Block>> filterBlocks(AVector<SignedData<Block>> blks,
			long cp) {
		// TODO Filter from consensus point onwards
		return blks;
	}

	/**
	 * Combine stakes from multiple orders to a single stake for each distinct Block ordering.
	 * 
	 * @param stakedOrders
	 * @return Map of AVector<Block> to total stake
	 */
	private static HashMap<AVector<SignedData<Block>>, Double> combineToBlocks(HashMap<Order, Double> stakedOrders) {
		HashMap<AVector<SignedData<Block>>, Double> result = new HashMap<>();
		for (Map.Entry<Order, Double> e : stakedOrders.entrySet()) {
			Order c = e.getKey();
			Double stake = e.getValue();
			AVector<SignedData<Block>> blocks = c.getBlocks();
			Double acc = result.get(blocks);
			if (acc == null) {
				result.put(blocks, stake);
			} else {
				result.put(blocks, acc + stake);
			}
		}
		return result;
	}

	private final AVector<SignedData<Block>> appendNewBlocks(AVector<SignedData<Block>> blocks, ArrayList<SignedData<Block>> newBlocksOrdered,
			long consensusPoint) {
		HashSet<SignedData<Block>> newBlocks = new HashSet<>();
		newBlocks.addAll(newBlocksOrdered);

		// exclude new blocks already in the base Order
		// TODO: what about blocks already in consensus?
		// Probably need to check last block time from Peer
		long scanStart=Math.min(blocks.count(), consensusPoint);
		Iterator<SignedData<Block>> it = blocks.listIterator(scanStart);
		while (it.hasNext()) {
			newBlocks.remove(it.next());
		}
		newBlocksOrdered.removeIf(sb -> {
			// We ignore blocks that don't look valid for current state
			BlockResult br=state.checkBlock(sb);
			if (br!=null) {
				return true;
			}
			return !newBlocks.contains(sb);
		});

		// sort new blocks by timestamp and append to winning Order
		// must be a stable sort to maintain order from equal timestamps
		newBlocksOrdered.sort(Block.TIMESTAMP_COMPARATOR);

		AVector<SignedData<Block>> fullBlocks = blocks.appendAll(newBlocksOrdered);
		return fullBlocks;
	}

	/**
	 * Updates Consensus based on stake weighted voting results.
	 * @param winningOrder Winning order from voting
	 * @param stakedOrders Staked Orders from Peers
	 * @param THRESHOLD Threshold from Consensus
	 * @return updated Order
	 */
	private Order updateConsensus(Order order, HashMap<Order, Double> stakedOrders, double totalStake) {
		final double THRESHOLD = totalStake * CPoSConstants.CONSENSUS_THRESHOLD;
		
		for (int level=1; level<CPoSConstants.CONSENSUS_LEVELS; level++) {
			order = updateLevel(order,level, stakedOrders, THRESHOLD);
		}
		
		return order;
	}
	
	/**
	 * Updates the consensus level for the winning Order, given an overall map of
	 * staked orders and consensus threshold.
	 */
	private Order updateLevel(Order winnningOrder, int level, HashMap<Order, Double> stakedOrders, double THRESHOLD) {
		AVector<SignedData<Block>> proposedBlocks = winnningOrder.getBlocks();
		ArrayList<Order> agreedChains = Utils.sortListBy(new Function<Order, Long>() {
			@Override
			public Long apply(Order c) {
				// scoring function scores by level of proposed agreement with proposed chain
				// in order to sort by length of matched proposals
				long blockMatch = proposedBlocks.commonPrefixLength(c.getBlocks());

				long minPrevious = Math.min(winnningOrder.getConsensusPoint(level-1), c.getConsensusPoint(level-1));

				// Match length is how many blocks agree with winning order at previous consensus level
				long match = Math.min(blockMatch, minPrevious);
				
				return -match;
			}
		}, stakedOrders.keySet());
		int numAgreed = agreedChains.size();
		// assert(proposedChain.equals(agreedChains.get(0)));
		double accumulatedStake = 0.0;
		int i = 0;
		for (; i < numAgreed; i++) {
			Order c = agreedChains.get(i);
			Double chainStake = stakedOrders.get(c);
			accumulatedStake += chainStake;
			if (accumulatedStake > THRESHOLD) break;
		}

		if (i < numAgreed) {
			// we have a consensus since we hit the stake threshold!
			Order lastAgreed = agreedChains.get(i); // Order that tipped us over the threshold
			long prefixMatch = winnningOrder.getBlocks().commonPrefixLength(lastAgreed.getBlocks());
			long previousLevel = Math.min(winnningOrder.getConsensusPoint(level-1), lastAgreed.getConsensusPoint(level-1));
			long newPoint = Math.min(prefixMatch, previousLevel);
			return winnningOrder.withConsensusPoint(level,newPoint);
		} else {
			return winnningOrder;
		}
	}

	/**
	 * Computes the total vote for all entries in a HashMap
	 * 
	 * @param <V> The type of values used as keys in the HashMap
	 * @param m   A map of values to votes
	 * @return The total voting stake
	 */
	public static <V> double computeVote(HashMap<V, Double> m) {
		double result = 0.0;
		for (Map.Entry<V, Double> me : m.entrySet()) {
			result += me.getValue();
		}
		return result;
	}

	/**
	 * Gets the Order for the current peer specified by a MergeContext in this
	 * Belief
	 * 
	 * @param mc Merge context
	 * @return Order for current Peer, or null if not found
	 * @throws BadSignatureException 
	 */
	private Order getMyOrder() {
		Index<AccountKey, SignedData<Order>> orders = initialBelief.getOrders();
		SignedData<Order> signed = (SignedData<Order>) orders.get(publicKey);
		if (signed == null) return null;
		return signed.getValue();
	}
	
	/**
	 * Checks if a new Order should replace the current order when collecting Peer orders
	 * @param oldOrder Current Order
	 * @param newOrder Potential new ORder
	 * @return True if new Order should replace old order
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
			// Don't replace if equal
			if (oldOrder.equals(newOrder)) return false;
			
			// This probably shouldn't happen if peers are sticking to timestamps
			// But we compare anyway
			// Prefer advanced consensus
			for (int level=CPoSConstants.CONSENSUS_LEVELS-1; level>=1; level--) {
				if (newOrder.getConsensusPoint(level)>oldOrder.getConsensusPoint(level)) return true;
			}
			
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
	private Index<AccountKey, SignedData<Order>> accumulateOrders(Belief[] beliefs) {
		// Initialise result with existing Orders from this Belief
		Index<AccountKey, SignedData<Order>> result = initialBelief.getOrders();
		
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
		return SignedData.sign(keyPair, value);
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
