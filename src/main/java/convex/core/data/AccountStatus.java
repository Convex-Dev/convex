package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
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
	private final long memory;
	private final AHashMap<Symbol, ACell> environment;
	private final AHashMap<Symbol, AHashMap<ACell,ACell>> metadata;
	private final ABlobMap<Address, ACell> holdings;
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
			ABlobMap<Address, ACell> holdings,
			Address controller, 
			AccountKey publicKey) {
		super(FORMAT);
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
	 * @param sequence
	 * @param balance
	 * @return New AccountStatus
	 */
	public static AccountStatus create(long sequence, long balance, AccountKey key) {
		return new AccountStatus(sequence, balance, 0L, null,null,null,null,key);
	}

	/**
	 * Create a governance account.
	 * 
	 * @param sequence
	 * @param balance
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
		if ((included&HAS_KEY)!=0) pos = publicKey.writeToBuffer(bs, pos);
		return pos;
	}

	public static AccountStatus read(ByteBuffer bb) throws BadFormatException {
		int included=bb.get();
		long sequence = ((included&HAS_SEQUENCE)!=0) ? Format.readVLCLong(bb) : 0L;
		long balance = ((included&HAS_BALANCE)!=0) ? Format.readVLCLong(bb) : 0L;
		long allowance = ((included&HAS_ALLOWANCE)!=0) ? Format.readVLCLong(bb) : 0L;
		AHashMap<Symbol, ACell> environment = ((included&HAS_ENVIRONMENT)!=0) ? Format.read(bb):null;
		AHashMap<Symbol, AHashMap<ACell,ACell>> metadata = ((included&HAS_METADATA)!=0) ? Format.read(bb) : null;
		ABlobMap<Address,ACell> holdings = ((included&HAS_HOLDINGS)!=0) ? Format.read(bb) : null;
		Address controller = ((included&HAS_CONTROLLER)!=0) ? Format.read(bb) : null;
		AccountKey publicKey = ((included&HAS_KEY)!=0) ? AccountKey.readRaw(bb) : null;
		return new AccountStatus(sequence, balance, allowance, environment,metadata,holdings,controller,publicKey);
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
		return publicKey==null;
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
	public <R extends ACell> AFn<R> getExportedFunction(Symbol sym) {
		ASet<Symbol> exports = getExports();
		if (exports==null) return null;
		if (!exports.contains(sym)) return null;

		// get function from environment. Anything not a function results in null
		ACell maybeFn = environment.get(sym);
		
		AFn<R> fn = RT.castFunction(maybeFn);
		return fn;
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

	/**
	 * Updates this account with a new sequence number.
	 * 
	 * @param newSequence
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
			if (environment.isEmpty()) throw new InvalidDataException("Account should not have empty map as metadata",this);
			holdings.validateCell();
		}
	}

	/**
	 * Gets the value in the Account's environment for the given symbol.
	 * 
	 * @param <R>
	 * @param sym
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
	public ABlobMap<Address, ACell> getHoldings() {
		ABlobMap<Address, ACell> result=holdings;
		if (result==null) return BlobMaps.empty();
		return result;
	}
	
	public ACell getHolding(Address addr) {
		if (holdings==null) return null;
		return holdings.get(addr);
	}
	
	public AccountStatus withHolding(Address addr,ACell value) {
		ABlobMap<Address, ACell> newHoldings=getHoldings();
		if (value==null) {
			newHoldings=newHoldings.dissoc(addr);
		} else if (newHoldings==null) {
			newHoldings=BlobMaps.of(addr,value);
		} else {
			newHoldings=newHoldings.assoc(addr, value);
		}
		return withHoldings(newHoldings);
	}

	private AccountStatus withHoldings(ABlobMap<Address, ACell> newHoldings) {
		if (newHoldings.isEmpty()) newHoldings=null;
		if (holdings==newHoldings) return this;
		return new AccountStatus(sequence, balance, memory, environment,metadata,newHoldings,controller,publicKey);
	}
	
	public AccountStatus withController(Address newController) {
		if (controller==newController) return this;
		return new AccountStatus(sequence, balance, memory, environment,metadata,holdings,newController,publicKey);
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
		ACell exports = getEnvironment().get(Symbols.STAR_EXPORTS);
		if (exports == null) return null;

		if (!(exports instanceof ASet)) return null;

		ASet<Symbol> result = (ASet<Symbol>) exports;
		return result;
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
	protected String ednTag() {
		return "#account";
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

	@SuppressWarnings("unchecked")
	@Override
	protected AccountStatus updateAll(ACell[] newVals) {
		long newSeq=((CVMLong)newVals[0]).longValue();
		long newBal=((CVMLong)newVals[1]).longValue();
		long newAllowance=((CVMLong)newVals[2]).longValue();
		AHashMap<Symbol, ACell> newEnv=(AHashMap<Symbol, ACell>) newVals[3];
		AHashMap<Symbol, AHashMap<ACell,ACell>> newMeta=(AHashMap<Symbol, AHashMap<ACell,ACell>>) newVals[4];
		ABlobMap<Address, ACell> newHoldings=(ABlobMap<Address, ACell>) newVals[5];
		if ((newHoldings!=null)&&newHoldings.isEmpty()) newHoldings=null; // switch empty maps to null
		Address newController = (Address)newVals[6];
		AccountKey newKey=(AccountKey)newVals[7];
		
		if ((balance==newBal)&&(sequence==newSeq)&&(newEnv==environment)&&(newMeta==metadata)&&(newHoldings==holdings)&&(newController==controller)&&(newKey==publicKey)) {
			return this;
		}
		
		return new AccountStatus(newSeq,newBal,newAllowance,newEnv,newMeta,newHoldings,newController,newKey);
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
	 * @param delta
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

	public AHashMap<Symbol,AHashMap<ACell,ACell>> getMetadata() {
		if (metadata==null) return Maps.empty();
		return metadata;
	}
	
	/**
	 * Gets the Environment for this account. Defaults to the an empty map if no Environment has been created.
	 * @return
	 */
	public AHashMap<Symbol, ACell> getEnvironment() {
		if (environment==null) return Maps.empty();
		return environment;
	}
	





}
