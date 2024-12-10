package convex.core.cvm;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Class representing the current on-chain status of an account.
 * 
 * Accounts may be User accounts or Actor accounts.
 * 
 * "People said I should accept the world. Bullshit! I don't accept the world."
 * - Richard Stallman
 */
public class AccountStatus extends ARecordGeneric {
	private final long sequence;
	private final AccountKey publicKey;
	private final long balance;
	private final long memory;
	private Index<Address, ACell> holdings;
	private ACell controller;
	private AHashMap<Symbol, ACell> environment;
	private AHashMap<Symbol, AHashMap<ACell,ACell>> metadata;
	private final Address parent;
	
	// Sequence of fields in account. 
	private static final Keyword[] ACCOUNT_KEYS = new Keyword[] { Keywords.SEQUENCE, Keywords.KEY, 
			Keywords.BALANCE,Keywords.ALLOWANCE,
			Keywords.HOLDINGS, Keywords.CONTROLLER,
			Keywords.ENVIRONMENT,Keywords.METADATA,
			Keywords.PARENT
			};
	
	protected static final Index<Address, ACell> EMPTY_HOLDINGS = Index.none();

	private static final RecordFormat FORMAT = RecordFormat.of(ACCOUNT_KEYS);
	
	private static final long IX_SEQUENCE=FORMAT.indexFor(Keywords.SEQUENCE);
	private static final long IX_KEY=FORMAT.indexFor(Keywords.KEY);
	private static final long IX_BALANCE=FORMAT.indexFor(Keywords.BALANCE);
	private static final long IX_ALLOWANCE=FORMAT.indexFor(Keywords.ALLOWANCE);
	private static final long IX_HOLDINGS=FORMAT.indexFor(Keywords.HOLDINGS);
	private static final long IX_CONTROLLER=FORMAT.indexFor(Keywords.CONTROLLER);
	private static final long IX_ENVIRONMENT=FORMAT.indexFor(Keywords.ENVIRONMENT);
	private static final long IX_METADATA=FORMAT.indexFor(Keywords.METADATA);
	private static final long IX_PARENT=FORMAT.indexFor(Keywords.PARENT);

	private AccountStatus(long sequence, AccountKey publicKey, long balance,
			long memory, 
			Index<Address, ACell> holdings, 
			ACell controller,
			AHashMap<Symbol, ACell> environment, 
			AHashMap<Symbol, AHashMap<ACell,ACell>> metadata, Address parent) {
		super(CVMTag.ACCOUNT_STATUS,FORMAT,Vectors.create(CVMLong.create(sequence),publicKey,CVMLong.create(balance),CVMLong.create(memory),holdings,controller,environment,metadata,parent));
		this.sequence = sequence;
		this.publicKey = publicKey;
		this.balance = balance;
		this.memory = memory;
		this.holdings=holdings;
		this.controller=controller;
		this.environment = environment;
		this.metadata=metadata;
		this.parent=parent;
	}
	
	private AccountStatus(AVector<ACell> values) {
		super(CVMTag.ACCOUNT_STATUS,FORMAT,values);
		this.sequence = RT.ensureLong(values.get(IX_SEQUENCE)).longValue();
		this.publicKey = RT.ensureAccountKey(values.get(IX_KEY));
		this.balance = RT.ensureLong(values.get(IX_BALANCE)).longValue();;
		this.memory = RT.ensureLong(values.get(IX_ALLOWANCE)).longValue();;
		this.parent = RT.ensureAddress(values.get(IX_PARENT));
	}

	/**
	 * Create a regular account, with the specified balance and zero memory allowance
	 * 
	 * @param sequence Sequence number
	 * @param balance Convex Coin balance of Account
	 * @param key Public Key of new Account
	 * @return New AccountStatus
	 */
	public static AccountStatus create(long sequence, long balance, AccountKey key) {
		if (sequence<0) throw new IllegalArgumentException("negative sequence");
		if (balance<0) throw new IllegalArgumentException("negative balance");
		return new AccountStatus(sequence, key, balance, 0L,null,null,null,null,null);
	}
	
	public static AccountStatus createUnsafe(long sequence, long balance, AccountKey key) {
		return new AccountStatus(sequence, key, balance, 0L,null,null,null,null,null);
	}

	public static AccountStatus createActor() {
		return new AccountStatus(Constants.INITIAL_SEQUENCE, null, 0L,0L,null,null,null,null,null);
	}

	public static AccountStatus create(long balance, AccountKey key) {
		return create(0, balance,key);
	}

	/**
	 * Create a completely empty Account record, with no balance or public key
	 * @return Empty Account record
	 */
	public static AccountStatus create() {
		return create(0, 0L,null);
	}

	/**
	 * Gets the sequence number for this Account. The sequence number is the number
	 * of transactions executed by this account to date. It will be zero for new
	 * Accounts.
	 * 
	 * The next transaction executed must have a sequence number equal to this value plus one.
	 * 
	 * @return The sequence number for this Account.
	 */
	public long getSequence() {
		return sequence;
	}

	public long getBalance() {
		return balance;
	}
	
	/**
	 * Decode AccountStatus from Blob
	 * @param b Blob to read from
	 * @param pos start position in Blob 
	 * @return AccountStatus instance
	 * @throws BadFormatException in case of any encoding error
	 */
	public static AccountStatus read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();
		
		AccountStatus result=new AccountStatus(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	@Override
	public int estimatedEncodingSize() {
		return 30+Format.estimateEncodingSize(environment)+Format.estimateEncodingSize(holdings)+Format.estimateEncodingSize(controller)+33;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Returns true if this account is an Actor, i.e. is not a user account (null public key)
	 * @return true if actor, false otherwise
	 */
	public boolean isActor() {
		return publicKey==null;
	}

	public AccountStatus withAccountKey(AccountKey newKey) {
		if (newKey==publicKey) return this;
		return new AccountStatus(values.assoc(IX_KEY,newKey));
	}
	
	public AccountStatus withBalance(long newBalance) {
		if (balance==newBalance) return this;
		return new AccountStatus(values.assoc(IX_BALANCE,CVMLong.create(newBalance)));
	}

	public AccountStatus withMemory(long newMemory) {
		if (memory==newMemory) return this;
		return new AccountStatus(values.assoc(IX_ALLOWANCE,CVMLong.create(newMemory)));
	}
	
	public AccountStatus withBalances(long newBalance, long newAllowance) {
		if ((balance==newBalance)&&(memory==newAllowance)) return this;
		AVector<ACell> nv=values;
		nv=nv.assoc(IX_BALANCE, CVMLong.create(newBalance));
		nv=nv.assoc(IX_ALLOWANCE, CVMLong.create(newAllowance));
		return new AccountStatus(nv);
	}

	private AccountStatus withHoldings(Index<Address, ACell> newHoldings) {
		if (getHoldings()==newHoldings) return this;
		return new AccountStatus(values.assoc(IX_HOLDINGS, newHoldings));
	}
	
	public AccountStatus withController(ACell newController) {
		if (getController()==newController) return this;
		return new AccountStatus(values.assoc(IX_CONTROLLER, newController));
	}

	public AccountStatus withEnvironment(AHashMap<Symbol, ACell> newEnvironment) {
		if (getEnvironment()==newEnvironment) return this;
		return new AccountStatus(values.assoc(IX_ENVIRONMENT, newEnvironment));
	}
	
	public AccountStatus withMetadata(AHashMap<Symbol, AHashMap<ACell, ACell>> newMeta) {
		if (getMetadata()==newMeta) return this;
		return new AccountStatus(values.assoc(IX_METADATA, newMeta));
	}
	
	public AccountStatus withParent(Address newParent) {
		if (parent==newParent) return this;
		return new AccountStatus(values.assoc(IX_PARENT,newParent));
	}
	
	@Override 
	public boolean equals(ACell o) {
		if (o instanceof AccountStatus) return equals((AccountStatus)o);
		return Cells.equalsGeneric(this, o);
	}
	
	/**
	 * Tests if this account is equal to another Account
	 * @param a AccountStatus to compare with
	 * @return true if equal, false otherwise
	 */
	public boolean equals(AccountStatus a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		
		if (balance!=a.balance) return false;
		if (sequence!=a.sequence) return false;
		if (memory!=a.memory) return false;
		return (Cells.equals(values, a.values));
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (sequence<0) throw new InvalidDataException("Neagitive sequence: "+sequence,this);
		if (!Coin.isValidAmount(balance)) throw new InvalidDataException("Illegal balance: "+balance,this);
	}

	/**
	 * Gets the value in the Account's environment for the given symbol.
	 * 
	 * @param <R> Result type
	 * @param symbol Symbol to get in Environment
	 * @return The value from the environment, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> R getEnvironmentValue(Symbol symbol) {
		AHashMap<Symbol, ACell> env = getEnvironment();
		if (env == null) return null;
		ACell value = env.get(symbol);
		return (R) value;
	}

	public ACell getHolding(Address addr) {
		Index<Address, ACell> hodls=getHoldings();
		if (hodls==null) return null;
		return hodls.get(addr);
	}

	public AccountStatus withHolding(Address addr,ACell value) {
		Index<Address, ACell> hodls=getHoldings();
		if (hodls==null) {
			hodls=(value==null)?null:Index.of(addr,value);
		} else if (value==null) { 
			hodls=hodls.dissoc(addr);
			if (hodls.isEmpty()) hodls=null;
		} else {
			hodls=hodls.assoc(addr, value);
		}
		return withHoldings(hodls);
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.SEQUENCE.equals(key)) return CVMLong.create(sequence);
		if (Keywords.KEY.equals(key)) return publicKey;
		if (Keywords.BALANCE.equals(key)) return CVMLong.create(balance);
		if (Keywords.ALLOWANCE.equals(key)) return CVMLong.create(memory);
		if (Keywords.HOLDINGS.equals(key)) return values.get(IX_HOLDINGS);
		if (Keywords.CONTROLLER.equals(key)) return getController();
		if (Keywords.ENVIRONMENT.equals(key)) return getEnvironment();
		if (Keywords.METADATA.equals(key)) return getMetadata();
		if (Keywords.PARENT.equals(key)) return parent;
		
		return null;
	}

	/**
	 * Gets the memory allowance for this account
	 * @return Memory allowance in bytes
	 */
	public long getMemory() {
		return memory;
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
	 * @param delta Amount of Convex copper to add
	 * @return Updated account record
	 */
	public AccountStatus addBalanceAndSequence(long delta) {
		AVector<ACell> nv=values;
		nv=nv.assoc(IX_SEQUENCE,CVMLong.create(sequence+1));
		nv=nv.assoc(IX_BALANCE,CVMLong.create(balance+delta));
		return new AccountStatus(nv);
	}

	/**
	 * Gets the public key for this Account. May bu null (e.g. for Actors)
	 * @return Account public key
	 */
	public AccountKey getAccountKey() {
		return publicKey;
	}
	
	/**
	 * Gets the parent address for this account
	 * @return Address of parent account
	 */
	public Address getParent() {
		return parent;
	}
	
	/**
	 * Gets the holdings for this account.
	 * @return Holdings map for this account
	 */
	public Index<Address, ACell> getHoldings() {
		if (holdings==null) {
			holdings=RT.ensureIndex(values.get(IX_HOLDINGS));
			// if (holdings==null) holdings=EMPTY_HOLDINGS;
		}
		if (holdings==null) return EMPTY_HOLDINGS;
		return holdings;
	}
	
	/**
	 * Get the controller for this Account
	 * @return Controller Address, or null if there is no controller
	 */
	public ACell getController() {
		if (controller==null) controller=values.get(IX_CONTROLLER);
		return controller;
	}
	
	/**
	 * Gets the Environment for this account. Defaults to the an empty map if no Environment has been created.
	 * @return Environment map for this Account
	 */
	@SuppressWarnings("unchecked")
	public AHashMap<Symbol, ACell> getEnvironment() {
		if (environment==null) {
			environment=(AHashMap<Symbol, ACell>)values.get(IX_ENVIRONMENT);
		}
		return environment;
	}

	/**
	 * Gets the Metadata map for this Account
	 * @return Metadata map (never null, but may be empty)
	 */
	@SuppressWarnings("unchecked")
	public AHashMap<Symbol,AHashMap<ACell,ACell>> getMetadata() {
		if (metadata==null) {
			metadata=(AHashMap<Symbol,AHashMap<ACell,ACell>>)(values.get(IX_METADATA));
		}
		return metadata;
	}

	/**
	 * Gets the callable functions from this Account.
	 * @return Set of callable Symbols
	 */
	public ASet<Symbol> getCallableFunctions() {
		ASet<Symbol> results=Sets.empty();
		if (metadata==null) return results;
		for (Entry<Symbol, AHashMap<ACell, ACell>> me:metadata.entrySet()) {
			ACell callVal=me.getValue().get(Keywords.CALLABLE_META);
			if (RT.bool(callVal)) {
				Symbol sym=me.getKey();
				if (RT.ensureFunction(getEnvironmentValue(sym))==null) continue;
				results=results.conj(sym);
			}
		}
		return results;
	}

	/**
	 * Gets a callable function from the environment, or null if not callable
	 * @param sym Symbol to look up
	 * @return Callable function if found, null otherwise
	 */
	public <R extends ACell> AFn<R> getCallableFunction(Symbol sym) {
		ACell exported=getEnvironmentValue(sym);
		AFn<R> fn=RT.ensureFunction(exported);
		
		if (fn==null) return null;
		AHashMap<ACell,ACell> md=getMetadata().get(sym);
		if (RT.bool(md.get(Keywords.CALLABLE_META))) {
			// We have both a function and required metadata tag
			return fn;
		}
		return null;
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new AccountStatus(newValues);
	}

	/**
	 * Gets metadata for a Symbol. 
	 * @param sym Symbol to get metadata for
	 * @return Metadata map. Returns empty map if symbol not defined or no metadata set
	 */
	public AHashMap<ACell, ACell> getMetadata(Symbol sym) {
		AHashMap<Symbol, AHashMap<ACell, ACell>> meta = getMetadata();
		if (meta==null) return Maps.empty();
		return meta.get(sym,Maps.empty());
	}

	/**
	 * Gets environment entry for a given symbol
	 * @param sym
	 * @return Environment entry, or null if not defined in this account
	 */
	public MapEntry<Symbol, ACell> getEnvironmentEntry(Symbol sym) {
		AHashMap<Symbol, ACell> env = getEnvironment();
		if (env==null) return null;
		MapEntry<Symbol, ACell> entry = env.getEntry(sym);
		return entry;
	}
	



}
