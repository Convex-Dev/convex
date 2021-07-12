package convex.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.core.util.Utils;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ARecord;
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
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Symbol;
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
	private static final Keyword[] STATE_KEYS = new Keyword[] { Keywords.ACCOUNTS, Keywords.PEERS,
			Keywords.GLOBALS, Keywords.SCHEDULE };

	private static final RecordFormat FORMAT = RecordFormat.of(STATE_KEYS);

	public static final AVector<Symbol> GLOBAL_SYMBOLS=Vectors.of(Symbols.TIMESTAMP, Symbols.FEES, Symbols.JUICE_PRICE);

	public static final int GLOBAL_TIMESTAMP=0;
	public static final int GLOBAL_FEES=1;
	public static final int GLOBAL_JUICE_PRICE=2;

	public static final State EMPTY = create(Vectors.empty(), BlobMaps.empty(), Constants.INITIAL_GLOBALS,
			BlobMaps.empty());

	private static final Logger log = Logger.getLogger(State.class.getName());
	private static final Level LEVEL_SCHEDULE=Level.FINE;

	// Note: we are embedding these directly in the State cell.
	// TODO: check we aren't at risk of hitting max encoding size limits

	private final AVector<AccountStatus> accounts;
	private final BlobMap<AccountKey, PeerStatus> peers;
	private final AVector<ACell> globals;
	private final BlobMap<ABlob, AVector<ACell>> schedule;

	private State(AVector<AccountStatus> accounts, BlobMap<AccountKey, PeerStatus> peers,
			AVector<ACell> globals, BlobMap<ABlob, AVector<ACell>> schedule) {
		super(FORMAT);
		this.accounts = accounts;
		this.peers = peers;
		this.globals = globals;
		this.schedule = schedule;
	}

	@Override
	public ACell get(ACell k) {
		if (Keywords.ACCOUNTS.equals(k)) return accounts;
		if (Keywords.PEERS.equals(k)) return peers;
		if (Keywords.GLOBALS.equals(k)) return globals;
		if (Keywords.SCHEDULE.equals(k)) return schedule;
		return null;
	}

	@Override
	public int getRefCount() {
		int rc=accounts.getRefCount();
		rc+=peers.getRefCount();
		rc+=globals.getRefCount();
		rc+=schedule.getRefCount();
		return rc;
	}

	public <R extends ACell> Ref<R> getRef(int i) {
		if (i<0) throw new IndexOutOfBoundsException(i);

		{
			int c=accounts.getRefCount();
			if (i<c) return accounts.getRef(i);
			i-=c;
		}

		{
			int c=peers.getRefCount();
			if (i<c) return peers.getRef(i);
			i-=c;
		}

		{
			int c=globals.getRefCount();
			if (i<c) return globals.getRef(i);
			i-=c;
		}

		{
			int c=schedule.getRefCount();
			if (i<c) return schedule.getRef(i);
			i-=c;
		}

		throw new IndexOutOfBoundsException(i);
	}

	@SuppressWarnings("unchecked")
	@Override
	protected State updateAll(ACell[] newVals) {
		AVector<AccountStatus> accounts = (AVector<AccountStatus>) newVals[0];
		BlobMap<AccountKey, PeerStatus> peers = (BlobMap<AccountKey, PeerStatus>) newVals[1];
		AVector<ACell> globals = (AVector<ACell>) newVals[2];
		BlobMap<ABlob, AVector<ACell>> schedule = (BlobMap<ABlob, AVector<ACell>>) newVals[3];
		if ((this.accounts == accounts) && (this.peers == peers) && (this.globals == globals)
				&& (this.schedule == schedule)) {
			return this;
		}
		return new State(accounts, peers, globals, schedule);
	}

	public static State create(AVector<AccountStatus> accounts, BlobMap<AccountKey, PeerStatus> peers,
			AVector<ACell> globals, BlobMap<ABlob, AVector<ACell>> schedule) {
		return new State(accounts, peers, globals, schedule);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = accounts.write(bs,pos);
		pos = peers.write(bs,pos);
		pos = globals.write(bs,pos);
		pos = schedule.write(bs,pos);
		return pos;
	}

	@Override
	public long getEncodingLength() {
		long length=1;
		length+=accounts.getEncodingLength();
		length+=peers.getEncodingLength();
		length+=globals.getEncodingLength();
		length+=schedule.getEncodingLength();
		return length;
	}

	@Override
	public int estimatedEncodingSize() {
		int est=1;
		est+=accounts.estimatedEncodingSize();
		est+=peers.estimatedEncodingSize();
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
			AVector<ACell> globals = Format.read(bb);
			BlobMap<ABlob, AVector<ACell>> schedule = Format.read(bb);
			return create(accounts, peers, globals, schedule);
		} catch (ClassCastException ex) {
			throw new BadFormatException("Can't read state", ex);
		}
	}

	public AVector<AccountStatus> getAccounts() {
		return accounts;
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
		State state = prepareBlock(block);
		return state.applyTransactions(block);
	}

	/**
	 * Apply state updates consistent with time advancing to a given timestamp
	 * @param b
	 * @return
	 */
	private State prepareBlock(Block b) {
		State state = this;
		AVector<ACell> glbs = state.globals;
		long ts=((CVMLong)glbs.get(0)).longValue();
		long bts = b.getTimeStamp();
		if (bts > ts) {
			AVector<ACell> newGlbs=glbs.assoc(0,CVMLong.create(bts));
			state = state.withGlobals(newGlbs);
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
		ArrayList<ACell> al = null;

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
		}
		if (al==null) return this; // nothing to do if no transactions to execute

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
					log.log(LEVEL_SCHEDULE,"Scheduled transaction error: " + ctx.getExceptional());
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
		return new State(accounts, peers, globals, newSchedule);
	}

	private State withGlobals(AVector<ACell> newGlobals) {
		if (newGlobals == globals) return this;
		return new State(accounts, peers, newGlobals, schedule);
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
			if (key==null) return Context.createFake(this).withError(ErrorCodes.NOBODY,"Transaction for account that is an Actor: "+addr);
			if (!Utils.equals(key, signedTransaction.getAccountKey())) {
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
	 * @param t Transaction to apply
	 * @return Context containing the updated chain State (may be exceptional)
	 */
	public <T extends ACell> Context<T> applyTransaction(ATransaction t) {
		Address origin = t.getAddress();

		try {
			// Create prepared context (juice subtracted, sequence updated, transaction entry checks)
			Context<T> ctx = prepareTransaction(origin,t);
			if (ctx.isExceptional()) {
				// We hit some error while preparing transaction. Return context with no state change,
				// i.e. before executing the transaction
				return ctx;
			}
			
			final long totalJuice = ctx.getJuice();

			State preparedState=ctx.getState();


			// apply transaction. This may result in an error!
			ctx = t.apply(ctx);

			// complete transaction
			// NOTE: completeTransaction handles error cases as well
			ctx = ctx.completeTransaction(preparedState, totalJuice);

			return ctx;
		} catch (Throwable ex) {
			// SECURITY: This should never happen!
			// But catching right now to prevent CVM overall crash
			StringWriter s=new StringWriter();
			ex.printStackTrace(new PrintWriter(s));
			String message=s.toString();
			Context<T> fCtx=Context.createInitial(this, origin, 0);
			fCtx=fCtx.withError(ErrorCodes.FATAL, message);
			return fCtx;
		}
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
			return Context.createFake(this,origin).withError(ErrorCodes.SEQUENCE, "Received = "+sequence+" & Expected = "+(account.getSequence()+1));
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
			hm.put(AccountKey.create(e.getKey()), stake);
			return stake + acc;
		}, 0.0);
		hm.put(null, totalStake);
		return hm;
	}

	public State withAccounts(AVector<AccountStatus> newAccounts) {
		if (newAccounts == accounts) return this;
		return create(newAccounts, peers,globals, schedule);
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
	 * @param target Address to look up. Must not be null
	 * @return The AccountStatus for the given account, or null.
	 */
	public AccountStatus getAccount(Address target) {
		long ix=target.longValue();
		if ((ix<0)||(ix>=accounts.count())) return null;
		return accounts.get(ix);
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


	public State withPeers(BlobMap<AccountKey, PeerStatus> newPeers) {
		if (peers == newPeers) return this;
		return create(accounts, newPeers, globals, schedule);
	}

	@Override
	public byte getTag() {
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
	public State tryAddActor() {
		AccountStatus as = AccountStatus.createActor();
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
		total += getGlobalFees().longValue();
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
		globals.validateCell();
		schedule.validateCell();
	}

	/**
	 * Gets the current global timestamp from this state.
	 *
	 * @return The timestamp from this state.
	 */
	public CVMLong getTimeStamp() {
		return (CVMLong) globals.get(GLOBAL_TIMESTAMP);
	}

	public CVMLong getJuicePrice() {
		return (CVMLong) globals.get(GLOBAL_JUICE_PRICE);
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

	public CVMLong getGlobalFees() {
		return (CVMLong) globals.get(GLOBAL_FEES);
	}

	public State withGlobalFees(CVMLong newFees) {
		return withGlobals(globals.assoc(GLOBAL_FEES,newFees));
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

	public Address lookupCNS(String name) {
		Context<?> ctx=Context.createFake(this);
		return (Address) ctx.lookupCNS(name).getResult();
	}

	/**
	 * Gets globals.
	 *
	 * @return Vector of global values
	 */
	public AVector<ACell> getGlobals() {
		return globals;
	}

	public State withTimestamp(long timestamp) {
		return withGlobals(globals.assoc(GLOBAL_TIMESTAMP, CVMLong.create(timestamp)));
	}

}
