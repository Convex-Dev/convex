package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Core;
import convex.core.lang.IFn;
import convex.core.lang.RT;
import convex.core.lang.Symbols;
import convex.core.lang.impl.RecordFormat;

/**
 * Class representing the current on-chain status of an account.
 * 
 * Accounts may be User accounts or Actor accounts.
 * 
 * "People said I should accept the world. Bullshit! I don't accept the world."
 * - Richard Stallman
 */
public class AccountStatus extends ARecord {
	private final long sequence;
	private final long balance;
	private final long allowance;
	private final AHashMap<Symbol, Syntax> environment;
	private final ABlobMap<Address, Object> holdings;
	private final Address controller;
	private final AccountKey publicKey;
	
	private static final Keyword[] ACCOUNT_KEYS = new Keyword[] { Keywords.SEQUENCE, Keywords.BALANCE,Keywords.ALLOWANCE,Keywords.ENVIRONMENT,
			Keywords.HOLDINGS, Keywords.CONTROLLER, Keywords.KEY};

	private static final RecordFormat FORMAT = RecordFormat.of(ACCOUNT_KEYS);

	private AccountStatus(long sequence, long balance, long allowance,
			AHashMap<Symbol, Syntax> environment, ABlobMap<Address, Object> holdings,Address controller, AccountKey publicKey) {
		super(FORMAT);
		this.sequence = sequence;
		this.balance = balance;
		this.allowance = allowance;
		this.environment = environment;
		this.holdings=holdings;
		this.controller=controller;
		this.publicKey=publicKey;
	}
	
	/**
	 * Create a regular account, with the specified balance and zero allowance
	 * 
	 * @param sequence
	 * @param balance
	 * @return New AccountStatus
	 */
	public static AccountStatus create(long sequence, long balance, AccountKey key) {
		return new AccountStatus(sequence, balance, 0L, null,null,null,key);
	}

	/**
	 * Create a governance account.
	 * 
	 * @param sequence
	 * @param balance
	 * @return New governance AccountStatus
	 */
	public static AccountStatus createGovernance(long balance) {
		return new AccountStatus(0, balance, 0L, null,null,null,null);
	}

	public static AccountStatus createActor(long balance,
			AHashMap<Symbol, Syntax> environment) {
		return new AccountStatus(Constants.ACTOR_SEQUENCE, balance, 0L,environment,null,null,null);
	}

	public static AccountStatus create(long balance, AccountKey key) {
		return create(0, balance,key);
	}

	/**
	 * Create a completely empty account, with no balance or public key
	 * @return
	 */
	public static AccountStatus create() {
		return create(0, 0L,null);
	}

	/**
	 * Gets the sequence number for this Account. The sequence number is the number
	 * of transactions executed by this account to date. It will be zero for new
	 * Accounts.
	 * 
	 * The next transaction executed must have a nonce equal to this value plus one.
	 * 
	 * @return The sequence number for this Account.
	 */
	public long getSequence() {
		return sequence;
	}

	public long getBalance() {
		return balance;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.ACCOUNT_STATUS;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs, pos,sequence);
		pos = Format.writeVLCLong(bs,pos, balance);
		pos = Format.writeVLCLong(bs,pos, allowance);
		pos = Format.write(bs,pos, environment);
		pos = Format.write(bs,pos, holdings);
		pos = Format.write(bs,pos, controller);
		pos = Format.write(bs,pos, publicKey);
		return pos;
	}

	public static AccountStatus read(ByteBuffer bb) throws BadFormatException {
		long sequence = Format.readVLCLong(bb);
		long balance = Format.readVLCLong(bb);
		long allowance = Format.readVLCLong(bb);
		AHashMap<Symbol, Syntax> environment = Format.read(bb);
		ABlobMap<Address,Object> holdings = Format.read(bb);
		Address controller = Format.read(bb);
		AccountKey publicKey = Format.read(bb);
		return new AccountStatus(sequence, balance, allowance, environment,holdings,controller,publicKey);
	}

	@Override
	public int estimatedEncodingSize() {
		return 30+Format.estimateSize(environment)+Format.estimateSize(holdings)+Format.estimateSize(controller)+33;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	public boolean isActor() {
		return sequence==Constants.ACTOR_SEQUENCE;
	}

	/**
	 * Gets the exported function for a given symbol in an Account
	 * 
	 * Returns null if not found. This might occur because:
	 * 
	 * <ul>
	 * <li>The Account does not have the specified exported symbol.<li>
	 * <li>The exported symbol does not refer to a function</li>
	 * </ul>
	 * 
	 * @param <R>
	 * @param sym
	 * @return The function specified in Actor, or null if not
	 *         found/exported.
	 * @throws BadStateException
	 */
	public <R> IFn<R> getExportedFunction(Symbol sym) {
		ASet<Symbol> exports = getExports();
		if (exports==null) return null;
		if (!exports.contains(sym)) return null;

		// get function from environment. Anything not a function results in null
		Syntax functionSyn = environment.get(sym);
		IFn<R> fn = RT.function(functionSyn.getValue());
		return fn;
	}

	/**
	 * Gets the dynamic environment for this account. Defaults to the standard initial environment.
	 * @return
	 */
	public AHashMap<Symbol, Syntax> getEnvironment() {
		// default to standard environment
		// needed to avoid circularity in static initialisation?
		if (environment == null) return Core.ENVIRONMENT;
		return environment;
	}
	
	/**
	 * Get the controller for this Account
	 * @return Controller Address, or null if there is no controller
	 */
	public Address getController() {
		return controller;
	}

	/**
	 * Checks if this account has enough balance for a transaction consuming the
	 * specified amount.
	 * 
	 * @param amt minimum amount that must be present in the specified balance
	 */
	public boolean hasBalance(long amt) {
		if (amt < 0) return false;
		if (amt > balance) return false;
		return true;
	}

	public AccountStatus withBalance(long newBalance) {
		if (balance==newBalance) return this;
		return new AccountStatus(sequence, newBalance, allowance, environment,holdings,controller,publicKey);
	}
	
	public AccountStatus withAllowance(long newAllowance) {
		if (allowance==newAllowance) return this;
		return new AccountStatus(sequence, balance, newAllowance, environment,holdings,controller,publicKey);
	}
	
	public AccountStatus withBalances(long newBalance, long newAllowance) {
		if ((balance==newBalance)&&(allowance==newAllowance)) return this;
		return new AccountStatus(sequence, newBalance, newAllowance, environment,holdings,controller,publicKey);
	}

	public AccountStatus withEnvironment(AHashMap<Symbol, Syntax> newEnvironment) {
		// Core environment stored as null for efficiency
		if (newEnvironment==Core.ENVIRONMENT) newEnvironment=null;
		
		if (environment==newEnvironment) return this;
		return new AccountStatus(sequence, balance, allowance,newEnvironment,holdings,controller,publicKey);
	}

	/**
	 * Updates this account with a new sequence number.
	 * 
	 * @param newSequence
	 * @return Updated account, or null if the sequence number was wrong
	 */
	public AccountStatus updateSequence(long newSequence) {
		// SECURITY: shouldn't ever call updateSequence on a Actor address!
		if (isActor()) throw new Error("Trying to update Actor sequence number!");

		long expected = sequence + 1;
		if (expected != newSequence) {
			return null;
		}

		return new AccountStatus(newSequence, balance, allowance, environment,holdings,controller,publicKey);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (environment != null) environment.validateCell();
		if (holdings != null) holdings.validateCell();
	}

	/**
	 * Gets the value in the Account's environment for the given symbol.
	 * 
	 * @param <R>
	 * @param sym
	 * @return The value from the environment, or null if not found
	 */
	public <R> R getEnvironmentValue(Symbol symbol) {
		if (environment == null) return null;
		Syntax syntax = environment.get(symbol);
		if (syntax == null) return null;
		return syntax.getValue();
	}

	/**
	 * Gets the holdings for this account. Will always be a non-null map.
	 * @return Holdings map for this account
	 */
	public ABlobMap<Address, Object> getHoldings() {
		ABlobMap<Address, Object> result=holdings;
		if (result==null) return BlobMaps.empty();
		return result;
	}
	
	public Object getHolding(Address addr) {
		return holdings.get(addr);
	}
	
	public AccountStatus withHolding(Address addr,Object value) {
		ABlobMap<Address, Object> newHoldings=getHoldings();
		if (value==null) {
			newHoldings=newHoldings.dissoc(addr);
		} else if (newHoldings==null) {
			newHoldings=BlobMaps.of(addr,value);
		} else {
			newHoldings=newHoldings.assoc(addr, value);
		}
		return withHoldings(newHoldings);
	}

	private AccountStatus withHoldings(ABlobMap<Address, Object> newHoldings) {
		if (newHoldings.isEmpty()) newHoldings=null;
		if (holdings==newHoldings) return this;
		return new AccountStatus(sequence, balance, allowance, environment,newHoldings,controller,publicKey);
	}
	
	public AccountStatus withController(Address controllerAddress) {
		return new AccountStatus(sequence, balance, allowance, environment,holdings,controllerAddress,publicKey);
	}

	/**
	 * Gets *exports* from account
	 * 
	 * Returns null if the account has no *exports*. This might be for any of the following reasons:
	 * <ul>
	 * <li>The account does not define the *exports* symbol</li>
	 * </ul>
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public ASet<Symbol> getExports() {
		// get *exports* from environment, bail out if doesn't exist
		Syntax exportSyn = getEnvironment().get(Symbols.STAR_EXPORTS);
		if (exportSyn == null) return null;

		// examine *exports* value, bail out if not a set
		Object s = exportSyn.getValue();
		if (!(s instanceof ASet)) return null;

		ASet<Symbol> exports = (ASet<Symbol>) s;
		return exports;
	}

	@Override
	public int getRefCount() {
		int rc=(environment==null)?0:environment.getRefCount();
		rc+=(holdings==null)?0:holdings.getRefCount();
		return rc;
	}
	
	public <R> Ref<R> getRef(int i) {
		if (i<0) throw new IndexOutOfBoundsException(i);
		int ec=(environment==null)?0:environment.getRefCount();
		if (i<ec) return environment.getRef(i);
		int hc=(holdings==null)?0:holdings.getRefCount();
		if (i<hc+ec) return holdings.getRef(i-ec);
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	protected String ednTag() {
		return "#account";
	}

	@SuppressWarnings("unchecked")
	@Override
	public <V> V get(Keyword key) {
		if (Keywords.SEQUENCE.equals(key)) return (V) (Long)sequence;
		if (Keywords.BALANCE.equals(key)) return (V) (Long)balance;
		if (Keywords.ALLOWANCE.equals(key)) return (V) (Long)allowance;
		if (Keywords.ENVIRONMENT.equals(key)) return (V) environment;
		if (Keywords.HOLDINGS.equals(key)) return (V) holdings;
		if (Keywords.KEY.equals(key)) return (V) publicKey;
		
		return null;
	}

	@Override
	public byte getRecordTag() {
		return Tag.ACCOUNT_STATUS;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected AccountStatus updateAll(Object[] newVals) {
		long newSeq=(long)newVals[0];
		long newBal=(long)newVals[1];
		long newAllowance=(long)newVals[2];
		AHashMap<Symbol, Syntax> newEnv=(AHashMap<Symbol, Syntax>) newVals[3];
		ABlobMap<Address, Object> newHoldings=(ABlobMap<Address, Object>) newVals[4];
		Address newController = (Address)newVals[5];
		AccountKey newKey=(AccountKey)newVals[6];
		
		if ((balance==newBal)&&(sequence==newSeq)&&(newEnv==environment)&&(newHoldings==holdings)&&(newController==controller)&&(newKey==publicKey)) {
			return this;
		}
		
		return new AccountStatus(newSeq,newBal,newAllowance,newEnv,newHoldings,newController,newKey);
	}

	/**
	 * Gets the memory allowance for this account
	 * @return
	 */
	public long getAllowance() {
		return allowance;
	}

	/**
	 * Gets the memory usage for this Account. Memory usage is defined as the size of the AccountStatus Cell
	 * @return Memory usage of this Account in bytes.
	 */
	public long getMemoryUsage() {
		return this.getMemorySize();
	}

	/**
	 * Adds a change in balance to this account. Must not cause an illegal balance. Returns this instance unchanged
	 * if the delta is zero
	 * @param delta
	 * @return
	 */
	public AccountStatus addBalance(long delta) {
		if (delta==0) return this;
		return withBalance(balance+delta);
	}


}
