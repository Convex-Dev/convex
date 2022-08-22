package convex.core.lang;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.State;
import convex.core.data.ABlobMap;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.AObject;
import convex.core.data.ASequence;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.Hash;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.exceptions.TODOException;
import convex.core.init.Init;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.ATrampoline;
import convex.core.lang.impl.ErrorValue;
import convex.core.lang.impl.HaltValue;
import convex.core.lang.impl.RecurValue;
import convex.core.lang.impl.Reduced;
import convex.core.lang.impl.ReturnValue;
import convex.core.lang.impl.RollbackValue;
import convex.core.lang.impl.TailcallValue;
import convex.core.util.Economics;
import convex.core.util.Errors;

/**
 * Representation of CVM execution context.
 * <p>
 *
 * Execution context includes:
 * - The current on-Chain state, including the defined execution environment for each Address
 * - Local lexical bindings for the current execution position
 * - The identity (as an Address) for the origin, caller and currently executing actor
 * - Juice and execution depth current status for
 * - Result of the last operation executed (which may be exceptional)
 * <p>
 * Interestingly, this behaves like Scala's ZIO[Context-Stuff, AExceptional, T]
 * <p>
 * Contexts maintain checks on execution depth and juice to control against arbitrary on-chain
 * execution. Coupled with limits on total juice and limits on memory allocation
 * per unit juice, this places an upper bound on execution time and space.
 * <p>
 * Contexts also support returning exceptional values. Exceptional results may come
 * from arbitrary nested depth (which requires a bit of complexity to reset depth when
 * catching exceptional values). We avoid using Java exceptions here, because exceptionals
 * are "normal" in the context of on-chain execution, and we'd like to avoid the overhead
 * of exception handling - may be especially important in DoS scenarios.
 * <p>
 * "If you have a procedure with 10 parameters, you probably missed some"
 * - Alan Perlis
 *
 * @param <T> Result type of Context
 */
public class Context<T extends ACell> extends AObject {
	private static final long INITIAL_JUICE = 0;

	// Default values
	private static final AVector<AVector<ACell>> DEFAULT_LOG = null;
	private static int DEFAULT_DEPTH = 0;
	private static final AExceptional DEFAULT_EXCEPTION = null;
	private static final long DEFAULT_OFFER = 0L;
	public static final AVector<ACell> EMPTY_BINDINGS=Vectors.empty();
	// private static final Logger log=Logger.getLogger(Context.class.getName());

	/*
	 *  Frequently changing fields during execution.
	 *
	 *  While these are mutable, it is also very cheap to just fork() short-lived Contexts
	 *  because the JVM generational GC will just sweep them up shortly afterwards.
	 */

	private long juice;
	private T result;
	private AExceptional exception;
	private int depth;
	private AVector<ACell> localBindings;
	private ChainState chainState;

	/**
	 * Local log is a [vector of [address values] entries]
	 */
	private AVector<AVector<ACell>> log;
	private CompilerState compilerState;


	/**
	 * Inner class compiler state.
	 *
	 * Maintains a mapping of Symbols to positions in a definition vector corresponding to lexical scope.
	 *
	 */
	public static final class CompilerState {
		public static final CompilerState EMPTY = new CompilerState(Vectors.empty(),Maps.empty());

		private AVector<Syntax> definitions;
		private AHashMap<Symbol,CVMLong> mappings;

		private CompilerState(AVector<Syntax> definitions, AHashMap<Symbol,CVMLong> mappings) {
			this.definitions=definitions;
			this.mappings=mappings;
		}

		public CompilerState define(Symbol sym, Syntax syn) {
			long position=definitions.count();
			AVector<Syntax> newDefs=definitions.conj(syn);
			AHashMap<Symbol,CVMLong> newMaps=mappings.assoc(sym, CVMLong.create(position));
			return new CompilerState(newDefs,newMaps);
		}

		public CVMLong getPosition(Symbol sym) {
			return mappings.get(sym);
		}
	}

	/**
	 * Inner class for less-frequently changing state related to Actor execution
	 * Should save some allocation / GC on average, since it will change less
	 * frequently than the surrounding Context and can be cheaply copied by reference.
	 *
	 * SECURITY: security critical, since it determines the current *address* and *caller*
	 * which in turn controls access to most account resources and rights.
	 */
	private static final class ChainState {
		private final State state;
		private final Address origin;
		private final Address caller;
		private final Address address;
		private final long offer;

		/**
		 * Cached copy of the current environment. Avoid looking up via Address each time.
		 */
		private final AHashMap<Symbol, ACell> environment;
		private final AHashMap<Symbol, AHashMap<ACell,ACell>> metadata;

		private ChainState(State state, Address origin,Address caller, Address address,AHashMap<Symbol, ACell> environment, AHashMap<Symbol,AHashMap<ACell,ACell>> metadata, long offer) {
			this.state=state;
			this.origin=origin;
			this.caller=caller;
			this.address=address;
			this.environment=environment;
			this.metadata=metadata;
			this.offer=offer;
		}

		public static ChainState create(State state, Address origin, Address caller, Address address, long offer) {
			AHashMap<Symbol, ACell> environment=Core.ENVIRONMENT;
			AHashMap<Symbol, AHashMap<ACell,ACell>> metadata=Core.METADATA;
			if (address!=null) {
				AccountStatus as=state.getAccount(address);
				if (as!=null) {
					environment=as.getEnvironment();
					metadata=as.getMetadata();
				}
			}
			return new ChainState(state,origin,caller,address,environment,metadata,offer);
		}

		public ChainState withStateOffer(State newState,long newOffer) {
			if ((state==newState)&&(offer==newOffer)) return this;
			return create(newState,origin,caller,address,newOffer);
		}

		private ChainState withState(State newState) {
			if (state==newState) return this;
			return create(newState,origin,caller,address,offer);
		}

		private long getOffer() {
			return offer;
		}

		/**
		 * Gets the current defined environment
		 * @return
		 */
		private AHashMap<Symbol, ACell> getEnvironment() {
			if (environment==null) return Maps.empty();
			return environment;
		}

		private ChainState withEnvironment(AHashMap<Symbol, ACell> newEnvironment)  {
			if (environment==newEnvironment) return this;
			AccountStatus as=state.getAccount(address);
			AccountStatus nas=as.withEnvironment(newEnvironment);
			State newState=state.putAccount(address,nas);
			return withState(newState);
		}

		public ChainState withEnvironment(AHashMap<Symbol, ACell> newEnvironment,
				AHashMap<Symbol, AHashMap<ACell, ACell>> newMeta) {
			if ((environment==newEnvironment)&&(metadata==newMeta)) return this;
			AccountStatus as=state.getAccount(address);
			AccountStatus nas=as.withEnvironment(newEnvironment).withMetadata(newMeta);
			State newState=state.putAccount(address,nas);
			return withState(newState);
		}

		private ChainState withAccounts(AVector<AccountStatus> newAccounts) {
			return withState(state.withAccounts(newAccounts));
		}

		public AHashMap<Symbol, AHashMap<ACell, ACell>> getMetadata() {
			if (metadata==null) return Maps.empty();
			return metadata;
		}


	}

	private Context(ChainState chainState, long juice, AVector<ACell> localBindings2, T result,int depth, AExceptional exception, AVector<AVector<ACell>> log, CompilerState comp) {
		this.chainState=chainState;
		this.juice=juice;
		this.localBindings=localBindings2;
		this.result=result;
		this.depth=depth;
		this.exception=exception;
		this.log=log;
		this.compilerState=comp;
	}

	@SuppressWarnings("unchecked")
	private static <T extends ACell> Context<T> create(ChainState cs, long juice, AVector<ACell> localBindings, ACell result, int depth,AVector<AVector<ACell>> log, CompilerState comp) {
		if (juice<0) throw new IllegalArgumentException("Negative juice! "+juice);
		Context<T> ctx= new Context<T>(cs,juice,localBindings,(T)result,depth,DEFAULT_EXCEPTION,log,comp);
		return ctx;
	}

	private static <T extends ACell> Context<T> create(State state, long juice,AVector<ACell> localBindings, T result, int depth, Address origin,Address caller, Address address, long offer, AVector<AVector<ACell>> log, CompilerState comp) {
		ChainState chainState=ChainState.create(state,origin,caller,address,offer);
		return create(chainState,juice,localBindings,result,depth,log,comp);
	}

	/**
	 * Creates an execution context with a default actor address.
	 *
	 * Useful for Testing
	 *
	 * @param state State to use for this Context
	 * @return Fake context
	 */
	public static <R extends ACell> Context<R> createFake(State state) {
		return createFake(state,Init.CORE_ADDRESS);
	}

	/**
	 * Creates a "fake" execution context for the given address.
	 *
	 * Not valid for use in real transactions, but can be used to
	 * compute stuff off-chain "as-if" the actor made the call.
	 *
	 * @param state State to use for this Context
	 * @param origin Origin address to use
	 * @return Fake context
	 */
	public static <R extends ACell> Context<R> createFake(State state, Address origin) {
		if (origin==null) throw new IllegalArgumentException("Null address!");
		return create(state,Constants.MAX_TRANSACTION_JUICE,EMPTY_BINDINGS,null,0,origin,null,origin, 0, DEFAULT_LOG,null);
	}

	/**
	 * Creates an initial execution context with the specified actor as origin, and reserving the appropriate
	 * amount of juice.
	 *
	 * Juice reserve is extracted from the actor's current balance.
	 *
	 * @param state Initial State for Context
	 * @param origin Origin Address for Context
	 * @param juice Initial juice for Context
	 * @return Initial execution context with reserved juice.
	 */
	public static <T extends ACell> Context<T> createInitial(State state, Address origin,long juice) {
		AccountStatus as=state.getAccount(origin);
		if (as==null) {
			// no account
			return Context.createFake(state).withError(ErrorCodes.NOBODY);
		}

		long balance=as.getBalance();
		long juicePrice=state.getJuicePrice().longValue();

		// reduce juice if insufficient balance
		juice=Math.min(juice,balance/juicePrice);
		long reserve=juicePrice*juice;

		assert (reserve<=balance) : "Reserve calculation failed!";

		long newBalance=balance-reserve;
		as=as.withBalance(newBalance);
		state=state.putAccount(origin, as);
		return create(state,juice,EMPTY_BINDINGS,null,DEFAULT_DEPTH,origin,null,origin,INITIAL_JUICE,DEFAULT_LOG,null);
	}




	/**
	 * Performs key actions at the end of a transaction:
	 * <ul>
	 * <li>Refunds juice</li>
	 * <li>Accumulates used juice fees in globals</li>
	 * </ul>
	 *
	 * @param initialState State before transaction execution (after prepare)
	 * @param initialJuice total juice reserved at start of transaction
	 * @return Updated context
	 */
	public Context<T> completeTransaction(State initialState, long initialJuice) {
		// get state at end of transaction application
		State state=getState();

		long remainingJuice=Math.max(0L, juice);
		long usedJuice=initialJuice-remainingJuice;
		long juicePrice=initialState.getJuicePrice().longValue();
		assert(usedJuice>=0);

		long refund=0L;

		// maybe refund remaining juice
		if (remainingJuice>0L) {
			// Compute refund. Shouldn't be possible to overflow?
			// But do a paranoid checked multiply just in case
			refund+=Math.multiplyExact(remainingJuice,juicePrice);
		}

		// compute memory delta
		Address address=getAddress();
		AccountStatus account=state.getAccount(address);
		long memUsed=state.getMemorySize()-initialState.getMemorySize();
		long allowance=account.getMemory();
		long balanceLeft=account.getBalance();
		boolean memoryFailure=false;

		long memorySpend=0L; // usually zero
		if (memUsed>0) {
			long allowanceUsed=Math.min(allowance, memUsed);
			if (allowanceUsed>0) {
				account=account.withMemory(allowance-allowanceUsed);
			}

			// compute additional memory purchase requirement beyond allowance
			long purchaseNeeded=memUsed-allowanceUsed;
			if (purchaseNeeded>0) {
				AccountStatus pool=state.getAccount(Init.MEMORY_EXCHANGE_ADDRESS);
				// we do memory purchase if pool exists
				if (pool!=null) {
					long poolBalance=pool.getBalance();
					long poolAllowance=pool.getMemory();
					memorySpend=Economics.swapPrice(purchaseNeeded, poolAllowance, poolBalance);

					if ((refund+balanceLeft)>=memorySpend) {
						// enough to cover memory price, so automatically buy from pool
						// System.out.println("Buying "+purchaseNeeded+" memory for: "+price);
						pool=pool.withBalances(poolBalance+memorySpend, poolAllowance-purchaseNeeded);
						state=state.putAccount(Init.MEMORY_EXCHANGE_ADDRESS,pool);
					} else {
						// Insufficient memory, so need to roll back state to before transaction
						// origin should still pay transaction fees, but no memory costs
						memorySpend=0L;
						state=initialState;
						account=state.getAccount(address);
						memoryFailure=true;
					}
				}
			}
		} else {
			// credit any unused memory back to allowance (may be zero)
			long allowanceCredit=-memUsed;
			account=account.withMemory(allowance+allowanceCredit);
		}

		// Make balance changes if needed for refund and memory purchase
		account=account.addBalance(refund-memorySpend);

		// update Account
		state=state.putAccount(address,account);

		// maybe add used juice to miner fees
		if (usedJuice>0L) {
			long transactionFees = usedJuice*juicePrice;
			long oldFees=state.getGlobalFees().longValue();
			long newFees=oldFees+transactionFees;
			state=state.withGlobalFees(CVMLong.create(newFees));
		}

		// final state update and result reporting
		Context<T> rctx=this.withState(state);
		if (memoryFailure) {
			rctx=rctx.withError(ErrorCodes.MEMORY, "Unable to allocate additional memory required for transaction ("+memUsed+" bytes)");
		}
		return rctx;
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withState(State newState) {
		return (Context<R>) this.withChainState(chainState.withState(newState));
	}

	/**
	 * Get the latest state from this Context
	 * @return State instance
	 */
	public State getState() {
		return chainState.state;
	}

	/**
	 * Get the juice available in this Context
	 * @return Juice available
	 */
	public long getJuice() {
		return juice;
	}

	/**
	 * Get the current offer from this Context
	 * @return Offered amount in Convex copper
	 */
	public long getOffer() {
		return chainState.getOffer();
	}

	/**
	 * Gets the current Environment
	 * @return Environment map
	 */
	public AHashMap<Symbol,ACell> getEnvironment() {
		return chainState.getEnvironment();
	}

	/**
	 * Gets the compiler state
	 * @return CompilerState instance
	 */
	public CompilerState getCompilerState() {
		return compilerState;
	}

	/**
	 * Gets the metadata for the current Account
	 * @return Metadata map
	 */
	public AHashMap<Symbol,AHashMap<ACell,ACell>> getMetadata() {
		return chainState.getMetadata();
	}

	/**
	 * Consumes juice, returning an updated context if sufficient juice remains or an exceptional JUICE error.
	 * @param <R> Result type
	 * @param gulp Amount of jjuice to consume
	 * @return Updated context with juice consumed
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> consumeJuice(long gulp) {
		if (gulp<=0) throw new Error("Juice gulp must be positive!");
		if(!checkJuice(gulp)) return withJuiceError();
		juice=juice-gulp;
		return (Context<R>) this;
		// return new Context<R>(chainState,newJuice,localBindings,(R) result,depth,isExceptional);
	}

	/**
	 * Checks if there is sufficient juice for a given gulp of consumption. Does not alter context in any way.
	 *
	 * @param gulp Amount of juice to be consumed.
	 * @return true if juice is sufficient, false otherwise.
	 */
	public boolean checkJuice(long gulp) {
		return (juice>=gulp);
	}

	/**
	 * Looks up a symbol's value in the current execution context, without any effect on the Context (no juice consumed etc.)
	 *
	 * @param <R> Type of value associated with the given symbol
	 * @param symbol Symbol to look up. May be qualified
	 * @return Context with the result of the lookup (may be an undeclared exception)
	 */
	public <R extends ACell> Context<R> lookup(Symbol symbol) {
		// try lookup in dynamic environment
		return lookupDynamic(symbol);
	}

	/**
	 * Looks up a value in the dynamic environment. Consumes no juice.
	 *
	 * Returns an UNDECLARED exception if the symbol cannot be resolved.
	 *
	 * @param <R> Result type
	 * @param symbol Symbol to look up
	 * @return Updated Context
	 */
	public <R extends ACell> Context<R> lookupDynamic(Symbol symbol) {
		Address address=getAddress();
		return lookupDynamic(address,symbol);
	}

	/**
	 * Looks up a value in the dynamic environment. Consumes no juice.
	 * Returns an UNDECLARED exception if the symbol cannot be resolved.
	 * Returns a NOBODY exception if the specified Account does not exist
	 *
	 * @param <R> Type of value result
	 * @param address Address of account in which to look up value
	 * @param symbol Symbol to look up
	 * @return Updated Context
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> lookupDynamic(Address address, Symbol symbol) {
		AccountStatus as=getAccountStatus(address);
		if (as==null) return withError(ErrorCodes.NOBODY,"No account found for: "+symbol.toString());
		MapEntry<Symbol,ACell> envEntry=lookupDynamicEntry(as,symbol);

		// if not found, return UNDECLARED error
		if (envEntry==null) {
			return withError(ErrorCodes.UNDECLARED,symbol.toString());
		}

		// Result is whatever is defined as the datum value in the environment entry
		ACell result = envEntry.getValue();
		return (Context<R>) withResult(result);
	}

	/**
	 * Looks up Metadata for the given symbol in this context
	 * @param sym Symbol to look up
	 * @return Metadata for given symbol (may be empty) or null if undeclared
	 */
	public AHashMap<ACell,ACell> lookupMeta(Symbol sym) {
		AHashMap<Symbol, ACell> env=getEnvironment();
		if (env.containsKey(sym)) {
			return getMetadata().get(sym,Maps.empty());
		}
		AccountStatus as = getAliasedAccount(env);
		if (as==null) return null;

		env=as.getEnvironment();
		if (env.containsKey(sym)) {
			return as.getMetadata().get(sym,Maps.empty());
		}
		return null;
	}

	/**
	 * Looks up Metadata for the given symbol in this context
	 * @param address Address to use for lookup (may pass null for current environment)
	 * @param sym Symbol to look up
	 * @return Metadata for given symbol (may be empty) or null if undeclared
	 */
	public AHashMap<ACell,ACell> lookupMeta(Address address,Symbol sym) {
		if (address==null) return lookupMeta(sym);
		AccountStatus as=getAccountStatus(address);
		if (as==null) return null;
		AHashMap<Symbol, ACell> env=as.getEnvironment();
		if (env.containsKey(sym)) {
			return as.getMetadata().get(sym,Maps.empty());
		}
		return null;
	}

	/**
	 * Looks up the account the defines a given Symbol
	 * @param sym Symbol to look up
	 * @param address Address to look up in first instance (null for current address).
	 * @return AccountStatus for given symbol (may be empty) or null if undeclared
	 */
	public AccountStatus lookupDefiningAccount(Address address,Symbol sym) {
		AccountStatus as=(address==null)?getAccountStatus():getAccountStatus(address);
		if (as==null) return null;
		AHashMap<Symbol, ACell> env=as.getEnvironment();
		if (env.containsKey(sym)) {
			return as;
		}

		as = getAliasedAccount(env);
		if (as==null) return null;

		env=as.getEnvironment();
		if (env.containsKey(sym)) {
			return as;
		}
		return null;
	}

	/**
	 * Looks up value for the given symbol in this context
	 * @param sym Symbol to look up
	 * @return Value for the given symbol or null if undeclared
	 */
	public ACell lookupValue(Symbol sym) {
		AHashMap<Symbol, ACell> env=getEnvironment();

		// Lookup in current environment first
		MapEntry<Symbol,ACell> me=env.getEntry(sym);
		if (me!=null) {
			return me.getValue();
		}

		AccountStatus as = getAliasedAccount(env);
		if (as==null) return null;
		return as.getEnvironment().get(sym);
	}

	/**
	 * Looks up value for the given symbol in this context
	 * @param address Address to look up in (may be null for current environment)
	 * @param sym Symbol to look up
	 * @return Value for the given symbol or null if undeclared
	 */
	public ACell lookupValue(Address address,Symbol sym) {
		if (address==null) return lookupValue(sym);
		AccountStatus as=getAccountStatus(address);
		if (as==null) return null;
		AHashMap<Symbol, ACell> env=as.getEnvironment();
		return env.get(sym);
	}

	/**
	 * Looks up an environment entry for a specific address without consuming juice.
	 *
	 * @param address Address of Account in which to look up entry
	 * @param sym Symbol to look up
	 * @return Environment entry
	 */
	public MapEntry<Symbol,ACell> lookupDynamicEntry(Address address,Symbol sym) {
		AccountStatus as=getAccountStatus(address);
		if (as==null) return null;
		return lookupDynamicEntry(as,sym);
	}



	private MapEntry<Symbol,ACell> lookupDynamicEntry(AccountStatus as,Symbol sym) {
		// Get environment for Address, or default to initial environment
		AHashMap<Symbol, ACell> env = (as==null)?Core.ENVIRONMENT:as.getEnvironment();


		MapEntry<Symbol,ACell> result=env.getEntry(sym);

		if (result==null) {
			AccountStatus aliasAccount=getAliasedAccount(env);
			result = lookupAliasedEntry(aliasAccount,sym);
		}
		return result;
	}

	private MapEntry<Symbol,ACell> lookupAliasedEntry(AccountStatus as,Symbol sym) {
		if (as==null) return null;
		AHashMap<Symbol, ACell> env = as.getEnvironment();
		return env.getEntry(sym);
	}

	/**
	 * Gets the account status for the current Address
	 *
	 * @return AccountStatus object, or null if not found
	 */
	public AccountStatus getAccountStatus() {
		Address a=getAddress();

		// Possible we don't have an Address (e.g. in a Query)
		if (a==null) return null;

		return chainState.state.getAccount(a);
	}

	/**
	 * Looks up the account for an Symbol alias in the given environment.
	 * @param env
	 * @param path An alias path
	 * @return AccountStatus for the alias, or null if not present
	 */
	private AccountStatus getAliasedAccount(AHashMap<Symbol, ACell> env) {
		// TODO: alternative core accounts
		return getCoreAccount();
	}

	private AccountStatus getCoreAccount() {
		return getState().getAccount(Init.CORE_ADDRESS);
	}

	/**
	 * Gets the holdings map for the current account.
	 * @return Map of holdings, or null if the current account does not exist.
	 */
	public ABlobMap<Address,ACell> getHoldings() {
		AccountStatus as=getAccountStatus(getAddress());
		if (as==null) return null;
		return as.getHoldings();
	}

	/**
	 * Gets the balance for the current Address
	 * @return Balance in Convex Coins
	 */
	public long getBalance() {
		return getBalance(getAddress());
	}

	/**
	 * Gets the balance for the specified Address
	 * @param address
	 * @return BAlance in Convex Coins
	 */
	public long getBalance(Address address) {
		AccountStatus as=getAccountStatus(address);
		if (as==null) return 0L;
		return as.getBalance();
	}

	/**
	 * Gets the caller of the currently executing context.
	 *
	 * Will be null if this context was not called from elsewhere (e.g. is an origin context)
	 * @return Caller of the currently executing context
	 */
	public Address getCaller() {
		return chainState.caller;
	}

	/**
	 * Gets the address of the currently executing Account. May be the current actor, or the address of the
	 * account that executed this transaction if no Actors have been called.
	 *
	 * @return Address of the current account, cannot be null, must be a valid existing account
	 */
	public Address getAddress() {
		return chainState.address;
	}

	/**
	 * Gets the result from this context. Throws an Error if the context return value is exceptional.
	 *
	 * @return Result value from this Context.
	 */
	public T getResult() {
		if (exception!=null) {
			String msg = "Can't get result with exceptional value: "+exception;
			if (exception instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)exception;
				msg=msg+"\n"+ev.getTrace();
			}
			throw new Error(msg);
		}
		return (T) result;
	}

	/**
	 * Gets the resulting value from this context. May be either exceptional or a normal result.
	 * @return Either the normal result, or an AExceptional instance
	 */
	public Object getValue() {
		if (exception!=null) return exception;
		return result;
	}

	/**
	 * Gets the exceptional value from this context. Throws an Error is the context return value is normal.
	 * @return an AExceptional instance
	 */
	public AExceptional getExceptional() {
		if (exception==null) throw new Error("Can't get exceptional value for context with result: "+exception);
		return exception;
	}

	/**
	 * Returns a context updated with the specified result.
	 *
	 * Context may become exceptional depending on the result type.
	 *
	 * @param <R> Result type
	 * @param value Value
	 * @return Context updated with the specified result.
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withResult(ACell value) {
		result=(T)value;
		exception=null;
		return (Context<R>) this;
	}

	/**
	 * Updates this context with a given value, which may either be a normal result or exceptional value
	 * @param <R> Result type
	 * @param value Value
	 * @return Context updated with the specified result value.
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withValue(Object value) {
		if (value instanceof AExceptional) {
			exception=(AExceptional)value;
			result=null;
		} else {
			result = (T)value;
			exception=null;
		}
		return (Context<R>) this;
	}

	public <R extends ACell> Context<R> withResult(long gulp,R value) {
		if (!checkJuice(gulp)) return withJuiceError();
		juice=juice-gulp;

		return withResult(value);
	}

	/**
	 * Returns this context with a JUICE error, consuming all juice.
	 * @param <R> Result type
	 * @return Exceptional Context signalling JUICE error.
	 */
	public <R extends ACell> Context<R> withJuiceError() {
		// set juice to zero. Can't consume more that we have!
		this.juice=0;
		return withError(ErrorCodes.JUICE,"Out of juice!");
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withException(AExceptional exception) {
		//return (Context<R>) new Context<AExceptional>(chainState,juice,localBindings,exception,depth,true);
		this.exception=exception;
		this.result=null;
		return (Context<R>) this;
	}

	public <R extends ACell> Context<R> withException(long gulp,AExceptional value) {
		if (!checkJuice(gulp)) return withJuiceError();
		juice=juice-gulp;
		return withException(value);
		//if ((this.result==value)&&(this.juice==newJuice)) return (Context<R>) this;
		//return (Context<R>) new Context<AExceptional>(chainState,newJuice,localBindings,value,depth,true);
	}

	/**
	 * Updates the environment of this execution context. This changes the environment stored in the
	 * state for the current Address.
	 *
	 * @param newEnvironment
	 * @return Updated Context with the given dynamic environment
	 */
	private Context<T> withEnvironment(AHashMap<Symbol, ACell> newEnvironment)  {
		ChainState cs=chainState.withEnvironment(newEnvironment);
		return withChainState(cs);
	}

	/**
	 * Updates the environment of this execution context. This changes the environment stored in the
	 * state for the current Address.
	 *
	 * @param newEnvironment
	 * @return Updated Context with the given dynamic environment
	 */
	private Context<T> withEnvironment(AHashMap<Symbol, ACell> newEnvironment, AHashMap<Symbol,AHashMap<ACell,ACell>> newMeta)  {
		ChainState cs=chainState.withEnvironment(newEnvironment,newMeta);
		return withChainState(cs);
	}

	private Context<T> withChainState(ChainState newChainState) {
		//if (chainState==newChainState) return this;
		//return create(newChainState,juice,localBindings,result,depth);
		chainState=newChainState;
		return this;
	}

	/**
	 * Executes an Op within this context, returning an updated context.
	 *
	 * @param <R> Return type of the Op
	 * @param op Op to execute
	 * @return Updated Context
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> execute(AOp<R> op) {
		// execute op with adjusted depth
		int savedDepth=getDepth();
		Context<AOp<R>> ctx =this.withDepth(savedDepth+1);
		if (ctx.isExceptional()) return (Context<R>) ctx; // depth error, won't have modified depth

		Context<R> rctx=op.execute(ctx);

		// reset depth after execution.
		rctx=rctx.withDepth(savedDepth);
		return rctx;
	}

	/**
	 * Executes an Op at the top level in a new forked Context. Handles top level halt, recur and return.
	 *
	 * Returning an updated context containing the result or an exceptional error.
	 *
	 * @param <R> Return type of the Op
	 * @param op Op to execute
	 * @return Updated Context
	 */
	public <R extends ACell> Context<R> run(AOp<R> op) {
		// Security: run in fork
		Context<R> ctx=fork().execute(op);

		// must handle state results like halt, rollback etc.
		return handleStateResults(ctx,false);
	}
	
	/**
	 * Executes a form at the top level in a new forked Context. Handles top level halt, recur and return.
	 *
	 * Returning an updated context containing the result or an exceptional error.
	 *
	 * @param <R> Return type of the Op
	 * @param code Code to execute
	 * @return Updated Context
	 */
	public <R extends ACell> Context<R> run(ACell code) {
		Context<R> ctx=fork();
		ctx=ctx.eval(code);

		// must handle state results like halt, rollback etc.
		return handleStateResults(ctx,false);
	}


	/**
	 * Invokes a function within this context, returning an updated context.
	 *
	 * Handles function recur and return values.
	 *
	 * Keeps depth constant upon return.
	 *
	 * @param <R> Return type of the function
	 * @param fn Function to execute
	 * @param args Arguments for function
	 * @return Updated Context
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> invoke(AFn<R> fn, ACell... args) {
		// Note: we don't adjust depth here because execute(...) does it for us in the function body
		Context<R> ctx = fn.invoke((Context<ACell>) this,args);

		if (ctx.isExceptional()) {
			// Need an Object because maybe mutating later
			Object v=ctx.getExceptional();

			// recur as many times as needed
			while (v instanceof ATrampoline) {
				// don't recur if this is the recur function itself

				if (v instanceof RecurValue) {
					if (fn==Core.RECUR) break;
					RecurValue rv = (RecurValue) v;
					ACell[] newArgs = rv.getValues();
					ctx = fn.invoke((Context<ACell>) ctx,newArgs);
					v = ctx.getValue();
				} else if (v instanceof TailcallValue) {
					if (fn==Core.TAILCALL_STAR) break;
					TailcallValue rv=(TailcallValue)v;
					ACell[] newArgs = rv.getValues();

					// redirect function and invoke
					fn = (AFn<R>) rv.getFunction();
					ctx = fn.invoke((Context<ACell>) ctx,newArgs);
					v = ctx.getValue();
				}
			}

			// unwrap return value if necessary
			if ((v instanceof ReturnValue)&&(!(fn==Core.RETURN))) {
				v = ((ReturnValue<T>) v).getValue();

				// unwrap result
				return ctx.withResult((R)v);
			}

			if (v instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)v;
				ev.addTrace("In function: "+RT.str(fn));
			}
		}
		return ctx;
	}

	/**
	 * Execute an op, and bind the result to the given binding form in the lexical environment
	 *
	 * Binding form may be a destructuring form
	 * @param bindingForm Binding form
	 * @param op Op to execute to get binding values
	 *
	 * @param <I> Result type of Context
	 * @return Context with local bindings updated
	 */
	@SuppressWarnings("unchecked")
	public <I extends ACell> Context<I> executeLocalBinding(ACell bindingForm, AOp<I> op) {
		Context<I> ctx=this.execute(op);
		if (ctx.isExceptional()) return ctx;
		return (Context<I>) ctx.updateBindings(bindingForm, ctx.getResult());
	}

	/**
	 * Updates local bindings with a given binding form
	 *
	 * @param <R> Result type of Context
	 * @param bindingForm Binding form
	 * @param args Arguments to bind
	 * @return Non-exceptional Context with local bindings updated, or an exceptional result if bindings fail
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> updateBindings(ACell bindingForm, Object args) {
		// Clear any exceptional status
		Context<R> ctx=this.withValue(null);	

		if (bindingForm instanceof Symbol) {
			Symbol sym=(Symbol)bindingForm;
			if (sym.equals(Symbols.UNDERSCORE)) return ctx;
			// TODO: confirm must be an ACell at this point?
			return withLocalBindings(localBindings.conj((ACell)args));
		} else if (bindingForm instanceof AVector) {
			AVector<ACell> v=(AVector<ACell>)bindingForm;
			long vcount=v.count(); // count of binding form symbols (may include & etc.)

			// Count the arguments, exit with a CAST error if args are not sequential
			Long argCount=RT.count(args);
			if (argCount==null) return ctx.withError(ErrorCodes.CAST, "Trying to destructure an argument that is not a sequential collection");

			boolean foundAmpersand=false;
			for (long i=0; i<vcount; i++) {
				// get datum for syntax element in binding form
				ACell bf=v.get(i);

				if (Symbols.AMPERSAND.equals(bf)) {
					if (foundAmpersand) return ctx.withCompileError("Can't bind two or more ampersands in a single binding vector");

					long nLeft=vcount-i-2; // number of following bindings should be zero in usual usage [... & more]
					if (nLeft<0) return ctx.withCompileError("Can't bind ampersand at end of binding form");

					// bind variadic form at position i+1 to all args except nLeft
					long consumeCount=(argCount-i)-nLeft;
					if (consumeCount<0) return ctx.withArityError("Insufficient arguments to allow variadic binding");
					AVector<ACell> rest=RT.vec(args).slice(i,i+consumeCount); // TODO: cost of this?
					ctx= ctx.updateBindings(v.get(i+1), rest);
					if(ctx.isExceptional()) return ctx;

					// mark ampersand as found, and skip to next binding form (i.e. past the variadic symbol following &)
					foundAmpersand=true;
					i++;
				} else {
					// just a regular binding
					long argIndex=foundAmpersand?(argCount-(vcount-i)):i;
					if (argIndex>=argCount) return ctx.withArityError("Insufficient arguments ("+argCount+") for binding form: "+bindingForm);
					ctx=ctx.updateBindings(bf,RT.nth(args, argIndex));
					if(ctx.isExceptional()) return ctx;
				}
			}

			// at this point, should have consumed all bindings
			if (!foundAmpersand) {
				if (vcount!=argCount) {
					return ctx.withArityError("Expected "+vcount+" arguments but got "+argCount+" for binding form: "+bindingForm);
				}
			}
		} else {
			return ctx.withCompileError("Don't understand binding form of type: "+RT.getType(bindingForm));
		}
		// return
		return ctx;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit)  {
		bb.append("{");
		bb.append(":juice "+juice);
		bb.append(',');
		bb.append(":result ");
		if (!RT.print(bb,result,limit)) return false;
		bb.append(',');
		bb.append(":state ");
		if (!getState().print(bb,limit)) return false;
		bb.append("}");
		return bb.check(limit);
	}

	public AVector<ACell> getLocalBindings() {
		return localBindings;
	}

	/**
	 * Updates this Context with new local bindings. Doesn't affact result state (exceptional or otherwise)
	 * @param <R> Return type of Context
	 * @param newBindings New local bindings map to use.
	 * @return Updated context
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withLocalBindings(AVector<ACell> newBindings) {
		//if (localBindings==newBindings) return (Context<R>) this;
		//return create(chainState,juice,newBindings,(R)result,depth);
		localBindings=newBindings;
		return (Context<R>) this;
	}

	/**
	 * Gets the account status record, or null if not found
	 *
	 * @param address Address of account
	 * @return AccountStatus for the specified address, or null if the account does not exist
	 */
	public AccountStatus getAccountStatus(Address address) {
		return getState().getAccount(address);
	}

	public int getDepth() {
		return depth;
	}

	public Address getOrigin() {
		return chainState.origin;
	}

	/**
	 * Defines a value in the environment of the current address
	 * @param key Symbol of the mapping to create
	 * @param value Value to define
	 * @return Updated context with symbol defined in environment
	 */
	public Context<T> define(Symbol key, ACell value) {
		AHashMap<Symbol, ACell> env = getEnvironment();
		AHashMap<Symbol, ACell> newEnvironment = env.assoc(key, value);

		return withEnvironment(newEnvironment);
	}

	/**
	 * Defines a value in the environment of the current address, updating the metadata
	 *
	 * @param syn Syntax Object to define, containing a Symbol value
	 * @param value Value to set of the given Symbol
	 * @return Updated context with symbol defined in environment
	 */
	public Context<T> defineWithSyntax(Syntax syn, ACell value) {
		Symbol key=syn.getValue();
		AHashMap<Symbol, ACell> env = getEnvironment();
		AHashMap<Symbol, ACell> newEnvironment = env.assoc(key, value);
		AHashMap<Symbol, AHashMap<ACell,ACell>> newMeta = getMetadata().assoc(key, syn.getMeta());

		return withEnvironment(newEnvironment,newMeta);
	}


	/**
	 * Removes a definition mapping in the environment of the current address
	 * @param key Symbol of the environment mapping to remove
	 * @return Updated context with symbol definition removed from the environment, or this context if unchanged
	 */
	public Context<T> undefine(Symbol key) {
		AHashMap<Symbol, ACell> m = getEnvironment();
		AHashMap<Symbol, ACell> newEnvironment = m.dissoc(key);
		AHashMap<Symbol, AHashMap<ACell,ACell>> newMeta = getMetadata().dissoc(key);

		return withEnvironment(newEnvironment,newMeta);
	}

	/**
	 * Expand and compile a form in this Context.
	 *
	 * @param <R> Return type of compiled op
	 * @param form Form to expand and compile
	 * @return Updated Context with compiled Op as result
	 */
	public <R extends ACell> Context<AOp<R>> expandCompile(ACell form) {
		// run compiler with adjusted depth
		int saveDepth=getDepth();
		Context<AOp<R>> rctx =this.withDepth(saveDepth+1);
		if (rctx.isExceptional()) return rctx; // depth error, won't have modified depth

		// EXPAND AND COMPILE
		rctx = Compiler.expandCompile(form, rctx);

		// reset depth after expansion and compilation, unless there is an error
		rctx=rctx.withDepth(saveDepth);

		return rctx;
	}

	/**
	 * Compile a form in this Context. Form must already be fully expanded to a Syntax Object
	 *
	 * @param <R> Return type of compiled op
	 * @param expandedForm Form to compile
	 * @return Updated Context with compiled Op as result
	 */
	public <R extends ACell> Context<AOp<R>> compile(ACell expandedForm) {
		// Save an adjust depth
		int saveDepth=getDepth();
		Context<AOp<R>> rctx =this.withDepth(saveDepth+1);
		if (rctx.isExceptional()) return rctx; // depth error

		// Save Compiler state
		CompilerState savedCompilerState=getCompilerState();

		// COMPILE
		rctx = Compiler.compile(expandedForm, rctx);

		if (rctx.isExceptional()) {
			AExceptional ex=rctx.getExceptional();
			if (ex instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)ex;
				// TODO: SECURITY: DoS limits
				//String msg = "Compiling: Syntax Object with datum of type "+Utils.getClassName(expandedForm);
				String msg = "Compiling:"+ expandedForm;
				//String msg = "Compiling: "+expandedForm;
				ev.addTrace(msg);
			}
		}

		// restore depth and return
		rctx=rctx.withDepth(saveDepth);
		rctx=rctx.withCompilerState(savedCompilerState);
		return rctx;
	}

	/**
	 * Executes a form in the current context.
	 * 
	 * Ops are executed directly.
	 * Other forms will be expanded and compiled before execution, unless *lang* is set, in which case they will
	 * be executed via the *lang* function.
	 *
	 * @param <R> Return type of evaluation
	 * @param form Form to evaluate
	 * @return Context containing the result of evaluating the specified form
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> eval(ACell form) {
		Context<AOp<R>> ctx=(Context<AOp<R>>) this;
		AOp<R> op;
	
		if (form instanceof AOp) {
			op=(AOp<R>)form;
		} else {
			AFn<R> lang=RT.ensureFunction(lookupValue(Symbols.STAR_LANG));
			if (lang!=null) {
				// Execute *lang* function, but increment depth just in case
				int saveDepth=ctx.getDepth();
				ctx=ctx.withDepth(saveDepth+1);
				if (ctx.isExceptional()) return (Context<R>) ctx;
				Context<R> rctx = ctx.invoke(lang,form);
				return rctx.withDepth(saveDepth);
			} else {
				ctx=expandCompile(form);
				if (ctx.isExceptional()) return (Context<R>) ctx;
				op=ctx.getResult();
				ctx=ctx.withResult(null); // clear result for execution
			}
		}
		AVector<ACell> savedBindings = ctx.getLocalBindings();
		ctx=ctx.withLocalBindings(Vectors.empty());
		Context<R> rctx= ctx.execute(op);
		return rctx.withLocalBindings(savedBindings);
	}

	/**
	 * Evaluates a form as another Address.
	 *
	 * Causes TRUST error if the Address is not controlled by the current address.
	 * @param <R> Result type
	 * @param address Address of Account in which to evaluate
	 * @param form Form to evaluate
	 * @return Updated Context
	 */
	public <R extends ACell> Context<R> evalAs(Address address, ACell form) {
		Address caller=getAddress();
		if (caller.equals(address)) return eval(form);
		AccountStatus as=this.getAccountStatus(address);
		if (as==null) return withError(ErrorCodes.NOBODY,"Address does not exist: "+address);

		Address controller=as.getController();
		if (controller==null) return withError(ErrorCodes.TRUST,"Cannot control address with nil controller set: "+address);

		boolean canControl=false;

		// Run eval in a forked context
		Context<R> ctx=this.fork();
		if (controller.equals(getAddress())) {
			canControl=true;
		} else {
			AccountStatus controlAccount=this.getAccountStatus(controller);
			if (controlAccount==null) return ctx.withError(ErrorCodes.TRUST,"Cannot control address because controller does not exist: "+controller);
			if (controlAccount.isActor()) {
				// (call target amount (receive-coin source amount nil))
				ctx=ctx.actorCall(controller,DEFAULT_OFFER,Symbols.CHECK_TRUSTED_Q,caller,null,address);
				if (ctx.isExceptional()) return ctx;
				canControl=RT.bool(ctx.getResult());
			}
		}

		if (!canControl) return ctx.withError(ErrorCodes.TRUST,"Cannot control address: "+address);

		// SECURITY: eval with a context switch
		final Context<R> exContext=Context.create(getState(), juice, EMPTY_BINDINGS, null, depth+1, getOrigin(),caller, address,0,log,null);

		final Context<R> rContext=exContext.eval(form);
		// SECURITY: must handle results as if returning from an actor call
		return handleStateResults(rContext,false);
	}

	/**
	 * Executes code as if run in the current account, but always discarding state changes.
	 * @param <R> Result type
	 * @param form Code to execute.
	 * @return Context updated with only query result and juice consumed
	 */
	public <R extends ACell> Context<R> query(ACell form) {
		Context<R> ctx=this.fork();

		// adjust depth. May be exceptional if depth limit exceeded
		ctx=ctx.withDepth(depth+1);

		// eval in current account if everything OK
		if (!ctx.isExceptional()) {
		   ctx=ctx.eval(form);
		}

		// handle results including state rollback. Will propagate any errors.
		return handleQueryResult(ctx);
	}

	/**
	 * Executes code as if run in the specified account, but always discarding state changes.
	 * @param <R> Result type
	 * @param address Address of Account in which to execute the query
	 * @param form Code to execute.
	 * @return Context updated with only query result and juice consumed
	 */
	public <R extends ACell> Context<R> queryAs(Address address, ACell form) {
		// chainstate with the target address as origin.
		ChainState cs=ChainState.create(getState(),address,null,address,DEFAULT_OFFER);
		Context<R> ctx=Context.create(cs, juice, EMPTY_BINDINGS, null, depth,log,null);
		ctx=ctx.evalAs(address, form);
		return handleQueryResult(ctx);
	}

	/**
	 * Just take result and juice from query. Log and state not kept.
	 * @param <R>
	 * @param ctx
	 * @return
	 */
	protected <R extends ACell> Context<R> handleQueryResult(Context<R> ctx) {
		this.juice=ctx.getJuice();
		return this.withValue(ctx.result);
	}

	/**
	 * Compiles a sequence of forms in the current context.
	 * Returns a vector of ops in the updated Context.
	 *
	 * Maintains depth.
	 *
	 * @param <R> Return type of compiled op.
	 * @param forms A sequence of forms to compile
	 * @return Updated context with vector of compiled forms
	 */
	public <R extends ACell> Context<AVector<AOp<R>>> compileAll(ASequence<ACell> forms) {
		Context<AVector<AOp<R>>> rctx = Compiler.compileAll(forms, this);
		return rctx;
	}

//	public <R> Context<R> adjustDepth(int delta) {
//		int newDepth=Math.addExact(depth,delta);
//		return withDepth(newDepth);
//	}

	/**
	 * Changes the depth of this context. Returns exceptional result if depth limit exceeded.
	 * @param <R> Result type
	 * @param newDepth New depth value
	 * @return Updated context with new depth set
	 */
	@SuppressWarnings("unchecked")
	<R extends ACell> Context<R> withDepth(int newDepth) {
		if (newDepth==depth) return (Context<R>) this;
		if ((newDepth<0)||(newDepth>Constants.MAX_DEPTH)) return withError(ErrorCodes.DEPTH,"Invalid depth: "+newDepth);
		depth=newDepth;
		return (Context<R>)this;
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withJuice(long newJuice) {
		juice=newJuice;
		return (Context<R>) this;
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withCompilerState(CompilerState comp) {
		compilerState=comp;
		return (Context<R>) this;
	}

	/**
	 * Tests if this Context holds an exceptional result.
	 *
	 * Ops should cancel and return exceptional results unchanged, unless they can handle them.
	 * @return true if context has an exceptional value, false otherwise
	 */
	public boolean isExceptional() {
		return exception!=null;
	}
	
	/**
	 * Tests if an Address is valid, i.e. refers to an existing Account
	 * 
	 * @param address Address to check. May be null
	 * @return true if Account exists, false otherwise
	 */
	public boolean isValidAccount(Address address) {
		if (address==null) return false;
		return getAccountStatus(address)!=null;
	}

	/**
	 * Tests if this Context's current status contains an Error. Errors are an uncatchable subset of Exceptions.
	 *
	 * @return true if context has an Error value, false otherwise
	 */
	public boolean isError() {
		return (exception!=null)&&(exception instanceof ErrorValue);
	}

	/**
	 * Transfers funds from the current address to the target.
	 *
	 * Uses no juice
	 *
	 * @param target Target Address, will be created if does not already exist.
	 * @param amount Amount to transfer, must be between 0 and Amount.MAX_VALUE inclusive
	 * @return Context with sent amount if the transaction succeeds, or an exceptional value if the transfer fails
	 */
	public Context<CVMLong> transfer(Address target, long amount) {
		if (amount<0) return withError(ErrorCodes.ARGUMENT,"Can't transfer a negative amount");
		if (amount>Constants.MAX_SUPPLY) return withError(ErrorCodes.ARGUMENT,"Can't transfer an amount beyond maximum limit");

		AVector<AccountStatus> accounts=getState().getAccounts();

		Address source=getAddress();
		long sourceIndex=source.longValue();
		AccountStatus sourceAccount=accounts.get(sourceIndex);

		long currentBalance=sourceAccount.getBalance();
		if (currentBalance<amount) {
			return this.withFundsError(Errors.insufficientFunds(source,amount));
		}

		long newSourceBalance=currentBalance-amount;
		AccountStatus newSourceAccount=sourceAccount.withBalance(newSourceBalance);
		accounts=accounts.assoc(sourceIndex, newSourceAccount);

		// new target account (note: could be source account, so we get from latest accounts)
		long targetIndex=target.longValue();
		if (targetIndex>=accounts.count()) {
			return this.withError(ErrorCodes.NOBODY,"Target account for transfer "+target+" does not exist");
		}
		AccountStatus targetAccount=accounts.get(targetIndex);

		if (targetAccount.isActor()) {
			// (call target amount (receive-coin source amount nil))
			// SECURITY: actorCall must do fork to preserve this
			Context<CVMLong> actx=this.fork();
			actx=actorCall(target,amount,Symbols.RECEIVE_COIN,source,CVMLong.create(amount),null);
			if (actx.isExceptional()) return actx;

			Long sent=currentBalance-actx.getBalance(source);
			return actx.withResult(CVMLong.create(sent));
		} else {
			// must be a user account
			long oldTargetBalance=targetAccount.getBalance();
			long newTargetBalance=oldTargetBalance+amount;
			AccountStatus newTargetAccount=targetAccount.withBalance(newTargetBalance);
			accounts=accounts.assoc(targetIndex, newTargetAccount);

			// SECURITY: new context with updated accounts
			Context<CVMLong> result=withChainState(chainState.withAccounts(accounts)).withResult(CVMLong.create(amount));

			return result;
		}

	}

	/**
	 * Transfers memory allowance from the current address to the target.
	 *
	 * Uses no juice
	 *
	 * @param target Target Address, must already exist
	 * @param amountToSend Amount of memory to transfer, must be between 0 and Amount.MAX_VALUE inclusive
	 * @return Context with a null result if the transaction succeeds, or an exceptional value if the transfer fails
	 */
	public Context<CVMLong> transferMemoryAllowance(Address target, CVMLong amountToSend) {
		long amount=amountToSend.longValue();
		if (amount<0) return withError(ErrorCodes.ARGUMENT,"Can't transfer a negative allowance amount");
		if (amount>Constants.MAX_SUPPLY) return withError(ErrorCodes.ARGUMENT,"Can't transfer an allowance amount beyond maximum limit");

		AVector<AccountStatus> accounts=getState().getAccounts();

		Address source=getAddress();
		long sourceIndex=source.longValue();
		AccountStatus sourceAccount=accounts.get(sourceIndex);

		long currentBalance=sourceAccount.getMemory();
		if (currentBalance<amount) {
			return withError(ErrorCodes.MEMORY,"Insufficient memory allowance for transfer");
		}

		long newSourceBalance=currentBalance-amount;
		AccountStatus newSourceAccount=sourceAccount.withMemory(newSourceBalance);
		accounts=accounts.assoc(sourceIndex, newSourceAccount);

		// new target account (note: could be source account, so we get from latest accounts)
		long targetIndex=target.longValue();
		if (targetIndex>=accounts.count()) {
			return withError(ErrorCodes.NOBODY,"Cannot transfer memory allowance to non-existent account: "+target);
		}
		AccountStatus targetAccount=accounts.get(targetIndex);

		long newTargetBalance=targetAccount.getMemory()+amount;
		AccountStatus newTargetAccount=targetAccount.withMemory(newTargetBalance);
		accounts=accounts.assoc(targetIndex, newTargetAccount);

		// SECURITY: new context with updated accounts
		Context<CVMLong> result=withChainState(chainState.withAccounts(accounts)).withResult(amountToSend);
		return result;
	}

	/**
	 * Sets the memory allowance for the current account, buying / selling from the pool as necessary to
	 * ensure the correct final allowance
	 * @param allowance New memory allowance
	 * @return Context indicating the price paid for the allowance change (may be zero or negative for refund)
	 */
	public Context<CVMLong> setMemory(long allowance) {
		AVector<AccountStatus> accounts=getState().getAccounts();
		if (allowance<0) return withError(ErrorCodes.ARGUMENT,"Can't transfer a negative allowance amount");
		if (allowance>Constants.MAX_SUPPLY) return withError(ErrorCodes.ARGUMENT,"Can't transfer an allowance amount beyond maximum limit");

		Address source=getAddress();
		long sourceIndex=source.longValue();
		AccountStatus sourceAccount=accounts.get(sourceIndex);

		long current=sourceAccount.getMemory();
		long balance=sourceAccount.getBalance();
		long delta=allowance-current;
		if (delta==0L) return this.withResult(CVMLong.ZERO);

		AccountStatus pool=getState().getAccount(Init.MEMORY_EXCHANGE_ADDRESS);

		try {
			long poolAllowance=pool.getMemory();
			long poolBalance=pool.getBalance();
			long price = Economics.swapPrice(delta, poolAllowance,poolBalance);
			if (price>balance) {
				return withError(ErrorCodes.FUNDS,"Cannot afford allowance, would cost: "+price);
			}
			sourceAccount=sourceAccount.withBalances(balance-price, allowance);
			pool=pool.withBalances(poolBalance+price, poolAllowance-delta);

			// Update accounts
			AVector<AccountStatus> newAccounts=accounts.assoc(sourceIndex, sourceAccount);
			newAccounts=newAccounts.assoc(Init.MEMORY_EXCHANGE_ADDRESS.longValue(),pool);

			return withChainState(chainState.withAccounts(newAccounts)).withResult(null);
		} catch (IllegalArgumentException e) {
			return withError(ErrorCodes.FUNDS,"Cannot trade allowance: "+e.getMessage());
		}
	}

	/**
	 * Accepts offered funds for the given address.
	 *
	 * STATE error if offered amount is insufficient. ARGUMENT error if acceptance is negative.
	 *
	 * @param <R> Type of result
	 * @param amount Amount to accept
	 * @return Updated context, with long amount accepted as result
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> acceptFunds(long amount) {
		if (amount<0L) return this.withError(ErrorCodes.ARGUMENT,"Negative accept argument");
		if (amount==0L) return this.withResult(Juice.ACCEPT, (R)CVMLong.ZERO);

		long offer=getOffer();
		if (amount>offer) return this.withError(ErrorCodes.STATE,"Insufficient offered funds");

		State state=getState();
		Address addr=getAddress();
		long balance=state.getBalance(addr);
		state=state.withBalance(addr,balance+amount);

		// need to update both state and offer
		ChainState cs=chainState.withStateOffer(state,offer-amount);
		Context<T> ctx=this.withChainState(cs);

		return (Context<R>) ctx.withResult(Juice.ACCEPT, CVMLong.create(amount));
	}

	/**
	 * Executes a call to an Actor. Utility function which convert a java String function name
	 *
	 * @param <R> Return type of Actor call
	 * @param target Target Actor address
	 * @param offer Amount of Convex Coins to offer in Actor call
	 * @param functionName Symbol of function name defined by Actor
	 * @param args Arguments to Actor function invocation
	 * @return Context with result of Actor call (may be exceptional)
	 */
	public <R extends ACell> Context<R> actorCall(Address target, long offer, String functionName, ACell... args) {
		return actorCall(target,offer,Symbol.create(functionName),args);
	}

	/**
	 * Executes a call to an Actor.
	 *
	 * @param <R> Return type of Actor call
	 * @param target Target Actor address
	 * @param offer Amount of Convex Coins to offer in Actor call
	 * @param functionName Symbol of function name defined by Actor
	 * @param args Arguments to Actor function invocation
	 * @return Context with result of Actor call (may be exceptional)
	 */
	public <R extends ACell> Context<R> actorCall(Address target, long offer, ACell functionName, ACell... args) {
		// SECURITY: set up state for actor call
		State state=getState();
		Symbol sym=RT.ensureSymbol(functionName);
		AccountStatus as=state.getAccount(target);
		if (as==null) return this.withError(ErrorCodes.NOBODY,"Actor Account does not exist: "+target);

		// Handling for non-zero offers.
		// SECURITY: Subtract offer from balance first so we don't have double-spend issues!
		if (offer>0L) {
			Address senderAddress=getAddress();
			AccountStatus cas=state.getAccount(senderAddress);
			long balance=cas.getBalance();
			if (balance<offer) {
				return this.withFundsError("Insufficient funds for offer: "+offer +" trying to call Actor "+target+ " function ("+sym+" ...)");
			}
			cas=cas.withBalance(balance-offer);
			state=state.putAccount(senderAddress, cas);
		} else if (offer<0) {
			return this.withError(ErrorCodes.ARGUMENT, "Cannot make negative offer in Actor call: "+offer);
		}

		AFn<R> fn = as.getCallableFunction(sym);

		if (fn == null) {
			return this.withError(ErrorCodes.STATE, "Value defined in account " + target + " is not a callable function: " + sym);
		}

		// Ensure we create a forked Context for the Actor call
		final Context<R> exContext=forkActorCall(state, target, offer);

		// INVOKE ACTOR FUNCTION
		final Context<R> rctx=exContext.invoke(fn,args);

		ErrorValue ev=rctx.getError();
		if (ev!=null) {
			ev.addTrace("Calling Actor "+target+" with function ("+sym+" ...)");
		}

		// SECURITY: must handle state transitions in results correctly
		// calling handleStateReturns on 'this' to ensure original values are restored
		return handleStateResults(rctx,false);
	}

	/**
	 * Create new forked Context for execution of Actor call.
	 * SECURITY: Increments depth, will be restored in handleStateResults
	 * SECURITY: Must change address to the target Actor address.
	 * SECURITY: Must change caller to current address.
	 * @param <R>
	 * @param state for forked context.
	 * @param target Target actor call address, will become new *address* for context
	 * @param offer Offer amount for actor call. Must have been pre-subtracted from caller account.
	 * @return
	 */
	private <R extends ACell> Context<R> forkActorCall(State state, Address target, long offer) {
		return Context.create(state, juice, EMPTY_BINDINGS, (R)null, depth+1, getOrigin(),getAddress(), target,offer, log,null);
	}

	/**
	 * Handle results at the end of an execution boundary (actor call, transaction etc.)
	 * @param <R>
	 * @param returnContext
	 * @param rollback
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private <R extends ACell> Context<R> handleStateResults(Context<R> returnContext, boolean rollback) {
		/** Return value */
		Object rv;
		if (returnContext.isExceptional()) {
			// SECURITY: need to handle exceptional states correctly
			AExceptional ex=returnContext.getExceptional();
			if (ex instanceof RollbackValue) {
				// roll back state to before Actor call
				// Note: this will also refund unused offer.
				rollback=true;
				rv=((RollbackValue<R>)ex).getValue();
			} else if (ex instanceof HaltValue) {
				rv=((HaltValue<R>)ex).getValue();
			} else if (ex instanceof ErrorValue) {
				// OK to pass through error, but need to roll back state changes
				rollback=true;
				rv=ex;
			} else if (ex instanceof ReturnValue) {
				// Normally doesn't happen (invoke catches this)
				// but might in a user transaction. Treat as a Halt.
				rv=((ReturnValue<R>)ex).getValue();
			} else {
				rollback=true;
				String msg;
				if (ex instanceof ATrampoline) {
					msg="attempt to recur or tail call outside of a function body";
				} if (ex instanceof Reduced) {
					msg="reduced used outside of a reduce operation";
				} else {
					msg="Unhandled Exception with Code:"+ex.getCode();
				}
				rv=ErrorValue.create(ErrorCodes.EXCEPTION, msg);
			}
		} else {
			rv=returnContext.getResult();
		}

		final Address address=getAddress(); // address we are returning to
		State returnState;

		if (rollback) {
			returnState=this.getState();
		} else {
			// take state from the returning context
			returnState=returnContext.getState();

			// Take log from returning context
			log=returnContext.getLog();

			// Refund offer
			// Not necessary if rolling back to initial context before offer was subtracted
			long refund=returnContext.getOffer();
			if (refund>0) {
				// we need to refund caller
				AccountStatus cas=returnState.getAccount(address);
				long balance=cas.getBalance();
				cas=cas.withBalance(balance+refund);
				returnState=returnState.putAccount(address, cas);
			}
		}
		// Rebuild context for the current execution
		// SECURITY: must restore origin,depth,caller,address,local bindings, offer

		Context<R> result=this.withState(returnState);
		result.juice=returnContext.juice;
		result=this.withValue(rv);
		return result;
	}

	/**
	 * Deploys an Actor in this context.
	 *
	 * Argument argument must be an Actor generation code, which will be evaluated in the new Actor account
	 * to initialise the Actor.
	 *
	 * Result will contain the new Actor address if successful, an exception otherwise.
	 *
	 * @param code Actor initialisation code
	 * @return Updated Context with Actor deployed, or an exceptional result
	 */
	public Context<Address> deployActor(ACell code) {
		final State initialState=getState();

		// deploy initial contract state to next address
		Address address=initialState.nextAddress();
		State stateSetup=initialState.addActor();

		// Deployment execution context with forked context and incremented depth
		final Context<Address> deployContext=Context.create(stateSetup, juice, EMPTY_BINDINGS, null, depth+1, getOrigin(),getAddress(), address,DEFAULT_OFFER,log,null);
		final Context<Address> rctx=deployContext.eval(code);

		Context<Address> result=this.handleStateResults(rctx,false);
		if (result.isExceptional()) return result;

		return result.withResult(Juice.DEPLOY_CONTRACT,address);
	}

	/**
	 * Create a new Account with a given AccountKey (may be null for actors etc.)
	 * @param key New Account Key
	 * @return Updated context with new Account added
	 */
	public Context<Address> createAccount(AccountKey key) {
		final State initialState=getState();
		Address address=initialState.nextAddress();
		AVector<AccountStatus> accounts=initialState.getAccounts();
		AccountStatus as=AccountStatus.create(0L, key);
		accounts=accounts.conj(as);
		final State newState=initialState.withAccounts(accounts);
		Context<Address> rctx=this.withState(newState);
		return rctx.withResult(address);
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withError(Keyword error) {
		return (Context<R>) withError(ErrorValue.create(error));
	}

	public <R extends ACell> Context<R> withError(Keyword errorCode,String message) {
		return withError(ErrorValue.create(errorCode,Strings.create(message)));
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> withError(ErrorValue error) {
		error.addLog(log);
		error.setAddress(getAddress());
		return (Context<R>) withException(error);
	}


	public <R extends ACell> Context<R> withArityError(String message) {
		return withError(ErrorCodes.ARITY,message);
	}

	public <R extends ACell> Context<R> withCompileError(String message) {
		return withError(ErrorCodes.COMPILE,message);
	}

	public <R extends ACell> Context<R> withBoundsError(long index) {
		return withError(ErrorCodes.BOUNDS,"Index: "+index);
	}

	public <R extends ACell> Context<R> withCastError(int argIndex, AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert argument at position "+(argIndex+1)+" to type "+klass);
	}

	public <R extends ACell> Context<R> withCastError(int argIndex, ACell[] args, AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert argument at position "+(argIndex+1)+" (with type "+RT.getType(args[argIndex])+ ") to type "+klass);
	}

	public <R extends ACell> Context<R> withCastError(ACell a, AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert value of type "+RT.getType(a)+ " to type "+klass);
	}

	public <R extends ACell> Context<R> withCastError(AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert value(s) to type "+klass);
	}

	public <R extends ACell> Context<R> withCastError(ACell a, String message) {
		return withError(ErrorCodes.CAST,message);
	}

	/**
	 * Gets the error code of this context's return value
	 *
	 * @return The ErrorType of the current exceptional value, or null if there is no error.
	 */
	public ACell getErrorCode() {
		if (exception!=null) {
			return exception.getCode();
		}
		return null;
	}

	/**
	 * Gets the Error from this Context, or null if not an Error
	 *
	 * @return The ErrorType of the current exceptional value, or null if there is no error.
	 */
	public ErrorValue getError() {
		if (exception instanceof ErrorValue) {
			return (ErrorValue)exception;
		}
		return null;
	}

	public <R extends ACell> Context<R> withAssertError(String message) {
		return withError(ErrorCodes.ASSERT,message);
	}

	public <R extends ACell> Context<R> withFundsError(String message) {
		return withError(ErrorCodes.FUNDS,message);
	}

	public <R extends ACell> Context<R> withArgumentError(String message) {
		return withError(ErrorCodes.ARGUMENT,message);
	}

	/**
	 * Gets the current timestamp for this context. The timestamp is the greatest timestamp
	 * of all blocks in consensus (including the currently executing block).
	 *
	 * @return Timestamp in milliseconds since UNIX epoch
	 */
	public CVMLong getTimeStamp() {
		return getState().getTimeStamp();
	}

	/**
	 * Schedules an operation for the specified future timestamp.
	 * Handles integrity checks and schedule juice.
	 *
	 * @param time Timestamp at which to schedule the op.
	 * @param op Operation to schedule.
	 * @return Updated context, with scheduled time as the result
	 */
	public Context<CVMLong> schedule(long time, AOp<ACell> op) {
		// check vs current timestamp
		long timestamp=getTimeStamp().longValue();
		if (timestamp<0L) return withError(ErrorCodes.ARGUMENT);
		if (time<timestamp) time=timestamp;

		long juice=(time-timestamp)/Juice.SCHEDULE_MILLIS_PER_JUICE_UNIT;
		if (this.juice<juice) return withJuiceError();

		State s=getState().scheduleOp(time,getAddress(),op);
		Context<?> ctx=this.withChainState(chainState.withState(s));

		return ctx.withResult(juice,CVMLong.create(time));
	}

	/**
	 * Sets the delegated stake on a specified peer to the specified level.
	 * May set to zero to remove stake. Stake will be capped by current balance.
	 *
	 * @param peerKey Peer Account key on which to stake
	 * @param newStake Amount to stake
	 * @return Context with amount of coins transferred to Peer as result (may be negative if stake withdrawn)
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> setDelegatedStake(AccountKey peerKey, long newStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(peerKey);
		if (ps==null) return withError(ErrorCodes.STATE,"Peer does not exist for account key: "+peerKey);
		if (newStake<0) return this.withArgumentError("Cannot set a negative stake");
		if (newStake>Constants.MAX_SUPPLY) return this.withArgumentError("Target stake out of valid Amount range");

		Address myAddress=getAddress();
		long balance=getBalance(myAddress);
		long currentStake=ps.getDelegatedStake(myAddress);
		long delta=newStake-currentStake;

		if (delta==0) return (Context<R>) this; // no change
		
		// need to check sufficient balance if increasing stake
		if (delta>balance) return this.withFundsError("Insufficient balance ("+balance+") to increase Delegated Stake to "+newStake);

		// Final updates. Hopefully everything balances. SECURITY: test this. A lot.
		PeerStatus updatedPeer=ps.withDelegatedStake(myAddress, newStake);
		s=s.withBalance(myAddress, balance-delta); // adjust own balance
		s=s.withPeer(peerKey, updatedPeer); // adjust peer
		return withState(s).withResult(CVMLong.create(delta));
	}
	
	/**
	 * Sets the stake for a given Peer, transferring coins from the current address.
	 * @param peerKey Peer Account Key for which to update Stake
	 * @param newStake New stake for Peer
	 * @return Updated Context
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> setPeerStake(AccountKey peerKey, long newStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(peerKey);
		if (ps==null) return withError(ErrorCodes.STATE,"Peer does not exist for account key: "+peerKey);
		if (newStake<0) return this.withArgumentError("Cannot set a negative stake");
		if (newStake>Constants.MAX_SUPPLY) return this.withArgumentError("Target stake out of valid Amount range");
	
		Address myAddress=getAddress();
		if (!ps.getController().equals(myAddress)) return withError(ErrorCodes.STATE,"Current address "+myAddress+" is not the controller of this peer account");
		
		long balance=getBalance(myAddress);
		long currentStake=ps.getPeerStake();
		long delta=newStake-currentStake;
		
		if (delta==0) return (Context<R>) this; // no change
		
		// need to check sufficient balance if increasing stake
		if (delta>balance) return this.withFundsError("Insufficient balance ("+balance+") to increase Peer Stake to "+newStake);

		// Final updates assuming everything OK. Hopefully everything balances. SECURITY: test this. A lot.
		PeerStatus updatedPeer=ps.withPeerStake(newStake);
		s=s.withBalance(myAddress, balance-delta); // adjust own balance
		s=s.withPeer(peerKey, updatedPeer); // adjust peer

		return withState(s).withResult(CVMLong.create(delta));
	}


	/**
	 * Creates a new peer with the specified stake.
	 * The accountKey must not be in the list of peers.
	 * The accountKey must be assigend to the current transaction address
	 * Stake must be greater than 0.
	 * Stake must be less than to the account balance.
	 *
	 * @param accountKey Peer Account key to create the PeerStatus
	 * @param initialStake Initial stake amount
	 * @return Context with final take set
	 */
	public <R extends ACell> Context<R> createPeer(AccountKey accountKey, long initialStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(accountKey);
		if (ps!=null) return withError(ErrorCodes.STATE,"Peer already exists for this account key: "+accountKey.toChecksumHex());
		if (initialStake<0) return this.withArgumentError("Cannot set a negative stake");
		if (initialStake == 0) return this.withArgumentError("Cannot create a peer with zero stake");
		if (initialStake>Constants.MAX_SUPPLY) return this.withArgumentError("Target stake out of valid Amount range");

		Address myAddress=getAddress();

		// TODO: SECURITY fix
		// AccountStatus as=getAccountStatus(myAddress);
		// if (!as.getAccountKey().equals(accountKey)) return this.withArgumentError("Cannot create a peer with a different account-key");

		long balance=getBalance(myAddress);
		if (initialStake>balance) return this.withFundsError("Insufficient balance ("+balance+") to assign an initial stake of "+initialStake);

		PeerStatus newPeerStatus = PeerStatus.create(myAddress, initialStake);

		// Final updates. Hopefully everything balances. SECURITY: test this. A lot.
		s=s.withBalance(myAddress, balance-initialStake); // adjust own balance
		s=s.withPeer(accountKey, newPeerStatus); // add peer
		return withState(s);
	}

	/**
	 * Sets peer data.
	 * 
	 * @param peerKey Peer to set data for
	 * @param data Map of data to set for the peer
	 * @return Context with final peer data set
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> setPeerData(AccountKey peerKey, AMap<ACell, ACell> data) {
		State s=getState();

		// get the callers account and account status
		Address address = getAddress();
		AccountStatus as = getAccountStatus(address);

		AccountKey ak = as.getAccountKey();
		if (ak == null) return withError(ErrorCodes.STATE,"The account signing this transaction must have a public key");
		PeerStatus ps=s.getPeer(ak);
		if (ps==null) return withError(ErrorCodes.STATE,"Peer does not exist for this account and account key: "+ak.toChecksumHex());
		if (!ps.getController().equals(address)) return withError(ErrorCodes.STATE,"Current address "+address+" is not the controller of this peer account");

		Hash lastStateHash = s.getHash();
		// at the moment only :url is used in the data map
		for (ACell key: data.keySet()) {
			if (Keywords.URL.equals((Keyword) key)) {
				AString url = (AString) data.get(Keywords.URL);
				PeerStatus updatedPeer=ps.withHostname(url);
				s=s.withPeer(ak, updatedPeer); // adjust peer
			}
			else {
				return withArityError("invalid key name " + key.toString());
			}
		}
		// if no change just return the current context
		if (lastStateHash.equals(s.getHash())){
			return (Context<R>) this;
		}
		return withState(s);
	}
	


	/**
	 * Sets the holding for a specified target account. Returns NOBODY exception if account does not exist.
	 * @param targetAddress Account address at which to set the holding
	 * @param value Value to set for the holding.
	 * @return Updated context
	 */
	public Context<T> setHolding(Address targetAddress, ACell value) {
		AccountStatus as=getAccountStatus(targetAddress);
		if (as==null) return withError(ErrorCodes.NOBODY,"Can't set set holding for non-existent account "+targetAddress);
		as=as.withHolding(getAddress(), value);
		return withAccountStatus(targetAddress,as);
	}

	/**
	 * Sets the controller for the current Account
	 * @param <R> Result type
	 * @param address New controller Address
	 * @return Context with current Account controller set
	 */
	public <R extends ACell> Context<R> setController(Address address) {
		AccountStatus as=getAccountStatus();
		as=as.withController(address);
		return withAccountStatus(getAddress(),as);

	}

	/**
	 * Sets the public key for the current account
	 * @param <R> Result type
	 * @param publicKey New Account Public Key
	 * @return Context with current Account Key set
	 */
	public <R extends ACell> Context<R> setAccountKey(AccountKey publicKey) {
		AccountStatus as=getAccountStatus();
		as=as.withAccountKey(publicKey);
		return withAccountStatus(getAddress(),as);
	}

	protected <R extends ACell> Context<R> withAccountStatus(Address target, AccountStatus accountStatus) {
		return withState(getState().putAccount(target, accountStatus));
	}

	/**
	 * Switches the context to a new address, creating a new execution context. Suitable for testing.
	 * @param <R> Result type
	 * @param newAddress New Address to use.
	 * @return Result type of new Context
	 */
	public <R extends ACell> Context<R> forkWithAddress(Address newAddress) {
		return createFake(getState(),newAddress);
	}

	/**
	 * Forks this context, creating a new copy of all local state
	 * @param <R> Result type of new Context
	 * @return A new forked Context
	 */
	public <R extends ACell> Context<R> fork() {
		return new Context<R>(chainState, juice, localBindings, null,depth, null,log,compilerState);
	}

	@Override
	public Blob createEncoding() {
		throw new TODOException();
	}

	/**
	 * Appends a log entry for the current address.
	 * @param values Values to log
	 * @return Updated Context
	 */
	public Context<T> appendLog(AVector<ACell> values) {
		Address addr=getAddress();
		AVector<AVector<ACell>> log=this.log;
		if (log==null) {
			log=Vectors.empty();
		}
		AVector<ACell> entry = Vectors.of(addr,values);
		log=log.conj(entry);

		this.log=log;
		return this;
	}

	/**
	 * Gets the log map for the current context.
	 *
	 * @return BlobMap of addresses to log entries created in the course of current execution context.
	 */
	public AVector<AVector<ACell>> getLog() {
		if (log==null) return Vectors.empty();
		return log;
	}

	public Context<?> lookupCNS(String name) {
		Context<?> ctx=this.fork();
		ctx=this.actorCall(Init.REGISTRY_ADDRESS, 0, Symbols.CNS_RESOLVE, Symbol.create(name));

		return ctx;
	}

	/**
	 * Expands a form with the default *initial-expander*
	 * @param form Form to expand
	 * @return Syntax Object resulting from expansion.
	 */
	public Context<ACell> expand(ACell form) {
		return expand(Core.INITIAL_EXPANDER, form, Core.INITIAL_EXPANDER);
	}

	@SuppressWarnings("unchecked")
	public <R extends ACell> Context<R> expand(AFn<?> expander, ACell form, AFn<?> cont) {
		// execute with adjusted depth
		int savedDepth=getDepth();
		Context<R> ctx =(Context<R>) this.withDepth(savedDepth+1);
		if (ctx.isExceptional()) return ctx; // depth error, won't have modified depth

		//AVector<ACell> savedEnv=getLocalBindings();

		Context<R> rctx= (Context<R>)invoke(expander, form, cont);

		// reset depth after execution.
		//rctx=rctx.withLocalBindings(savedEnv);
		rctx=rctx.withDepth(savedDepth);
		return rctx;
	}

	/**
	 * Looks up an expander from a form in this context
	 * @param form Form which might be an expander reference (either a symbol or (lookup...) form)
	 * @return Expander instance, or null if no expander found
	 */
	public AFn<ACell> lookupExpander(ACell form) {
		/**
		 * MapEntry for Expander metadata lookup
		 */
		AHashMap<ACell,ACell> me = null;
		Address addr;
		Symbol sym;

		if (form instanceof Symbol) {
			sym = (Symbol)form;
			me = this.lookupMeta(sym);
			addr = null;
		} else if (form instanceof AList) {
			// Need to check for (lookup ....) as this could reference an expander
			@SuppressWarnings("unchecked")
			AList<ACell> listForm = (AList<ACell>)form;
			int n = listForm.size();
			if (n <= 1) return null;
			if (!Symbols.LOOKUP.equals(listForm.get(0))) return null;
			ACell maybeSym = listForm.get(n-1);
			if (!(maybeSym instanceof Symbol)) return null;
			sym = (Symbol)maybeSym;
			if (n == 2) {
				addr = null;
				me = lookupMeta(sym);
			} else if (n == 3) {
				ACell maybeAddress = listForm.get(1);
				if (maybeAddress instanceof Symbol) {
					// one lookup via Environment for alias
					maybeAddress = lookupValue((Symbol)maybeAddress);
				}
				if (!(maybeAddress instanceof Address)) return null;
				addr = (Address)maybeAddress;
				me = lookupMeta((Address)maybeAddress,sym);
			} else {
				return null;
			}
		}  else {
			return null;
		}

		// If no metadata found, definitely not an expander
		if (me == null) return null;

		// TODO: examine syntax object for expander details?
		ACell expBool = me.get(Keywords.EXPANDER_Q);
		if (RT.bool(expBool)) {
			// expand form using specified expander and continuation expander
			ACell v = lookupValue(addr,sym);
			AFn<ACell> expander = RT.castFunction(v);
			if (expander != null) return expander;
		}
		return null;
	}



}
