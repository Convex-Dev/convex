package convex.core;

import java.util.Map;

import convex.core.crypto.AKeyPair;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;

/**
 * <p>
 * Immutable class representing the encapsulated state of a Peer
 * </p>
 * 
 * SECURITY:
 * <ul>
 * <li>Needs to contain the Peer's unlocked private key for online signing.</li>
 * <li>Manages Peer state transitions given external events. Must do so
 * correctly.</li>
 * </ul>
 * 
 * <p>
 * Must have at least one state, the initial state. New states will be added as
 * consensus updates happen.
 * </p>
 * 
 * 
 * "Don't worry about what anybody else is going to do. The best way to predict
 * the future is to invent it." - Alan Kay
 */
public class Peer {
	/** This Peer's address */
	private final Address address;

	/** This Peer's key pair */
	private final AKeyPair keyPair;

	/** The latest merged belief */
	private final SignedData<Belief> belief;

	/**
	 * The latest observed timestamp. This is increased by the Server polling the
	 * local clock.
	 */
	private final long timestamp;

	/**
	 * Vector of states
	 */
	private final AVector<State> states;

	/**
	 * Vector of results
	 */
	private final AVector<BlockResult> blockResults;

	private Peer(AKeyPair kp, SignedData<Belief> belief, AVector<State> states, AVector<BlockResult> results,
			long timeStamp) {
		this.keyPair = kp;
		this.address = kp.getAddress();
		this.belief = belief;
		this.states = states;
		this.blockResults = results;
		this.timestamp = timeStamp;
	}

	public static Peer create(AKeyPair pEER0, State initialState) {
		Belief belief = Belief.createSingleOrder(pEER0);
		SignedData<Belief> sb = pEER0.signData(belief);
		return new Peer(pEER0, sb, Vectors.of(initialState), Vectors.empty(), initialState.getTimeStamp());
	}

	/**
	 * Creates a new Peer instance at server startup using the provided
	 * configuration
	 * 
	 * @param config
	 * @return A new Peer instance
	 */
	public static Peer createStartupPeer(Map<Keyword, Object> config) {
		State initialState = (State) config.get(Keywords.STATE);
		if (initialState == null) throw new IllegalArgumentException("Belief initialisation requires an initial state");
		AKeyPair keyPair = (AKeyPair) config.get(Keywords.KEYPAIR);
		if (keyPair == null) throw new IllegalArgumentException("Belief initialisation requires a keypair");

		return create(keyPair, initialState);
	}

	public MergeContext getMergeContext() {
		return MergeContext.create(keyPair, timestamp, getConsensusState());
	}

	/**
	 * Updates the timestamp to the specified time, going forwards only
	 * 
	 * @param newTimestamp
	 * @return This peer upated with the given timestamp
	 */
	public Peer updateTimestamp(long newTimestamp) {
		if (newTimestamp < timestamp) return this;
		return new Peer(keyPair, belief, states, blockResults, timestamp);
	}

	/**
	 * Compiles and executes a query on the current consensus state of this Peer. 
	 * 
	 * @param <T> Type of result
	 * @param form Form to compile and execute.
	 * @param origon Address to use for query execution
	 * @return The Context containing the query results.
	 */
	@SuppressWarnings("unchecked")
	public <T> Context<T> executeQuery(Object form, Address origin) {
		Context<?> ctx;
		State state=getConsensusState();

		ctx = Context.createInitial(state, origin, Constants.MAX_TRANSACTION_JUICE);

		AccountStatus as=state.getAccount(origin);
		long nonce=(as!=null)?as.getSequence()+1:0;
		Context<AOp<T>> ectx = ctx.expandCompile(form);
		if (ectx.isExceptional()) {
			return (Context<T>) ectx;
		}
		AOp<T> op = ectx.getResult();
		Context<T> rctx = executeQuery(origin,Invoke.create(nonce, op));
		return rctx;
	}
	
	/** 
	 * Executes a query on the current consensus state of this Peer.
	 * 
	 * @param <T>
	 * @param origin Address with which to execute the transaction
	 * @param transaction Transaction to execute
	 * @returnThe Context containing the query results.
	 */
	public <T> Context<T> executeQuery(Address origin, ATransaction transaction) {
		Context<T> ctx=getConsensusState().applyTransaction(origin,transaction);
		return ctx;
	}
	
	/**
	 * Executes a query in this 
	 * @param <T>
	 * @param form
	 * @return
	 */
	public <T> Context<T> executeQuery(Object form) {
		return executeQuery(form,Init.HERO);
	}

	public long getTimeStamp() {
		return timestamp;
	}

	/**
	 * Gets the Address of this Peer. 
	 * @return Address of Peer.
	 */
	public Address getAddress() {
		return address;
	}

	public Belief getBelief() {
		try {
			return belief.getValue();
		} catch (BadSignatureException e) {
			throw new Error("Shouldn't have a badly signed Belief here!");
		}
	}

	public SignedData<Belief> getSignedBelief() {
		return belief;
	}

	public <T> SignedData<T> sign(T value) {
		return SignedData.create(keyPair, value);
	}

	/**
	 * Gets the current consensus state for this chain
	 * 
	 * @return Consensus state for this chain (initial state if no block consensus)
	 */
	public State getConsensusState() {
		return states.get(states.count() - 1);
	}

	/**
	 * Merges a set of new Beliefs into this Peer's belief. Beliefs may be null, in
	 * which case they are ignored.
	 * 
	 * @param beliefs An array of Beliefs. May contain nulls, which will be ignored.
	 * @throws InvalidDataException
	 * @throws BadSignatureException
	 * 
	 */
	public Peer mergeBeliefs(Belief... beliefs) throws BadSignatureException, InvalidDataException {
		Belief belief = getBelief();
		MergeContext mc = MergeContext.create(keyPair, timestamp, getConsensusState());
		Belief newBelief = belief.merge(mc, beliefs);

		return updateBelief(newBelief);
	}

	/**
	 * Update this belief with a new Belief
	 * 
	 * @param newBelief
	 * @return
	 * @throws BadSignatureException
	 */
	private Peer updateBelief(Belief newBelief) throws BadSignatureException {
		if (belief.getValue() == newBelief) return this;
		Order myChain = newBelief.getOrder(address); // this peer's chain from new belief
		long consensusPoint = myChain.getConsensusPoint();
		long stateIndex = states.count() - 1; // index of last state
		AVector<Block> blocks = myChain.getBlocks();

		if (stateIndex > consensusPoint) throw new Error("Receding consenus?");

		// need to advance states
		AVector<State> newStates = this.states;
		AVector<BlockResult> newResults = this.blockResults;
		while (stateIndex < consensusPoint) { // add states until last state is at consensus point
			State s = newStates.get(stateIndex);
			Block block = blocks.get(stateIndex);
			BlockResult br = s.applyBlock(block);
			newStates = newStates.append(br.getState());
			newResults = newResults.append(br);
			stateIndex++;
		}
		SignedData<Belief> sb = keyPair.signData(newBelief);
		return new Peer(keyPair, sb, newStates, newResults, timestamp);
	}

	public AVector<State> getStates() {
		return states;
	}

	public BlockResult getResult(long i) {
		return blockResults.get(i);
	}

	/**
	 * Propose a new Block. Adds the block to the current proposed chain for this
	 * Peer.
	 * 
	 * @throws BadSignatureException
	 */
	public Peer proposeBlock(Block block) throws BadSignatureException {
		Belief b = getBelief();
		AHashMap<Address, SignedData<Order>> chains = b.getChains();
		SignedData<Order> mySignedChain = chains.get(address);

		Order myChain = mySignedChain.getValue();

		Order newChain = myChain.propose(block);
		SignedData<Order> newSignedChain = sign(newChain);
		AHashMap<Address, SignedData<Order>> newChains = chains.assoc(address, newSignedChain);
		return updateBelief(b.withOrders(newChains));
	}

	public long getConsensusPoint() {
		return getPeerOrder().getConsensusPoint();
	}

	/**
	 * Gets the current Order for this Peer
	 * 
	 * @return The Order for this peer in its current Belief.
	 * @throws BadSignatureException
	 */
	public Order getPeerOrder() {
		try {
			return getBelief().getOrder(address);
		} catch (BadSignatureException e) {
			throw new Error("Bad signature on own chain?", e);
		}
	}

	/**
	 * Gets the current chain this Peer sees for a given peer address
	 * 
	 * @return The current Order for the specified peer
	 * @throws BadSignatureException
	 */
	public Order getOrder(Address a) throws BadSignatureException {
		return getBelief().getOrder(a);
	}



}
