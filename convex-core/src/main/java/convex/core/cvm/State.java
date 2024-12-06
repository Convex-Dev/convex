package convex.core.cvm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.ResultContext;
import convex.core.SourceCodes;
import convex.core.cpos.Block;
import convex.core.cpos.BlockResult;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.impl.InvalidBlockException;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.impl.LongBlob;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.impl.TransactionContext;
import convex.core.util.Counters;
import convex.core.util.Economics;
import convex.core.util.Utils;

/**
 * Class representing the immutable state of the CVM
 *
 * State transitions are represented by blocks of transactions, according to the logic: s[n+1] = s[n].applyBlock(b[n])
 *
 * State contains the following elements - Map of AccountStatus for every
 * Address - Map of PeerStatus for every Peer Address - Global values - Schedule
 * data structure
 *
 * "State. You're doing it wrong" - Rich Hickey
 *
 */
public class State extends ARecordGeneric {
	public static final Index<ABlob, AVector<ACell>> EMPTY_SCHEDULE = Index.none();
	public static final Index<AccountKey, PeerStatus> EMPTY_PEERS = Index.none();

	private static final Keyword[] STATE_KEYS = new Keyword[] { Keywords.ACCOUNTS, Keywords.PEERS,
			Keywords.GLOBALS, Keywords.SCHEDULE };

	private static final RecordFormat FORMAT = RecordFormat.of(STATE_KEYS);
	
	private static final long FIELD_COUNT=FORMAT.count();

	/**
	 * Symbols for global values in :globals Vector
	 */
	public static final AVector<Symbol> GLOBAL_SYMBOLS=Vectors.of(
			Symbols.TIMESTAMP,
			Symbols.FEES, 
			Symbols.JUICE_PRICE, 
			Symbols.MEMORY, 
			Symbols.MEMORY_VALUE,
			Symbols.PROTOCOL);

	// Indexes for globals in :globals Vector
	public static final int GLOBAL_TIMESTAMP=0;
	public static final int GLOBAL_FEES=1;
	public static final int GLOBAL_JUICE_PRICE=2;
	public static final int GLOBAL_MEMORY_MEM=3;
	public static final int GLOBAL_MEMORY_CVX=4;
	public static final int GLOBAL_PROTOCOL=5; // TODO: move to actor?

	/**
	 * An empty State
	 */
	public static final State EMPTY = create(Vectors.empty(), EMPTY_PEERS, Constants.INITIAL_GLOBALS, EMPTY_SCHEDULE);

	private static final Logger log = LoggerFactory.getLogger(State.class.getName());

	private State(AVector<AccountStatus> accounts, Index<AccountKey, PeerStatus> peers,
			AVector<ACell> globals, Index<ABlob, AVector<ACell>> schedule) {
		super(CVMTag.STATE,FORMAT,Vectors.of(accounts,peers,globals,schedule).toVector());
	}
	
	public State(AVector<ACell> values) {
		super(CVMTag.STATE,FORMAT,values.toVector());
	}

	static State create(AVector<ACell> values) {
		if (values.count()!=FIELD_COUNT) return null;
		return new State(values);
	}

	@Override
	public ACell get(Keyword k) {
		if (Keywords.ACCOUNTS.equals(k)) return getAccounts();
		if (Keywords.PEERS.equals(k)) return getPeers();
		if (Keywords.GLOBALS.equals(k)) return getGlobals();
		if (Keywords.SCHEDULE.equals(k)) return getSchedule();
		return null;
	}

	/**
	 * Create a State
	 * @param accounts Accounts
	 * @param peers Peers
	 * @param globals Globals
	 * @param schedule Schedule (may be null)
	 * @return New State instance
	 */
	public static State create(AVector<AccountStatus> accounts, Index<AccountKey, PeerStatus> peers,
			AVector<ACell> globals, Index<ABlob, AVector<ACell>> schedule) {
		return new State(accounts, peers, globals, schedule);
	}

	@Override
	public int getEncodingLength() {
		return values.getEncodingLength();
	}

	@Override
	public int estimatedEncodingSize() {
		return values.estimatedEncodingSize();
	}

	/**
	 * Reads a State from an encoding. Assumes tag byte already read.
	 *
	 * @param b Blob to read from
	 * @param pos start position in Blob 
	 * @return Decoded State
	 * @throws BadFormatException If a State could not be read
	 */
	public static State read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();
		
		State result=create(values);
		if (result==null) throw new BadFormatException("Bad format for CVM global state");
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	/**
	 * Get all Accounts in this State
	 * @return Vector of Accounts
	 */
	@SuppressWarnings("unchecked")
	public AVector<AccountStatus> getAccounts() {
		return (AVector<AccountStatus>) values.get(0);
	}

	/**
	 * Gets the map of Peers for this State
	 *
	 * @return A map of addresses to PeerStatus records
	 */
	@SuppressWarnings("unchecked")
	public Index<AccountKey, PeerStatus> getPeers() {
		return(Index<AccountKey, PeerStatus>) values.get(1);
	}

	/**
	 * Gets the balance of a specific address, or null if the Address does not exist
	 * @param address Address to check
	 * @return Long balance, or null if Account does not exist
	 */
	public Long getBalance(Address address) {
		AccountStatus acc = getAccount(address);
		if (acc == null) return null;
		return acc.getBalance();
	}

	public State withBalance(Address address, long newBalance) {
		AccountStatus acc = getAccount(address);
		if (acc == null) {
			throw new Error("No account for " + address);
		} else {
			acc = acc.withBalance(newBalance);
		}
		return putAccount(address, acc);
	}

	/**
	 * Applies a signed Block to the current state, i.e. the State Transition function
	 * 
	 * @param signedBlock Signed Block to apply
	 * @return The BlockResult from applying the given Block to this State
	 * @throws InvalidBlockException 
	 */
	public BlockResult applyBlock(SignedData<Block> signedBlock) {
		Block block=null;
		try {
			block=signedBlock.getValue();
			Counters.applyBlock++;
	
			// First check the Block passes pre-conditions for application
			BlockResult maybeFailed=checkBlock(signedBlock);
			if (maybeFailed!=null) {
				return maybeFailed;
			}
			
			// Prepare block, including scheduled transactions and time based updates
			State state = prepareBlock(block);
			
			// Create TransactionContext after Block is prepared so we have correct timestamp etc.
			TransactionContext tctx=TransactionContext.create(state);
			tctx.block=signedBlock;
		
			BlockResult blockResult= state.applyTransactions(block,tctx);
			return blockResult;
		} catch (Exception e) {
			// Invalid block, so no state upadtes
			return BlockResult.createInvalidBlock(this,block,Strings.create(e.getMessage()));
		}
	}

	/**
	 * Checks if a block is valid for application to the current state
	 * @param signedBlock Signed Block
	 * @return BlockResult instance if an error occurred, or nil if checks pass
	 */
	public BlockResult checkBlock(SignedData<Block> signedBlock) {
		Block block=signedBlock.getValue();
		AccountKey peerKey=signedBlock.getAccountKey();
		PeerStatus ps=getPeers().get(peerKey);
		
		// If no current peer for this block, dump it
		if (ps==null) return BlockResult.createInvalidBlock(this,block,Strings.MISSING_PEER);
		
		// If peer stake is insufficient, dump it
		if (ps.getPeerStake()<CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
			return BlockResult.createInvalidBlock(this,block,Strings.INSUFFICIENT_STAKE);
		}	
		
		// if block is out of order for the peer, dump it
		if (block.getTimeStamp()<ps.getTimestamp()) {
			return BlockResult.createInvalidBlock(this,block,Strings.MISORDERED_BLOCK);
		}
		
		// if block is too old, dump it
		if (block.getTimeStamp()<(this.getTimestamp().longValue()-CPoSConstants.MAX_BLOCK_BACKDATE)) {
			return BlockResult.createInvalidBlock(this,block,Strings.BACKDATED_BLOCK);
		}
		
		// if block looks like having too many transactions, dump it
		if (block.getTransactions().count()>Constants.MAX_TRANSACTIONS_PER_BLOCK) {
			return BlockResult.createInvalidBlock(this,block,Strings.ILLEGAL_BLOCK_SIZE);
		}
		
		return null;
	}

	/**
	 * Apply state updates consistent with time advancing to a given timestamp
	 * @param b Block to apply
	 * @return Updated state after block preparation
	 */
	private State prepareBlock(Block b) {
		State state = this;
		state = state.applyTimeUpdate(b.getTimeStamp());
		state = state.applyScheduledTransactions();
		return state;
	}
	
	/**
	 * Updates the State based on passage of time.
	 * 
	 * Note: while this can be applied independently, normal correct State updates
	 * should go via `applyBlock`
	 * 
	 * @param newTimestamp New timestamp to apply
	 * @return Updates State
	 */
	public State applyTimeUpdate(long newTimestamp) {
		State state = this;
		AVector<ACell> glbs = state.getGlobals();
		long oldTimestamp=((CVMLong)glbs.get(GLOBAL_TIMESTAMP)).longValue();
		
		// Exit if no time elapsed
		if (newTimestamp <= oldTimestamp) return this;
		// long elapsed=newTimestamp-oldTimestamp;
		
		// Update timestamp
		glbs=glbs.assoc(GLOBAL_TIMESTAMP,CVMLong.create(newTimestamp));
		
		// Grow memory pool if required
		{
			long memAdditions=(newTimestamp/Constants.MEMORY_POOL_GROWTH_INTERVAL)-(oldTimestamp/Constants.MEMORY_POOL_GROWTH_INTERVAL);
			if (memAdditions>0) {
				long mem=((CVMLong)glbs.get(GLOBAL_MEMORY_MEM)).longValue();
				long add=memAdditions*Constants.MEMORY_POOL_GROWTH;
				if (add>0) {
					long  newMem=mem+add;
					glbs=glbs.assoc(GLOBAL_MEMORY_MEM, CVMLong.create(newMem));
				} else {
					throw new Error("Bad memory additions?");
				}
			}
		}
		
		state = state.withGlobals(glbs);
		return state;
	}

	@SuppressWarnings("unchecked")
	public State applyScheduledTransactions() {
		long tcount = 0;
		Index<ABlob, AVector<ACell>> sched = this.getSchedule();
		CVMLong timestamp = this.getTimestamp();

		// ArrayList to accumulate the transactions to apply. Null until we need it
		ArrayList<ACell> al = null;

		// walk schedule entries to determine how many there are
		// and remove from the current schedule
		// we can optimise bulk removal later
		while (tcount < CPoSConstants.MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK) {
			if (sched.isEmpty()) break;
			MapEntry<ABlob, AVector<ACell>> me = sched.entryAt(0);
			ABlob key = me.getKey();
			long time = key.longValue();
			if (time > timestamp.longValue()) break; // exit if we are still in the future
			AVector<ACell> trans = me.getValue();
			long numScheduled = trans.count(); // number scheduled at this schedule timestamp
			long take = Math.min(numScheduled, CPoSConstants.MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK - tcount);

			// add scheduled transactions to List
			if (al == null) al = new ArrayList<>();
			for (long i = 0; i < take; i++) {
				al.add(trans.get(i));
			}
			// remove schedule entries taken. Delete key if no more entries remaining
			trans = trans.slice(take, numScheduled);
			if (trans.isEmpty()) sched = sched.dissoc(key);
		}
		if (al==null) return this; // nothing to do if no transactions to execute

		// update state with amended schedule
		State state = this.withSchedule(sched);

		// now apply the transactions!
		int n = al.size();
		log.debug("Applying {} scheduled transactions",n);
		for (int i = 0; i < n; i++) {
			AVector<ACell> st = (AVector<ACell>) al.get(i);
			Address origin = (Address) st.get(0);
			AOp<?> op = (AOp<?>) st.get(1);
			Context ctx;
			
			// TODO juice limit? juice refund?
			ctx = Context.create(state, origin, Constants.MAX_TRANSACTION_JUICE);
			ctx = ctx.run(op);
			if (ctx.isExceptional()) {
				// TODO: what to do here? probably ignore
				// we maybe need to think about reporting scheduled results?
				log.trace("Scheduled transaction error: {}", ctx.getExceptional());
			} else {
				state = ctx.getState();
				log.trace("Scheduled transaction succeeded");
			}
		}

		return state;
	}

	private BlockResult applyTransactions(Block block, TransactionContext tctx) throws InvalidBlockException {
		State state = this;
		int blockLength = block.length();
		Result[] results = new Result[blockLength];
		long fees=0L;

		AVector<SignedData<ATransaction>> transactions = block.getTransactions();
		for (int i = 0; i < blockLength; i++) {
			// SECURITY: catch-all exception handler, needs consideration
			//try {
				// extract the signed transaction from the block
				SignedData<ATransaction> signed = transactions.get(i);
				
				// Update transaction context
				tctx.tx=signed;
				
				// execute the transaction using the *latest* state (not necessarily "this")
				ResultContext rc = state.applyTransaction(signed);

				// record results from result context
				results[i] = Result.fromContext(CVMLong.create(i),rc);
				
				// Get fees from ResultContext
				// NOTE: Juice fees includes transaction overhead fees
				fees+=rc.getJuiceFees();
				
				// state update
				state = rc.context.getState();
			//} catch (Exception e) {
			//	String msg= "Unexpected fatal exception applying transaction: "+e.toString();
			//	results[i] = Result.create(CVMLong.create(i), Strings.create(msg),ErrorCodes.FATAL).withSource(SourceCodes.CVM);
			//	log.error(msg,e);
			//}
		}
		
		state=distributeFees(state,tctx.getPeer(),fees);

		// TODO: any other things for complete block?
		return BlockResult.create(state, results);
	}

	static State distributeFees(State state, AccountKey peer, long fees) {
		PeerStatus ps=state.getPeer(peer);
		AccountStatus rewardPool=state.getAccount(Address.ZERO);
		if (ps==null) {
			// this can happen, e.g. if the peer evicted itself
		} else {
			// immediate fees to peer
			long peerFees=fees/2;
			ps=ps.distributeBlockReward(state,peerFees);
			fees-=peerFees;
			state=state.withPeer(peer, ps);
		}
		
		// Remaining fees burned to reward pool
		if (fees>0) {
			state=state.putAccount(Address.ZERO,rewardPool.withBalance(rewardPool.getBalance()+fees));
		}		
		return state;
	}

	/**
	 * Applies a signed transaction to the State.
	 *
	 * SECURITY: Checks digital signature and correctness of account key
	 *
	 * @return ResultContext containing the result of the transaction
	 * @throws InvalidBlockException 
	 */
	public ResultContext applyTransaction(SignedData<? extends ATransaction> signedTransaction) throws InvalidBlockException {
		// Extract transaction
		ATransaction t=RT.ensureTransaction(signedTransaction.getValue());
		if (t==null) throw new InvalidBlockException("Not a signed transaction: "+signedTransaction.getHash());

		Address addr=t.getOrigin();
		AccountStatus as = getAccount(addr);
		if (as==null) {
			ResultContext rc=ResultContext.error(this,ErrorCodes.NOBODY,"Transaction for non-existent Account: "+addr);
			return rc.withSource(SourceCodes.CVM);
		} else {
			// Update sequence number for target account
			long sequence=t.getSequence();
			long expectedSequence=as.getSequence()+1;
			if (sequence!=expectedSequence) {
				ResultContext rc=ResultContext.error(this,ErrorCodes.SEQUENCE, "Sequence = "+sequence+" but expected "+expectedSequence);
				return rc.withSource(SourceCodes.CVM);
			}
			
			AccountKey key=as.getAccountKey();
			if (key==null) {
				ResultContext rc= ResultContext.error(this,ErrorCodes.STATE,"Transaction for account that is an Actor: "+addr);
				return rc.withSource(SourceCodes.CVM);
			}
			
			// Perform Signature check
			boolean sigValid=signedTransaction.checkSignature(key);
			if (!sigValid) {
				ResultContext rc= ResultContext.error(this,ErrorCodes.SIGNATURE, Strings.BAD_SIGNATURE);
				return rc.withSource(SourceCodes.CVM);
			}
		}

		ResultContext ctx=applyTransaction(t);
		return ctx;
	}
	
	/**
	 * Applies a transaction to the State.
	 *
	 * There are three phases in application of a transaction:
	 * <ol>
	 * <li>Preparation for accounting, with {@link #prepareTransaction(ResultContext) prepareTransaction}</li>
	 * <li>Functional application of the transaction with ATransaction.apply(....)</li>
	 * <li>Completion of accounting, with completeTransaction</li>
	 * </ol>
	 *
	 * SECURITY: Assumes digital signature and sequence number already checked.
	 *
	 * @param t Transaction to apply
	 * @return Context containing the updated chain State (may be exceptional)
	 */
	public ResultContext applyTransaction(ATransaction t) {
		ResultContext rc=createResultContext(t);
		
		// Create prepared context 
		Context ctx = prepareTransaction(rc);
		if (!ctx.isExceptional()) {
			State preparedState=ctx.getState();

			// apply transaction. This may result in an error / exceptional result!
			ctx = t.apply(ctx);
			
			// Set context, might be needed by completeTransaction
			rc.context=ctx;

			// complete transaction including handling of fees
			// NOTE: completeTransaction handles error cases as well
			ctx = ctx.completeTransaction(preparedState,rc);	
		} else {
			// We hit some error while preparing transaction. Possible culprits:
			// - Non-existent Origin account
			// - Bad sequence number
			// Return context with no change, i.e. before executing the transaction
			rc.source=SourceCodes.CVM;
		}

		return rc.withContext(ctx);
	}
	
	/**
	 * Prepares a CVM execution context and ResultContext for a transaction
	 * @param rc ResultContext to populate
	 * @return
	 */
	private Context prepareTransaction(ResultContext rc) {
		ATransaction t=rc.tx;
		long juicePrice=rc.juicePrice;
		Address origin = t.getOrigin();
		
		// Pre-transaction state updates (persisted even if transaction fails)
		AccountStatus account = getAccount(origin);
		if (account == null) {
			return Context.create(this).withError(ErrorCodes.NOBODY);
		}

		// Create context with juice limit
		long balance=account.getBalance();
		long juiceLimit=Juice.calcAvailable(balance, juicePrice);
		juiceLimit=Math.min(Constants.MAX_TRANSACTION_JUICE,juiceLimit);
		long initialJuice=0;
		if (juiceLimit<=initialJuice) {
			return Context.create(this,origin).withJuiceError();
		}
		
		// Create context ready to execute, with at least some available juice
		Context ctx = Context.create(this, origin, juiceLimit);
		ctx=ctx.withJuice(initialJuice);
		return ctx;
	}
	
	/**
	 * Creates an initial ResultContext for a transaction
	 * @param t
	 * @return
	 */
	private ResultContext createResultContext(ATransaction t) {
		long juicePrice=getJuicePrice().longValue();
		ResultContext rc=new ResultContext(t,juicePrice);
		return rc;
	}


	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Computes the weighted effective stake for each peer. Adds a single entry for the null
	 * key, containing the total stake
	 *
	 * @return Map of Stakes
	 */
	public HashMap<AccountKey, Double> computeStakes() {
		HashMap<AccountKey, Double> hm = new HashMap<>(getPeers().size());
		long timeStamp=this.getTimestamp().longValue();
		Double totalStake = getPeers().reduceEntries((acc, e) -> {
			PeerStatus ps=e.getValue();
			double stake = (double) (ps.getTotalStake());
			
			long peerTimestamp=ps.getTimestamp();
			double decay=Economics.stakeDecay(timeStamp,peerTimestamp);
			stake*=decay;
			
			hm.put(RT.ensureAccountKey(e.getKey()), stake);
			return stake + acc;
		}, 0.0);
		hm.put(null, totalStake);
		return hm;
	}

	/**
	 * Updates the Accounts in this State
	 * @param newAccounts New Accounts vector
	 * @return Updated State
	 */
	public State withAccounts(AVector<AccountStatus> newAccounts) {
		if (newAccounts == getAccounts()) return this;
		return create(values.assoc(0, newAccounts));
	}

	/**
	 * Returns this state after updating the given account
	 *
	 * @param address Address of Account to update
	 * @param accountStatus New Account Status
	 * @return Updates State, or this state if Account was unchanged
	 * @throws IndexOutOfBoundsException if Address represents an illegal account position
	 */
	public State putAccount(Address address, AccountStatus accountStatus) {
		long ix=address.longValue();
		long n=getAccounts().count();
		if (ix>n) {
			throw new IndexOutOfBoundsException("Trying to add an account beyond accounts array at position: "+ix);
		}
		
		AVector<AccountStatus> accounts=getAccounts();
		AVector<AccountStatus> newAccounts;
		if (ix==n) {
			// adding a new account in next position
			newAccounts=accounts.conj(accountStatus);
		} else {
			newAccounts = accounts.assoc(ix, accountStatus);
		}

		return withAccounts(newAccounts);
	}

	/**
	 * Gets the AccountStatus for a given account, or null if not found.
	 *
	 * @param target Address to look up. Must not be null
	 * @return The AccountStatus for the given account, or null.
	 */
	public AccountStatus getAccount(Address target) {
		long ix=target.longValue();
		return getAccount(ix);
	}
	
	public AccountStatus getAccount(long ix) {
		AVector<AccountStatus> accts=getAccounts();
		if ((ix<0)||(ix>=accts.count())) return null;
		return accts.get(ix);
	}

	/**
	 * Gets the environment for a given account, or null if not found.
	 *
	 * @param addr Address of account to obtain
	 * @return The environment of the given account, or null if not found.
	 */
	public AMap<Symbol, ACell> getEnvironment(Address addr) {
		AccountStatus as = getAccount(addr);
		if (as == null) return null;
		return as.getEnvironment();
	}

	/**
	 * Updates the Peers in this State
	 * @param newPeers New Peer Map
	 * @return Updated State
	 */
	public State withPeers(Index<AccountKey, PeerStatus> newPeers) {
		if (getPeers() == newPeers) return this;
		return create(values.assoc(1,newPeers));
	}

	/**
	 * Adds a new Actor Account. The actor will be the last Account in the resulting State, and will
	 * have a default empty Actor environment.
	 *
	 * @return The updated state with the Actor Account added.
	 */
	public State addActor() {
		AccountStatus as = AccountStatus.createActor();
		AVector<AccountStatus> newAccounts = getAccounts().conj(as);
		return withAccounts(newAccounts);
	}

	/**
	 * Compute the total funds existing within this state.
	 *
	 * Should be constant! 1,000,000,000,000,000,000 in full deployment mode
	 *
	 * @return The total value of all funds
	 */
	public long computeTotalBalance() {
		long total = getAccounts().reduce((Long acc,AccountStatus as) -> acc + as.getBalance(), (Long)0L);
		total += getPeers().reduceValues((Long acc, PeerStatus ps) -> acc + ps.getBalance(), 0L);
		total += getGlobalFees().longValue();
		total += getGlobalMemoryValue().longValue();
		return total;
	}
	
	/**
	 * Compute the issued coin supply. This is the maximum supply cap minus the unissued coin balance.
	 *
	 * @return The current Convex Coin Supply
	 */
	public long computeSupply() {
		long supply=Constants.MAX_SUPPLY;
		for (int i=0; i<Init.NUM_GOVERNANCE_ACCOUNTS; i++) {
			supply-=getAccounts().get(i).getBalance();
		}
		return supply;
	}
	
	/**
	 * Compute the total memory allowance, including the memory pool. WARNING: expensive full account scan.
	 *
	 * @return The total amount of CVM memory available
	 */
	public long computeTotalMemory() {
		long total = getAccounts().reduce((Long acc,AccountStatus as) -> acc + as.getMemory(), (Long)0L);
		total+=getGlobalMemoryPool().longValue();
		return total;
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
	}

	/**
	 * Gets the current global timestamp from this state.
	 *
	 * @return The timestamp from this state.
	 */
	public CVMLong getTimestamp() {
		return (CVMLong) getGlobals().get(GLOBAL_TIMESTAMP);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// nothing to do?
	}

	/**
	 * Gets the current Juice price
	 * 
	 * @return Juice Price
	 */
	public CVMLong getJuicePrice() {
		return (CVMLong) getGlobals().get(GLOBAL_JUICE_PRICE);
	}

	/**
	 * Schedules an operation with the given timestamp and Op in this state
	 *
	 * @param time Timestamp at which to execute the scheduled op
	 *
	 * @param address AccountAddress to schedule op for
	 * @param op Op to execute in schedule
	 * @return The updated State
	 */
	public State scheduleOp(long time, Address address, AOp<?> op) {
		AVector<ACell> v = Vectors.of(address, op);

		LongBlob key = LongBlob.create(time);
		AVector<ACell> list = getSchedule().get(key);
		if (list == null) {
			list = Vectors.of(v);
		} else {
			list = list.append(v);
		}
		Index<ABlob, AVector<ACell>> newSchedule = getSchedule().assoc(key, list);

		return this.withSchedule(newSchedule);
	}

	/**
	 * Gets the current schedule data structure for this state
	 *
	 * @return The schedule data structure.
	 */
	@SuppressWarnings("unchecked")
	public Index<ABlob, AVector<ACell>> getSchedule() {
		return (Index<ABlob, AVector<ACell>>) values.get(3);
	}

	/**
	 * Gets the Global Fees accumulated in the State
	 * @return Global Fees
	 */
	public CVMLong getGlobalFees() {
		return (CVMLong) getGlobals().get(GLOBAL_FEES);
	}

	/**
	 * Update Global Fees
	 * @param newFees New Fees
	 * @return Updated State
	 */
	public State withGlobalFees(CVMLong newFees) {
		return withGlobals(getGlobals().assoc(GLOBAL_FEES,newFees));
	}


	/**
	 * Gets the PeerStatus record for the given Address, or null if it does not
	 * exist
	 *
	 * @param peerAddress Address of Peer to check
	 * @return PeerStatus
	 */
	public PeerStatus getPeer(AccountKey peerAddress) {
		return getPeers().get(peerAddress);
	}

	/**
	 * Updates the specified peer status
	 *
	 * @param peerKey Peer Key
	 * @param updatedPeer New Peer Status
	 * @return Updated state
	 */
	public State withPeer(AccountKey peerKey, PeerStatus updatedPeer) {
		return withPeers(getPeers().assoc(peerKey, updatedPeer));
	}

	/**
	 * Gets the next available address for allocation, i.e. the lowest Address
	 * that does not yet exist in this State.
	 * 
	 * @return Next address available
	 */
	public Address nextAddress() {
		return Address.create(getAccounts().count());
	}

	/**
	 * Look up an Address from CNS
	 * @param name CNS name String
	 * @return Address from CNS, or null if not found
	 */
	public Address lookupCNS(String name) {
		Context ctx=Context.create(this);
		return (Address) ctx.lookupCNS(name).getResult();
	}

	/**
	 * Gets globals.
	 *
	 * @return Vector of global values
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> getGlobals() {
		return (AVector<ACell>) values.get(2);		
	}

	/**
	 * Updates the State with a new timestamp
	 * @param timestamp New timestamp
	 * @return Updated State
	 */
	public State withTimestamp(long timestamp) {
		return withGlobals(getGlobals().assoc(GLOBAL_TIMESTAMP, CVMLong.create(timestamp)));
	}
	
	@Override 
	public boolean equals(ACell a) {
		if (a instanceof State) {
			State as=(State)a;
			return equals(as);
		} else {
			return Cells.equalsGeneric(this, a);
		}
	}
	
	/**
	 * Tests if this State is equal to another
	 * @param a State to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(State a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		
		// fast check on cached hash
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return Cells.equals(h, ha);
		}
		return values.equals(a.values);
	}

	/**
	 * Gets the Convex Coin value in the memory exchange pool
	 * @return Memory pool Convex Coin amount (coppers)
	 */
	public CVMLong getGlobalMemoryValue() {
		return (CVMLong)(getGlobals().get(GLOBAL_MEMORY_CVX));
	}

	/**
	 * Gets the amount of memory in the memory exchange pool
	 * @return Memory in pool, in bytes
	 */
	public CVMLong getGlobalMemoryPool() {
		return (CVMLong)(getGlobals().get(GLOBAL_MEMORY_MEM));
	}
	
	public double getMemoryPrice() {
		long pool=getGlobalMemoryPool().longValue();
		long value=getGlobalMemoryValue().longValue();
				
		return ((double)value)/pool;
	}
	
	private State withSchedule(Index<ABlob, AVector<ACell>> newSchedule) {
		if (getSchedule() == newSchedule) return this;
		return new State(values.assoc(3, newSchedule));
	}

	private State withGlobals(AVector<ACell> newGlobals) {
		if (newGlobals == getGlobals()) return this;
		return new State(values.assoc(2, newGlobals));
	}

	public State updateMemoryPool(long cvx, long mem) {
		AVector<ACell> r=getGlobals();
		r=r.assoc(GLOBAL_MEMORY_CVX, CVMLong.create(cvx));
		r=r.assoc(GLOBAL_MEMORY_MEM, CVMLong.create(mem));
		return withGlobals(r);
	}

	public boolean hasAccount(Address address) {
		long av=address.longValue();
		return (av>=0) &&(av<getAccounts().count());
	}

	public static AVector<State> statesAsOfRange(AVector<State> states, CVMLong timestamp, long interval, int count) {
		AVector<State> v = Vectors.empty();
	
		for (int i = 0; i < count; i++) {
			v = v.conj(stateAsOf(states, timestamp));
	
			timestamp = CVMLong.create(timestamp.longValue() + interval);
		}
	
		return v;
	}

	public static State stateAsOf(AVector<State> states, CVMLong timestamp) {
		return Utils.binarySearchLeftmost(states, State::getTimestamp, Comparator.comparingLong(CVMLong::longValue), timestamp);
	}

	@Override
	protected State withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return create(newValues);
	}







}
