package convex.core;

import java.io.IOException;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;

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
	/** This Peer's key */
	private final AccountKey peerKey;

	/** 
	 * This Peer's key pair 
	 * 
	 *  Make transient to mark that this should never be Persisted by accident
	 */
	private transient final AKeyPair keyPair;
	
	/** The latest merged belief */
	private final Belief belief;

	/**
	 * The latest observed timestamp. This is increased by the Server polling the
	 * local clock.
	 */
	private final long timestamp;
	
	/**
	 * Current state index
	 */
	private final long position;
	
	/**
	 * Current base history position, from which results are stored
	 */
	private final long historyPosition;


	/**
	 * Current consensus state 
	 */
	private final State state;
	
	/**
	 * Current consensus state 
	 */
	private final State genesis;

	/**
	 * Vector of results
	 */
	private final AVector<BlockResult> blockResults;

	private Peer(AKeyPair kp, Belief belief, long pos, State state, State genesis, long history, AVector<BlockResult> results,
			long timeStamp) {
		this.keyPair = kp;
		this.peerKey = kp.getAccountKey();
		this.belief = belief;
		this.state = state;
		this.genesis=genesis;
		this.timestamp = timeStamp;
		this.position=pos;
		
		this.historyPosition=history;
		this.blockResults = results;
	}

	/**
	 * Constructs a Peer instance from persisted PEer Data
	 * @param keyPair Key Pair for Peer
	 * @param peerData Peer data map
	 * @return New Peer instance
	 */
	@SuppressWarnings("unchecked")
	public static Peer fromData(AKeyPair keyPair,AMap<Keyword, ACell> peerData)  {
		Belief belief=(Belief) peerData.get(Keywords.BELIEF);
		AVector<BlockResult> results=(AVector<BlockResult>) peerData.get(Keywords.RESULTS);
		State state=(State) peerData.get(Keywords.STATE);
		State genesis=(State) peerData.get(Keywords.GENESIS);
		long pos=((CVMLong) peerData.get(Keywords.POSITION)).longValue();
		long hpos=((CVMLong) peerData.get(Keywords.HISTORY)).longValue();
		long timestamp=((CVMLong) peerData.get(Keywords.TIMESTAMP)).longValue();
		return new Peer(keyPair,belief,pos,state,genesis,hpos,results,timestamp);
	}

	/**
	 * Gets the Peer Data map for this Peer
	 * @return Peer data
	 */
	public AMap<Keyword, ACell> toData() {
		return Maps.of(
			Keywords.BELIEF,belief,
			Keywords.HISTORY,CVMLong.create(historyPosition),
			Keywords.RESULTS,blockResults,
			Keywords.POSITION,CVMLong.create(position),
			Keywords.STATE,state,
			Keywords.GENESIS,genesis,
			Keywords.TIMESTAMP,timestamp
		);
	}

	/**
	 * Creates a Peer
	 * @param peerKP Key Pair
	 * @param genesis Genesis State
	 * @return New Peer instance
	 */
	public static Peer create(AKeyPair peerKP, State genesis) {
		Belief belief = Belief.createSingleOrder(peerKP);

		return new Peer(peerKP, belief, 0L,genesis,genesis, 0,Vectors.empty(),genesis.getTimestamp().longValue());
	}
	
	/**
	 * Create a Peer instance from a remotely acquired Belief
	 * @param peerKP Peer KeyPair
	 * @param initialState Initial genesis State of the Network
	 * @param remoteBelief Remote belief to sync with
	 * @return New Peer instance
	 */
	public static Peer create(AKeyPair peerKP, State initialState, Belief remoteBelief) {
		Peer peer=create(peerKP,initialState);
		peer=peer.updateTimestamp(Utils.getCurrentTimestamp());
		try {
			peer=peer.mergeBeliefs(remoteBelief);
			return peer;
		} catch (Throwable  e) {
			throw Utils.sneakyThrow(e);
		}
	}

	/**
	 * Like {@link #restorePeer(AStore, AKeyPair, ACell)} but uses a null root key.
	 */
	public static Peer restorePeer(AStore store, AKeyPair keyPair) throws IOException {
		return restorePeer(store, keyPair, null);
	}

	/**
	 * Restores a Peer from the Etch database specified in Config
	 * @param store Store to restore from
	 * @param keyPair Key Pair to use for restored Peer
	 * @param rootKey When not null, assumes the root data is a map and peer data is under that key
	 * @return Peer instance, or null if root hash was not found
	 * @throws IOException If store reading failed
	 */
	public static Peer restorePeer(AStore store, AKeyPair keyPair, ACell rootKey) throws IOException {
			AMap<Keyword,ACell> peerData=getPeerData(store, rootKey);
			if (peerData==null) return null;
			Peer peer=Peer.fromData(keyPair,peerData);
			return peer;
	}
	
	/**
	 * Like {@link #getPeerData(AStore, ACell)} but uses a null root key.
	 * @param store store from which to load Peer data
	 * @return Peer data map
	 * @throws IOException In case of IOException
	 */
	public static AMap<Keyword, ACell> getPeerData(AStore store) throws IOException {
		return getPeerData(store, null);
	}

	/**
	 * Gets Peer Data from a Store.
	 * 
	 * @param store Store to retrieve Peer Data from
	 * @param rootKey When not null, assumes the root data is a map and peer data is under that key
	 * @return Peer data map, or null if not available
	 * @throws IOException If a store IO error occurs
	 */
	@SuppressWarnings("unchecked")
	public static AMap<Keyword, ACell> getPeerData(AStore store, ACell rootKey) throws IOException {
		Stores.setCurrent(store);
		Hash root = store.getRootHash();
		Ref<ACell> ref=store.refForHash(root);
		if (ref==null) return null; // not found case
		if (ref.getStatus()<Ref.PERSISTED) return null; // not fully in store
		
		if (rootKey == null) return (AMap<Keyword,ACell>)ref.getValue();
		else return (AMap<Keyword,ACell>)((AMap<ACell,ACell>)ref.getValue()).get(rootKey);
	}

	/**
	 * Creates a new Peer instance at server startup using the provided
	 * configuration. Current store must be set to store for server.
	 * 
	 * @param keyPair Key pair for genesis peer
	 * @param genesisState Genesis state, or null to generate fresh state
	 * @return A new Peer instance
	 */
	public static Peer createGenesisPeer(AKeyPair keyPair, State genesisState) {
		if (keyPair == null) throw new IllegalArgumentException("Peer initialisation requires a keypair");

		if (genesisState == null) {
			genesisState=Init.createState(Utils.listOf(keyPair.getAccountKey()));
			genesisState=genesisState.withTimestamp(Utils.getCurrentTimestamp());
		}

		return create(keyPair, genesisState);
	}


	/**
	 * Updates the timestamp to the specified time, going forwards only
	 *
	 * @param newTimestamp New Peer timestamp
	 * @return This peer updated with the given timestamp
	 */
	public Peer updateTimestamp(long newTimestamp) {
		if (newTimestamp <= timestamp) return this;
		return new Peer(keyPair, belief, position,state,genesis, historyPosition,blockResults, newTimestamp);
	}

	/**
	 * Compiles and executes a query on the current consensus state of this Peer.
	 *
	 * @param form Form to compile and execute.
	 * @param address Address to use for query execution. If null, core address will be used
	 * @return The Context containing the query results. Will be NOBODY error if address / account does not exist
	 */
	public Context executeQuery(ACell form, Address address) {
		State state=getConsensusState();

		if (address==null) {
			address=Init.CORE_ADDRESS;
			//return  Context.createFake(state).withError(ErrorCodes.NOBODY,"Null Address provided for query");
		}

		Context ctx= Context.createFake(state, address);

		if (state.getAccount(address)==null) {
			return ctx.withError(ErrorCodes.NOBODY,"Account does not exist for query: "+address);
		}

		Context ectx = ctx.expandCompile(form);
		if (ectx.isExceptional()) {
			return (Context) ectx;
		}

		AOp<?> op = ectx.getResult();
		Context rctx = ctx.run(op);
		return rctx;
	}

	/**
	 * Estimates the coin cost of a executing a given transaction by performing a "dry run".
	 *
	 * This will be exact if no intermediate transactions affect the state, and if no time-dependent functionality is used.
	 *
	 * @param trans Transaction to test
	 * @return Estimated cost
	 */
	public long estimateCost(ATransaction trans) {
		Address address=trans.getOrigin();
		State state=getConsensusState();
		Context ctx=executeDryRun(trans);
		return state.getBalance(address)-ctx.getState().getBalance(address);
	}

	/**
	 * Executes a "dry run" transaction on the current consensus state of this Peer.
	 *
	 * @param transaction Transaction to execute
	 * @return The Context containing the transaction results.
	 */
	public Context executeDryRun(ATransaction transaction) {
		Context ctx=getConsensusState().applyTransaction(transaction);
		return ctx;
	}

	/**
	 * Executes a query in this Peer's current Consensus State, using a default address
	 * @param form Form to execute as a Query
	 * @return Context after executing query
	 */
	public Context executeQuery(ACell form) {
		return executeQuery(form,Init.getGenesisAddress());
	}

	/**
	 * Gets the timestamp of this Peer
	 * @return Timestamp
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Gets the Peer Key of this Peer.
	 * @return Peer Key of Peer.
	 */
 	public AccountKey getPeerKey() {
		return peerKey;
	}
 	
	/**
	 * Gets the controller Address for this Peer
	 * @return Address of Peer controller Account, or null if does not exist
	 */
 	public Address getController() {
		PeerStatus ps= getConsensusState().getPeer(peerKey);
		if (ps==null) return null;
		return ps.getController();
	}
 	
	/**
	 * Gets the Peer Key of this Peer.
	 * @return Address of Peer.
	 */
 	public AKeyPair getKeyPair() {
		return keyPair;
	}

 	/**
 	 * Get the current Belief of this Peer
 	 * @return Belief
 	 */
	public Belief getBelief() {
		return belief;
	}

	/**
	 * Signs a value with the keypair of this Peer
	 * @param <T> Type of value to sign
	 * @param value Value to sign
	 * @return Signed data value
	 */
	public <T extends ACell> SignedData<T> sign(T value) {
		return SignedData.create(keyPair, value);
	}

	/**
	 * Gets the current consensus state for this Peer
	 *
	 * @return Consensus state for this chain (genesis state if no block consensus)
	 */
	public State getConsensusState() {
		return state;
	}

	/**
	 * Merges a set of new Beliefs into this Peer's belief. Beliefs may be null, in
	 * which case they are ignored. Should call updateState() at some point after this to
	 * update the global consensus state
	 *
	 * @param beliefs An array of Beliefs. May contain nulls, which will be ignored.
	 * @return Updated Peer after Belief Merge
	 * @throws InvalidDataException if 
	 *
	 */
	public Peer mergeBeliefs(Belief... beliefs) throws InvalidDataException {
		Belief belief=getBelief();
		BeliefMerge mc = BeliefMerge.create(belief,keyPair, timestamp, getConsensusState());
		Belief newBelief = mc.merge(beliefs);

		long ocp=getFinalityPoint();
		Order newOrder=newBelief.getOrder(peerKey);
		if (ocp>newBelief.getOrder(peerKey).getConsensusPoint(Constants.CONSENSUS_LEVEL_FINALITY)) {
			// This probably shouldn't happen, but just in case.....
			System.err.println("Receding consensus? Old CP="+ocp +", New CP="+newOrder.getConsensusPoint());
			
		}
		Peer p= updateBelief(newBelief);
		if (p==this) return this;
		
		return p;
	}
	
	/**
	 * Prunes History before the given timestamp
	 * @param ts Timestamp from which to to keep History
	 * @return Updated Peer with pruned History
	 */
	public Peer pruneHistory(long ts) {
		// Return this if we don't possibly have anything to prune
		if (blockResults.count()==0) return this;
		long firstTs=blockResults.get(0).getState().getTimestamp().longValue();
		if (ts<firstTs) return this;
		
		@SuppressWarnings("unused")
		long ix=Utils.binarySearch(blockResults, br->br.getState().getTimestamp(), (a,b)->a.compareTo(b), CVMLong.create(ts));
		// TODO: complete pruning
		return this;
	}

	/**
	 * Update this Peer with Consensus State for an updated Belief
	 *
	 * @param newBelief Belief to apply
	 * @return Updated Peer
	 */
	public Peer updateBelief(Belief newBelief) {
		if (belief == newBelief) return this;
		return new Peer(keyPair, newBelief, position,state, genesis, historyPosition,blockResults, timestamp);
	}	
	
	/**
	 * Updates the state of the Peer based on latest consensus Belief
	 * @return Updated Peer
	 */
	public Peer updateState() {
		Order myOrder = belief.getOrder(peerKey); // this peer's chain from new belief
		long consensusPoint = myOrder.getConsensusPoint(Constants.CONSENSUS_LEVEL_FINALITY);
		AVector<SignedData<Block>> blocks = myOrder.getBlocks();

		// need to advance states
		State s = this.state;
		long stateIndex=position;
		if (stateIndex>=consensusPoint) return this;

		// We need to compute at least one new state update
		AVector<BlockResult> newResults = this.blockResults;
		while (stateIndex < consensusPoint) { // add states until last state is at consensus point
			SignedData<Block> block = blocks.get(stateIndex);
			
			BlockResult br = s.applyBlock(block);
			s=br.getState();
			newResults = newResults.append(br);
			stateIndex++;
		}
		return new Peer(keyPair, belief, stateIndex,s, genesis, historyPosition,newResults, timestamp);
	}

	/**
	 * Gets the Result of a specific transaction
	 * @param blockIndex Index of Block in Order
	 * @param txIndex Index of transaction in block
	 * @return Result from transaction, or null if the transaction does not exist or is no longer stored
	 */
	public Result getResult(long blockIndex, long txIndex) {
		BlockResult br=getBlockResult(blockIndex);
		if (br==null) return null;
		return br.getResult(txIndex);
	}

	/**
	 * Gets the BlockResult of a specific block index
	 * @param i Index of Block
	 * @return BlockResult, or null if the BlockResult is not stired
	 */
	public BlockResult getBlockResult(long i) {
		if (i<historyPosition) return null; // Ancient history
		long brix=i-historyPosition;
		if (brix>=blockResults.count()) return null;
		return blockResults.get(brix);
	}

	/**
	 * Propose a new Block. Adds the Block to the current proposed Order for this
	 * Peer. Also increments Peer timestamp if necessary for new Block
	 *
	 * @param block Block to publish
	 * @return Peer after proposing new Block in Peer's own Order
	 */
	public Peer proposeBlock(Block block) {
		
		SignedData<Block> signedBlock=sign(block);
		Belief newBelief=belief.proposeBlock(keyPair, signedBlock);
		
		Peer result=this;
		// Update timestamp if necessary to accommodate Block
		long blockTimeStamp=block.getTimeStamp();
		if (blockTimeStamp>result.getTimestamp()) {
			result=result.updateTimestamp(blockTimeStamp);
		}
		
		result=result.updateBelief(newBelief);
		result=result.updateState();
		return result;
	}

	/**
	 * Gets the Final Point for this Peer
	 * @return Consensus Point value
	 */
	public long getFinalityPoint() {
		Order order=getPeerOrder();
		if (order==null) return 0;
		return order.getConsensusPoint(Constants.CONSENSUS_LEVEL_FINALITY);
	}

	/**
	 * Gets the current Order for this Peer
	 *
	 * @return The Order for this peer in its current Belief. Will return null if the Peer is not a peer in the current consensus state
	 *
	 */
	public Order getPeerOrder() {
		return getBelief().getOrder(peerKey);
	}

	/**
	 * Gets the current chain this Peer sees for a given peer address
	 *
	 * @param peerKey Peer Key
	 * @return The current Order for the specified peer
	 */
	public Order getOrder(AccountKey peerKey) {
		return getBelief().getOrder(peerKey);
	}


	/**
	 * Get the Network ID for this PEer
	 * @return Network ID
	 */
	public Hash getNetworkID() {
		return genesis.getHash();
	}

	/**
	 * Gets the state position of this Peer, which is equal to the number of state transitions executed.
	 * @return Position
	 */
	public long getPosition() {
		return position;
	}

	/**
	 * Gets the genesis State of this Peer
	 * @return Genesis State
	 */
	public State getGenesisState() {
		return genesis;
	}
}
