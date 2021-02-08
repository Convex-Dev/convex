package convex.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.parboiled.common.Utils;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.ARecord;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.LongBlob;
import convex.core.data.MapEntry;
import convex.core.data.PeerStatus;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Symbols;
import convex.core.lang.impl.RecordFormat;
import convex.core.transactions.ATransaction;
import convex.core.util.Counters;

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
public class State extends ARecord {
	private static final Keyword[] STATE_KEYS = new Keyword[] { Keywords.ACCOUNTS, Keywords.PEERS, Keywords.STORE,
			Keywords.GLOBALS, Keywords.SCHEDULE };

	private static final RecordFormat FORMAT = RecordFormat.of(STATE_KEYS);

	public static final State EMPTY = create(Vectors.empty(), BlobMaps.empty(), Sets.empty(), Constants.INITIAL_GLOBALS,
			BlobMaps.empty());

	private static final Logger log = Logger.getLogger(State.class.getName());
	private static final Level LEVEL_SCHEDULE=Level.FINE;

	// Note: we are embedding these directly in the State cell.
	// TODO: check we aren't at risk of hitting max encoding size limits
	
	private final AVector<AccountStatus> accounts;
	private final BlobMap<AccountKey, PeerStatus> peers;
	private final ASet<ACell> store;
	private final AHashMap<Symbol, ACell> globals;
	private final BlobMap<ABlob, AVector<ACell>> schedule;

	private State(AVector<AccountStatus> accounts, BlobMap<AccountKey, PeerStatus> peers, ASet<ACell> store,
			AHashMap<Symbol, ACell> globals, BlobMap<ABlob, AVector<ACell>> schedule) {
		super(FORMAT);
		this.accounts = accounts;
		this.peers = peers;
		this.globals = globals;
		this.store = store;
		this.schedule = schedule;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword k) {
		if (Keywords.ACCOUNTS.equals(k)) return (V) accounts;
		if (Keywords.PEERS.equals(k)) return (V) peers;
		if (Keywords.STORE.equals(k)) return (V) store;
		if (Keywords.GLOBALS.equals(k)) return (V) globals;
		if (Keywords.SCHEDULE.equals(k)) return (V) schedule;
		return null;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected State updateAll(Object[] newVals) {
		AVector<AccountStatus> accounts = (AVector<AccountStatus>) newVals[0];
		BlobMap<AccountKey, PeerStatus> peers = (BlobMap<AccountKey, PeerStatus>) newVals[1];
		ASet<ACell> store = (ASet<ACell>) newVals[2];
		AHashMap<Symbol, ACell> globals = (AHashMap<Symbol, ACell>) newVals[3];
		BlobMap<ABlob, AVector<ACell>> schedule = (BlobMap<ABlob, AVector<ACell>>) newVals[4];
		if ((this.accounts == accounts) && (this.peers == peers) && (this.store == store) && (this.globals == globals)
				&& (this.schedule == schedule)) {
			return this;
		}
		return new State(accounts, peers, store, globals, schedule);
	}

	public static State create(AVector<AccountStatus> accounts, BlobMap<AccountKey, PeerStatus> peers,
			ASet<ACell> store, AHashMap<Symbol, ACell> globals, BlobMap<ABlob, AVector<ACell>> schedule) {
		return new State(accounts, peers, store, globals, schedule);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getRecordTag();
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = accounts.write(bs,pos);
		pos = peers.write(bs,pos);
		pos = store.write(bs,pos);
		pos = globals.write(bs,pos);
		pos = schedule.write(bs,pos);
		return pos;
	}
	
	@Override
	public long getEncodingLength() {
		long length=1;
		length+=accounts.getEncodingLength();
		length+=peers.getEncodingLength();
		length+=store.getEncodingLength();
		length+=globals.getEncodingLength();
		length+=schedule.getEncodingLength();
		return length;
	}
	
	@Override
	public int estimatedEncodingSize() {
		int est=1;
		est+=accounts.estimatedEncodingSize();
		est+=peers.estimatedEncodingSize();
		est+=store.estimatedEncodingSize();
		est+=globals.estimatedEncodingSize();
		est+=schedule.estimatedEncodingSize();
		return est;
	}

	/**
	 * Reads a State from a ByteBuffer encoding. Assumes tag byte already read.
	 * 
	 * @param bb
	 * @return The State read
	 * @throws BadFormatException If a State could not be read
	 */
	public static State read(ByteBuffer bb) throws BadFormatException {
		try {
			AVector<AccountStatus> accounts = Format.read(bb);
			BlobMap<AccountKey, PeerStatus> peers = Format.read(bb);
			ASet<ACell> store = Format.read(bb);
			AHashMap<Symbol, ACell> globals = Format.read(bb);
			BlobMap<ABlob, AVector<ACell>> schedule = Format.read(bb);
			return create(accounts, peers, store, globals, schedule);
		} catch (ClassCastException ex) {
			throw new BadFormatException("Can't read state", ex);
		}
	}

	public AVector<AccountStatus> getAccounts() {
		return accounts;
	}

	public ASet<ACell> getStore() {
		return store;
	}

	public long getFees() {
		CVMLong fees = (CVMLong) globals.get(Symbols.FEES);
		if (fees == null) return 0L;
		return fees.longValue();
	}

	/**
	 * Gets the map of Peers for this State
	 * 
	 * @return A map of addresses to PeerStatus records
	 */
	public BlobMap<AccountKey, PeerStatus> getPeers() {
		return peers;
	}

	/**
	 * Gets the balance of a specific address, or null if the Address does not exist
	 * @param address
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
	 * Block level state transition function
	 * 
	 * Updates the state by applying a given block of transactions
	 * 
	 * @param block
	 * @return The BlockResult from applying the given Block to this State
	 * @throws BadSignatureException If any transaction is not signed correctly
	 */
	public BlockResult applyBlock(Block block) throws BadSignatureException {
		Counters.applyBlock++;
		State state = applyTimeUpdates(block);
		return state.applyTransactions(block);
	}

	private State applyTimeUpdates(Block b) {
		State state = this;
		long ts = ((CVMLong) state.globals.get(Symbols.TIMESTAMP)).longValue();
		long bts = b.getTimeStamp();
		if (bts > ts) {
			state = state.withGlobal(Symbols.TIMESTAMP, CVMLong.create(bts));
		}

		state = state.applyScheduledTransactions(b);

		return state;
	}

	@SuppressWarnings("unchecked")
	private State applyScheduledTransactions(Block b) {
		long tcount = 0;
		BlobMap<ABlob, AVector<ACell>> sched = this.schedule;
		CVMLong timestamp = this.getTimeStamp();

		// ArrayList to accumulate the transactions to apply. Null until we need it
		ArrayList<Object> al = null;

		// walk schedule entries to determine how many there are
		// and remove from the current schedule
		// we can optimise bulk removal later
		while (tcount < Constants.MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK) {
			if (sched.isEmpty()) break;
			MapEntry<ABlob, AVector<ACell>> me = sched.entryAt(0);
			ABlob key = me.getKey();
			long time = key.longValue();
			if (time > timestamp.longValue()) break; // exit if we are still in the future
			AVector<ACell> trans = me.getValue();
			long numScheduled = trans.count(); // number scheduled at this schedule timestamp
			long take = Math.min(numScheduled, Constants.MAX_SCHEDULED_TRANSACTIONS_PER_BLOCK - tcount);

			// add scheduled transactions to arraylist
			if (al == null) al = new ArrayList<>();
			for (long i = 0; i < take; i++) {
				al.add(trans.get(i));
			}
			// remove schedule entries taken. Delete key if no more entries remaining
			trans = trans.subVector(take, numScheduled - take);
			if (trans.isEmpty()) sched = sched.dissoc(key);
			tcount += take;
		}
		if (tcount == 0) return this; // nothing to do if no transactions to execute

		// update state with amended schedule
		State state = this.withSchedule(sched);

		// now apply the transactions!
		int n = al.size();
		log.log(LEVEL_SCHEDULE,"Applying " + n + " scheduled transactions");
		for (int i = 0; i < n; i++) {
			AVector<ACell> st = (AVector<ACell>) al.get(i);
			Address origin = (Address) st.get(0);
			AOp<?> op = (AOp<?>) st.get(1);
			Context<?> ctx;
			try {
				// TODO juice refund
				ctx = Context.createInitial(state, origin, Constants.MAX_TRANSACTION_JUICE);
				ctx = ctx.run(op);
				if (ctx.isExceptional()) {
					// TODO: what to do here? probably ignore
					// we maybe need to think about reporting scheduled results?
					log.log(LEVEL_SCHEDULE,"Scheduled transaction error: " + ctx.getValue());
				} else {
					state = ctx.getState();
					log.log(LEVEL_SCHEDULE,"Scheduled transaction succeeded");
				}
			} catch (Exception e) {
				log.log(LEVEL_SCHEDULE,"Scheduled transaction failed");
				e.printStackTrace();
			}

		}

		return state;
	}

	private State withSchedule(BlobMap<ABlob, AVector<ACell>> newSchedule) {
		if (schedule == newSchedule) return this;
		return new State(accounts, peers, store, globals, newSchedule);
	}

	private State withGlobals(AHashMap<Symbol, ACell> newGlobals) {
		if (newGlobals == globals) return this;
		return new State(accounts, peers, store, newGlobals, schedule);
	}

	private BlockResult applyTransactions(Block block) throws BadSignatureException {
		State state = this;
		int blockLength = block.length();
		Result[] results = new Result[blockLength];

		AVector<SignedData<ATransaction>> transactions = block.getTransactions();
		for (int i = 0; i < blockLength; i++) {
			// extract the signed transaction from the block
			SignedData<? extends ATransaction> signed = transactions.get(i);

			// SECURITY: catch-all exception handler.
			try {
				// execute the transaction using the *latest* state (not necessarily "this")
				Context<?> ctx = state.applyTransaction(signed);
				
				// record results and state update
				results[i] = Result.fromContext(CVMLong.create(i),ctx);
				state = ctx.getState();
			} catch (Throwable t) {
				String msg= "Unexpected fatal exception applying transaction: "+t.toString();
				results[i] = Result.create(CVMLong.create(i), Strings.create(msg),ErrorCodes.UNEXPECTED);
				t.printStackTrace();
				log.severe(msg);
			}
		}
		
		// TODO: changes for complete block?
		return BlockResult.create(state, results);
	}
	

	/**
	 * Applies a signed transaction to the State. 
	 * 
	 * SECURITY: Checks digital signature and correctness of account key
	 * 
	 * @return Context containing the updated chain State (may be exceptional)
	 */
	private <T extends ACell> Context<T> applyTransaction(SignedData<? extends ATransaction> signedTransaction) throws BadSignatureException {
		// Extract transaction, performs signature check
		ATransaction t=signedTransaction.getValue();
		Address addr=t.getAddress();
		AccountStatus as = getAccount(addr);
		if (as==null) {
			return Context.createFake(this).withError(ErrorCodes.NOBODY,"Transaction for non-existent Account: "+addr);
		} else {
			AccountKey key=as.getAccountKey();
			if (!Utils.equal(key, signedTransaction.getAccountKey())) {
				return Context.createFake(this).withError(ErrorCodes.SIGNATURE,"Signature not valid for Account: "+addr+" expected public key: "+key);
			}
		}
		
		Context<T> ctx=applyTransaction(t);
		return ctx;
	}
	
	/**
	 * Applies a transaction to the State. 
	 * 
	 * There are three phases in application of a transaction:
	 * <ol>
	 * <li>Preparation for accounting, with {@link #prepareTransaction(Address, ATransaction) prepareTransaction}</li>
	 * <li>Functional application of the transaction with ATransaction.apply(....)</li>
	 * <li>Completion of accounting, with completeTransaction</li>
	 * </ol>
	 * 
	 * SECURITY: Assumes digital signature already checked.
	 * 
	 * @return Context containing the updated chain State (may be exceptional)
	 */
	public <T extends ACell> Context<T> applyTransaction(ATransaction t) {
		Address origin = t.getAddress();
		
		// Create prepared context (juice subtracted, sequence updated, transaction entry checks)
		Context<T> ctx = prepareTransaction(origin,t);
		final long totalJuice = ctx.getJuice();
		
		if (ctx.isExceptional()) {
			// We hit some error while preparing transaction. Return context with no state change,
			// i.e. before executing the transaction
			return ctx;
		}
		
		State preparedState=ctx.getState();


		// apply transaction. This may result in an error!
		ctx = t.apply(ctx);

		// complete transaction
		// NOTE: completeTransaction handles error cases as well
		ctx = ctx.completeTransaction(preparedState, totalJuice);

		return ctx;
	}
	
	@SuppressWarnings("unchecked")
	public <T extends ACell> Context<T> prepareTransaction(Address origin,ATransaction t) {
		// Pre-transaction state updates (persisted even if transaction fails)
		AccountStatus account = getAccount(origin);
		if (account == null) {
			return (Context<T>) Context.createFake(this).withError(ErrorCodes.NOBODY);
		}

		// Update sequence number for target account
		long sequence=t.getSequence();
		AccountStatus newAccount = account.updateSequence(sequence);
		if (newAccount == null) {
			return Context.createFake(this,origin).withError(ErrorCodes.SEQUENCE, "Last = "+Long.toString(account.getSequence()));
		}
		State preparedState = this.putAccount(origin, newAccount);
		
		// Create context with juice subtracted
		Long maxJuice=t.getMaxJuice();
		long juiceLimit=Math.min(Constants.MAX_TRANSACTION_JUICE,(maxJuice==null)?account.getBalance():maxJuice);
		Context<T> ctx = Context.createInitial(preparedState, origin, juiceLimit);
		return ctx;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Computes the weighted stake for each peer. Adds a single entry for the null
	 * key, containing the total stake
	 * 
	 * @return @
	 */
	public HashMap<AccountKey, Double> computeStakes() {
		HashMap<AccountKey, Double> hm = new HashMap<>(peers.size());
		Double totalStake = peers.reduceEntries((acc, e) -> {
			double stake = (double) (e.getValue().getTotalStake());
			hm.put(e.getKey(), stake);
			return stake + acc;
		}, 0.0);
		hm.put(null, totalStake);
		return hm;
	}

	public State withAccounts(AVector<AccountStatus> newAccounts) {
		if (newAccounts == accounts) return this;
		return create(newAccounts, peers, store, globals, schedule);
	}

	/**
	 * Returns this state after updating the given account
	 * 
	 * @param address
	 * @param accountStatus
	 * @return Updates State, or this state if Account was unchanged
	 */
	public State putAccount(Address address, AccountStatus accountStatus) {
		long ix=address.longValue();
		long n=accounts.count();
		if (ix>n) {
			throw new IndexOutOfBoundsException("Trying to add an account beyond accounts array at position: "+ix);
		}
		
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
	 * @param target
	 * @return The AccountStatus for the given account, or null.
	 */
	public AccountStatus getAccount(Address target) {
		long ix=target.longValue();
		if (ix>=accounts.count()) return null;
		return accounts.get(ix);
	}

	/**
	 * Gets the environment for a given account, or null if not found.
	 * 
	 * @param addr Address of account to obtain
	 * @return The environment of the given account, or null if not found.
	 */
	public AMap<Symbol, Syntax> getEnvironment(Address addr) {
		AccountStatus as = getAccount(addr);
		if (as == null) return null;
		return as.getEnvironment();
	}

	public State withStore(ASet<ACell> store2) {
		if (store == store2) return this;
		return create(accounts, peers, store2, globals, schedule);
	}

	public State withPeers(BlobMap<AccountKey, PeerStatus> newPeers) {
		if (peers == newPeers) return this;
		return create(accounts, newPeers, store, globals, schedule);
	}

	public State store(ACell a) {
		ASet<ACell> newStore = store.include(a);
		return withStore(newStore);
	}

	@Override
	public byte getRecordTag() {
		return Tag.STATE;
	}

	@Override
	protected String ednTag() {
		return "#state";
	}

	/**
	 * Deploys the specified Actor environment in the current state.
	 * 
	 * Returns the updated state. The actor will be the last account.
	 * 
	 * @param address
	 * @param actorArgs
	 * @param environment Environment to use for new Actor Account. Can be null.
	 * @return The updated state with the Actor deployed.
	 */
	public State tryAddActor(AHashMap<Symbol, Syntax> environment) {
		AccountStatus as = AccountStatus.createActor(environment);
		AVector<AccountStatus> newAccounts = accounts.conj(as);
		return withAccounts(newAccounts);
	}

	/**
	 * Compute the total funds existing within this state.
	 * 
	 * Should be constant! 1,000,000,000,000,000,000 in full deployment mode
	 * 
	 * @return The total value of all funds
	 */
	public long computeTotalFunds() {
		long total = accounts.reduce((Long acc,AccountStatus as) -> acc + as.getBalance(), (Long)0L);
		total += peers.reduceValues((Long acc, PeerStatus ps) -> acc + ps.getTotalStake(), 0L);
		total += getFees();
		return total;
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		accounts.validateCell();
		peers.validateCell();
		store.validateCell();
		globals.validateCell();
		schedule.validateCell();
	}

	/**
	 * Gets the current global timestamp from this state.
	 * 
	 * @return The timestamp from this state.
	 */
	public CVMLong getTimeStamp() {
		return (CVMLong) globals.get(Symbols.TIMESTAMP);
	}

	public CVMLong getJuicePrice() {
		return (CVMLong) globals.get(Symbols.JUICE_PRICE);
	}

	/**
	 * Schedules an operation with the given timestamp and Op in this state
	 * 
	 * @param v
	 * @return The updated State
	 */
	public State scheduleOp(long time, Address address, AOp<?> op) {
		AVector<ACell> v = Vectors.of(address, op);

		LongBlob key = LongBlob.create(time);
		AVector<ACell> list = schedule.get(key);
		if (list == null) {
			list = Vectors.of(v);
		} else {
			list = list.append(v);
		}
		BlobMap<ABlob, AVector<ACell>> newSchedule = schedule.assoc(key, list);

		return this.withSchedule(newSchedule);
	}

	/**
	 * Gets the current schedule data structure for this state
	 * 
	 * @return The schedule data structure.
	 */
	public BlobMap<ABlob, AVector<ACell>> getSchedule() {
		return schedule;
	}

	@SuppressWarnings("unchecked")
	public <R> R getGlobal(Symbol sym) {
		return (R) globals.get(sym);
	}
	
	/**
	 * Gets the global value map
	 * @return Map of global values
	 */
	public  AHashMap<Symbol, ACell> getGlobals() {
		return globals;
	}

	public State withGlobal(Symbol sym, ACell value) {
		return this.withGlobals(globals.assoc(sym, value));
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
	 * @param peerKey
	 * @param updatedPeer
	 * @return Updated state
	 */
	public State withPeer(AccountKey peerKey, PeerStatus updatedPeer) {
		return withPeers(peers.assoc(peerKey, updatedPeer));
	}

	public Address nextAddress() {
		return Address.create(accounts.count());
	}

}