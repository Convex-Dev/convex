package convex.core.data;

import convex.core.Constants;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.RT;
import convex.core.lang.impl.RecordFormat;
import convex.core.util.Utils;

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
	private final long memory;
	private final AHashMap<Symbol, ACell> environment;
	private final AHashMap<Symbol, AHashMap<ACell,ACell>> metadata;
	private final BlobMap<Address, ACell> holdings;
	private final Address controller;
	private final AccountKey publicKey;
	
	private static final Keyword[] ACCOUNT_KEYS = new Keyword[] { Keywords.SEQUENCE, Keywords.BALANCE,Keywords.ALLOWANCE,Keywords.ENVIRONMENT,Keywords.METADATA,
			Keywords.HOLDINGS, Keywords.CONTROLLER, Keywords.KEY};

	private static final RecordFormat FORMAT = RecordFormat.of(ACCOUNT_KEYS);
	
	private static final int HAS_SEQUENCE=1<<FORMAT.indexFor(Keywords.SEQUENCE);
	private static final int HAS_BALANCE=1<<FORMAT.indexFor(Keywords.BALANCE);
	private static final int HAS_ALLOWANCE=1<<FORMAT.indexFor(Keywords.ALLOWANCE);
	private static final int HAS_ENVIRONMENT=1<<FORMAT.indexFor(Keywords.ENVIRONMENT);
	private static final int HAS_METADATA=1<<FORMAT.indexFor(Keywords.METADATA);
	private static final int HAS_HOLDINGS=1<<FORMAT.indexFor(Keywords.HOLDINGS);
	private static final int HAS_CONTROLLER=1<<FORMAT.indexFor(Keywords.CONTROLLER);
	private static final int HAS_KEY=1<<FORMAT.indexFor(Keywords.KEY);

	private AccountStatus(long sequence, long balance, long memory,
			AHashMap<Symbol, ACell> environment, 
			AHashMap<Symbol, AHashMap<ACell,ACell>> metadata, 
			BlobMap<Address, ACell> holdings,
			Address controller, 
			AccountKey publicKey) {
		super(FORMAT.count());
		this.sequence = sequence;
		this.balance = balance;
		this.memory = memory;
		this.environment = environment;
		this.metadata=metadata;
		this.holdings=holdings;
		this.controller=controller;
		this.publicKey=publicKey;
	}
	
	/**
	 * Create a regular account, with the specified balance and zero allowance
	 * 
	 * @param sequence Sequence number
	 * @param balance Convex Coin balance of Account
	 * @param key Public Key of new Account
	 * @return New AccountStatus
	 */
	public static AccountStatus create(long sequence, long balance, AccountKey key) {
		return new AccountStatus(sequence, balance, 0L, null,null,null,null,key);
	}

	/**
	 * Create a governance account.
	 * 
	 * @param balance Balance for governance account
	 * @return New governance AccountStatus
	 */
	public static AccountStatus createGovernance(long balance) {
		return new AccountStatus(Constants.INITIAL_SEQUENCE, balance, 0L, null,null,null,null,null);
	}

	public static AccountStatus createActor() {
		return new AccountStatus(Constants.INITIAL_SEQUENCE, 0L, 0L,null,null,null,null,null);
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

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.ACCOUNT_STATUS;
		return encodeRaw(bs,pos);
	}
	
	private int getInclusion() {
		int included=0;
		if (sequence!=0L) included|=HAS_SEQUENCE;
		if (balance!=0L) included|=HAS_BALANCE;
		if (memory!=0L) included|=HAS_ALLOWANCE;
		if (environment!=null) included|=HAS_ENVIRONMENT;
		if (metadata!=null) included|=HAS_METADATA;
		if (holdings!=null) included|=HAS_HOLDINGS;
		if (controller!=null) included|=HAS_CONTROLLER;
		if (publicKey!=null) included|=HAS_KEY;
		return included;
		
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		int included=getInclusion();
		bs[pos++]=(byte)included;
		if ((included&HAS_SEQUENCE)!=0) pos = Format.writeVLCLong(bs, pos,sequence);
		if ((included&HAS_BALANCE)!=0) pos = Format.writeVLCLong(bs,pos, balance);
		if ((included&HAS_ALLOWANCE)!=0) pos = Format.writeVLCLong(bs,pos, memory);
		if ((included&HAS_ENVIRONMENT)!=0) pos = Format.write(bs,pos, environment);
		if ((included&HAS_METADATA)!=0) pos = Format.write(bs,pos, metadata);
		if ((included&HAS_HOLDINGS)!=0) pos = Format.write(bs,pos, holdings);
		if ((included&HAS_CONTROLLER)!=0) pos = Format.write(bs,pos, controller);
		if ((included&HAS_KEY)!=0) pos = publicKey.getBytes(bs, pos);
		return pos;
	}
	
	/**
	 * Decode AccountStatus from Blob
	 * @param b Blob to read from
	 * @param pos start position in Blob 
	 * @return AccountStatus instance
	 * @throws BadFormatException in case of any encoding error
	 */
	public static AccountStatus read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		int included=b.byteAt(epos++);
		long sequence=0;
		if ((included&HAS_SEQUENCE)!=0) {
			sequence=Format.readVLCLong(b, epos);
			epos+=Format.getVLCLength(sequence);
		};
		long balance=0;
		if ((included&HAS_BALANCE)!=0) {
			balance=Format.readVLCLong(b, epos);
			epos+=Format.getVLCLength(balance);
		};		
		long allowance=0;
		if ((included&HAS_ALLOWANCE)!=0) {
			allowance=Format.readVLCLong(b, epos);
			epos+=Format.getVLCLength(allowance);
		};		
		AHashMap<Symbol, ACell> environment = null;
		if ((included&HAS_ENVIRONMENT)!=0) {
			environment=Format.read(b, epos);
			if ((environment==null)||environment.isEmpty()) throw new BadFormatException("Empty environment included!");
			epos+=environment.getEncodingLength();
		};		
		AHashMap<Symbol, AHashMap<ACell,ACell>> metadata = null;
		if ((included&HAS_METADATA)!=0) {
			metadata=Format.read(b, epos);
			if ((metadata==null)||metadata.isEmpty()) throw new BadFormatException("Empty metadata included!");
			epos+=metadata.getEncodingLength();
		};		
		BlobMap<Address,ACell> holdings = null;
		if ((included&HAS_HOLDINGS)!=0) {
			holdings=Format.read(b, epos);
			if ((holdings==null)||holdings.isEmpty()) throw new BadFormatException("Empty holdings included!");
			epos+=holdings.getEncodingLength();
		};		
		Address controller=null;
		if ((included&HAS_CONTROLLER)!=0) {
			controller=Format.read(b, epos); // TODO should be raw Address, save a byte?
			if ((controller==null)) throw new BadFormatException("Empty controller included!");
			epos+=controller.getEncodingLength();
		}
		AccountKey publicKey=null;
		if ((included&HAS_KEY)!=0) {
			publicKey=AccountKey.readRaw(b, epos);
			epos+=AccountKey.LENGTH;
		}
		
		AccountStatus result= new AccountStatus(sequence, balance, allowance, environment,metadata,holdings,controller,publicKey);
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

	/**
	 * Get the controller for this Account
	 * @return Controller Address, or null if there is no controller
	 */
	public Address getController() {
		return controller;
	}

	public AccountStatus withBalance(long newBalance) {
		if (balance==newBalance) return this;
		return new AccountStatus(sequence, newBalance, memory, environment,metadata,holdings,controller,publicKey);
	}
	

	public AccountStatus withAccountKey(AccountKey newKey) {
		if (newKey==publicKey) return this;
		return new AccountStatus(sequence, balance, memory, environment,metadata,holdings,controller,newKey);
	}
	
	public AccountStatus withMemory(long newMemory) {
		if (memory==newMemory) return this;
		return new AccountStatus(sequence, balance, newMemory, environment,metadata,holdings,controller,publicKey);
	}
	
	public AccountStatus withBalances(long newBalance, long newAllowance) {
		if ((balance==newBalance)&&(memory==newAllowance)) return this;
		return new AccountStatus(sequence, newBalance, newAllowance, environment,metadata,holdings,controller,publicKey);
	}

	public AccountStatus withEnvironment(AHashMap<Symbol, ACell> newEnvironment) {
		if ((newEnvironment!=null)&&newEnvironment.isEmpty()) newEnvironment=null;
		if (environment==newEnvironment) return this;
		return new AccountStatus(sequence, balance, memory,newEnvironment,metadata,holdings,controller,publicKey);
	}
	
	public AccountStatus withMetadata(AHashMap<Symbol, AHashMap<ACell, ACell>> newMeta) {
		if ((newMeta!=null)&&newMeta.isEmpty()) newMeta=null;
		if (metadata==newMeta) return this;
		return new AccountStatus(sequence, balance, memory,environment,newMeta,holdings,controller,publicKey);
	}
	
	@Override 
	public boolean equals(ACell a) {
		if(!(a instanceof AccountStatus)) return false;
		AccountStatus as=(AccountStatus)a;
		return equals(as);
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
		if (!(Utils.equals(publicKey, a.publicKey))) return false;
		if (!(Utils.equals(controller, a.controller))) return false;
		if (!(Utils.equals(holdings, a.holdings))) return false;
		if (!(Utils.equals(metadata, a.metadata))) return false;
		if (!(Utils.equals(environment, a.environment))) return false;
		return true;
	}

	/**
	 * Updates this account with a new sequence number.
	 * 
	 * @param newSequence New sequence number
	 * @return Updated account, or null if the sequence number was wrong
	 */
	public AccountStatus updateSequence(long newSequence) {
		// SECURITY: shouldn't ever be trying to call updateSequence on a Actor address!
		if (isActor()) throw new Error("Trying to update Actor sequence number!");

		long expected = sequence + 1;
		if (expected != newSequence) {
			return null;
		}

		return new AccountStatus(newSequence, balance, memory, environment,metadata,holdings,controller,publicKey);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (environment != null) {
			if (environment.isEmpty()) throw new InvalidDataException("Account should not have empty map as environment",this);
			environment.validateCell();
		}
		if (holdings != null) {
			if (holdings.isEmpty()) throw new InvalidDataException("Account should not have empty map as holdings",this);
			holdings.validateCell();
		}
	}

	/**
	 * Gets the value in the Account's environment for the given symbol.
	 * 
	 * @param <R> Result type
	 * @param symbol Symbol to get in Environment
	 * @return The value from the environment, or null if not found
	 */
	@SuppressWarnings("unchecked")
	public <R> R getEnvironmentValue(Symbol symbol) {
		if (environment == null) return null;
		ACell value = environment.get(symbol);
		return (R) value;
	}

	/**
	 * Gets the holdings for this account. Will always be a non-null map.
	 * @return Holdings map for this account
	 */
	public BlobMap<Address, ACell> getHoldings() {
		BlobMap<Address, ACell> result=holdings;
		if (result==null) return BlobMaps.empty();
		return result;
	}
	
	public ACell getHolding(Address addr) {
		if (holdings==null) return null;
		return holdings.get(addr);
	}
	
	public AccountStatus withHolding(Address addr,ACell value) {
		BlobMap<Address, ACell> newHoldings=getHoldings();
		if (value==null) {
			newHoldings=newHoldings.dissoc(addr);
		} else if (newHoldings==null) {
			newHoldings=BlobMaps.of(addr,value);
		} else {
			newHoldings=newHoldings.assoc(addr, value);
		}
		return withHoldings(newHoldings);
	}

	private AccountStatus withHoldings(BlobMap<Address, ACell> newHoldings) {
		if (newHoldings.isEmpty()) newHoldings=null;
		if (holdings==newHoldings) return this;
		return new AccountStatus(sequence, balance, memory, environment,metadata,newHoldings,controller,publicKey);
	}
	
	public AccountStatus withController(Address newController) {
		if (controller==newController) return this;
		return new AccountStatus(sequence, balance, memory, environment,metadata,holdings,newController,publicKey);
	}

	@Override
	public int getRefCount() {
		int rc=(environment==null)?0:environment.getRefCount();
		rc+=(metadata==null)?0:metadata.getRefCount();
		rc+=(holdings==null)?0:holdings.getRefCount();
		return rc;
	}
	
	public <R extends ACell> Ref<R> getRef(int i) {
		if (i<0) throw new IndexOutOfBoundsException(i);
		
		int ec=(environment==null)?0:environment.getRefCount();
		if (i<ec) return environment.getRef(i);
		i-=ec;
		
		int mc=(metadata==null)?0:metadata.getRefCount();
		if (i<mc) return metadata.getRef(i);
		i-=mc;

		int hc=(holdings==null)?0:holdings.getRefCount();
		if (i<hc) return holdings.getRef(i);
		
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public ACell get(ACell key) {
		if (Keywords.SEQUENCE.equals(key)) return CVMLong.create(sequence);
		if (Keywords.BALANCE.equals(key)) return CVMLong.create(balance);
		if (Keywords.ALLOWANCE.equals(key)) return CVMLong.create(memory);
		if (Keywords.ENVIRONMENT.equals(key)) return environment;
		if (Keywords.METADATA.equals(key)) return metadata;
		if (Keywords.HOLDINGS.equals(key)) return getHoldings();
		if (Keywords.CONTROLLER.equals(key)) return controller;
		if (Keywords.KEY.equals(key)) return publicKey;
		
		return null;
	}

	@Override
	public byte getTag() {
		return Tag.ACCOUNT_STATUS;
	}

	@Override
	public AccountStatus updateRefs(IRefFunction func) {
		AHashMap<Symbol, ACell> newEnv=Ref.updateRefs(environment, func);
		AHashMap<Symbol, AHashMap<ACell,ACell>> newMeta=Ref.updateRefs(metadata, func);
		BlobMap<Address, ACell> newHoldings=Ref.updateRefs(holdings, func);
		
		if ((newEnv==environment)&&(newMeta==metadata)&&(newHoldings==holdings)) {
			return this;
		}
		
		return new AccountStatus(sequence,balance,memory,newEnv,newMeta,newHoldings,controller,publicKey);
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
	 * @return Updates account record
	 */
	public AccountStatus addBalance(long delta) {
		if (delta==0) return this;
		return withBalance(balance+delta);
	}

	/**
	 * Gets the public key for this Account. May bu null (e.g. for Actors)
	 * @return Account public key
	 */
	public AccountKey getAccountKey() {
		return publicKey;
	}

	/**
	 * Gets the Metadata map for this Account
	 * @return Metadata map (never null, but may be empty)
	 */
	public AHashMap<Symbol,AHashMap<ACell,ACell>> getMetadata() {
		if (metadata==null) return Maps.empty();
		return metadata;
	}
	
	/**
	 * Gets the Environment for this account. Defaults to the an empty map if no Environment has been created.
	 * @return Environment map for this Account
	 */
	public AHashMap<Symbol, ACell> getEnvironment() {
		if (environment==null) return Maps.empty();
		return environment;
	}

	/**
	 * Gets the callable functions from this Account.
	 * @return Set of callable Symbols
	 */
	public ASet<Symbol> getCallableFunctions() {
		ASet<Symbol> results=Sets.empty();
		if (metadata==null) return results;
		for (Entry<Symbol, AHashMap<ACell, ACell>> me:metadata.entrySet()) {
			ACell callVal=me.getValue().get(Keywords.CALLABLE_Q);
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
		if (exported==null) return null;
		AFn<R> fn=RT.ensureFunction(exported);
		if (fn==null) return null;
		AHashMap<ACell,ACell> md=getMetadata().get(sym);
		if (RT.bool(md.get(Keywords.CALLABLE_Q))) {
			// We have both a function and required metadata tag
			return fn;
		}
		return null;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}



}
