package convex.core.cvm;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.ResultContext;
import convex.core.SourceCodes;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.exception.AExceptional;
import convex.core.cvm.exception.AThrowable;
import convex.core.cvm.exception.ATrampoline;
import convex.core.cvm.exception.ErrorValue;
import convex.core.cvm.exception.Failure;
import convex.core.cvm.exception.HaltValue;
import convex.core.cvm.exception.RecurValue;
import convex.core.cvm.exception.ReducedValue;
import convex.core.cvm.exception.ReturnValue;
import convex.core.cvm.exception.RollbackValue;
import convex.core.cvm.exception.TailcallValue;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.ASequence;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.util.BlobBuilder;
import convex.core.init.Init;
import convex.core.lang.Compiler;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.impl.CoreFn;
import convex.core.util.Economics;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

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
 */
public class Context {
	private static final long INITIAL_JUICE = 0;

	// Default values
	private static final AVector<AVector<ACell>> DEFAULT_LOG = null;
	private static int ZERO_DEPTH = 0;
	private static final AExceptional DEFAULT_EXCEPTION = null;
	private static final long ZERO_OFFER = 0L;
	public static final AVector<ACell> EMPTY_BINDINGS=Vectors.empty();
	private static final ACell NO_RESULT = null;
	private static final ACell NULL_SCOPE = null;
	private static final AExceptional NO_EXCEPTION = null;
	private static final CompilerState NO_COMPILER_STATE = null;
	
	// private static final Logger log=Logger.getLogger(Context.class.getName());

	/*
	 *  Frequently changing mutable fields during execution.
	 *
	 *  While these are mutable, it is also very cheap to just fork() short-lived Contexts
	 *  because the JVM generational GC will just sweep them up shortly afterwards.
	 */

	private long juice;
	private long juiceLimit;
	private ACell result;
	private AExceptional exception;
	private int depth;
	private AVector<ACell> localBindings;
	private ChainState chainState;

	/**
	 * Local log is an ordered [vector of [address scope location [values ...] ] entries
	 * See CAD33 for details
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
	 * Immutable inner class for less-frequently changing CVM state
	 * Should save some allocation / GC on average, since it will change less
	 * frequently than the surrounding Context and can be cheaply copied by reference.
	 *
	 * SECURITY: security critical, since it determines the current *address* and *caller*
	 * which in turn controls access to most account resources and rights.
	 */
	protected static final class ChainState {
		private final State state;
		private final TransactionContext txContext;
		private final Address caller;
		private final Address address;
		private final ACell scope;
		private final long offer;

		/**
		 * Cached copy of the current environment. Avoid looking up via Address each time.
		 */
		private final AccountStatus account;

		private ChainState(State state, TransactionContext transactionContext,Address caller, Address address,AccountStatus account, long offer,ACell scope) {
			this.state=state;
			this.txContext=transactionContext;
			this.caller=caller;
			this.address=address;
			this.account=account;
			this.offer=offer;
			this.scope=scope;
		}

		public static ChainState create(State state, TransactionContext tContext, Address caller, Address address, long offer, ACell scope) {
			AccountStatus as=state.getAccount(address);
			if (as==null) return null;
			return new ChainState(state,tContext,caller,address,as,offer,scope);
		}

		public ChainState withStateOffer(State newState,long newOffer) {
			if ((state==newState)&&(offer==newOffer)) return this;
			return create(newState,txContext,caller,address,newOffer,scope);
		}

		private ChainState withState(State newState) {
			if (state==newState) return this;
			return create(newState,txContext,caller,address,offer,scope);
		}

		protected long getOffer() {
			return offer;
		}
		
		protected ACell getScope() {
			return scope;
		}

		/**
		 * Gets the current defined environment
		 * @return
		 */
		private AHashMap<Symbol, ACell> getEnvironment() {
			return RT.ensureHashMap(account.getEnvironment());
		}

		private ChainState withEnvironment(AHashMap<Symbol, ACell> newEnvironment)  {
			if (account.getEnvironment()==newEnvironment) return this;
			AccountStatus nas=account.withEnvironment(newEnvironment);
			State newState=state.putAccount(address,nas);
			return withState(newState);
		}

		public ChainState withEnvironment(AHashMap<Symbol, ACell> newEnvironment,
				AHashMap<Symbol, AHashMap<ACell, ACell>> newMeta) {
			if ((account.getEnvironment()==newEnvironment)&&(account.getMetadata()==newMeta)) return this;
			AccountStatus nas=account.withEnvironment(newEnvironment).withMetadata(newMeta);
			State newState=state.putAccount(address,nas);
			return withState(newState);
		}

		private ChainState withAccounts(AVector<AccountStatus> newAccounts) {
			return withState(state.withAccounts(newAccounts));
		}

		public AHashMap<Symbol, AHashMap<ACell, ACell>> getMetadata() {
			return RT.ensureHashMap(account.getMetadata());
		}

		public ChainState withScope(ACell newScope) {
			if (scope==newScope) return this;
			return create(state,txContext,caller,address,offer,newScope);
		}

		public AccountStatus getAccount() {
			return account;
		}
		
		public Address getOrigin() {
			return txContext.getOrigin();
		}
 
		public AccountStatus getOriginAccount() {
			Address o=getOrigin();
			if (address.equals(o)) return account;
			return state.getAccount(o);
		}

		public AccountKey getPeer() {
			return txContext.getPeer();
		}

		public ChainState withTransactionContext(TransactionContext tctx) {
			if (txContext==tctx) return this;
			return create(state,tctx,caller,address,offer,scope);
		}

		public TransactionContext getTransactionContext() {
			return txContext;
		}

	}

	protected Context(ChainState chainState, long juice, long juiceLimit, AVector<ACell> localBindings2,ACell result, int depth, AExceptional exception, AVector<AVector<ACell>> log, CompilerState comp) {
		this.chainState=chainState;
		this.juice=juice;
		this.juiceLimit=juiceLimit;
		this.localBindings=localBindings2;
		this.result=result;
		this.depth=depth;
		this.exception=exception;
		this.log=log;
		this.compilerState=comp;
	}

	@SuppressWarnings("unchecked")
	private static <T extends ACell> Context create(ChainState cs, long juice,long juiceLimit, AVector<ACell> localBindings, ACell result, int depth,AVector<AVector<ACell>> log, CompilerState comp) {
		if (juice<0) throw new IllegalArgumentException("Negative juice! "+juice);
		Context ctx= new Context(cs,juice,juiceLimit,localBindings,(T)result,depth,DEFAULT_EXCEPTION,log, comp);
		return ctx;
	}

	private static <T extends ACell> Context create(State state, TransactionContext tctx,long juice,long juiceLimit,AVector<ACell> localBindings, T result, int depth, Address origin,Address caller, Address address, long offer, AVector<AVector<ACell>> log, CompilerState comp) {
		ChainState chainState=ChainState.create(state,tctx,caller,address,offer,NULL_SCOPE);
		if (chainState==null) throw new Error("Attempting to create context with invalid Address");
		return create(chainState,juice,juiceLimit,localBindings,result,depth,log,comp);
	}

	/**
	 * Creates an CVM execution context
	 *
	 * Useful for Testing or local execution
	 *
	 * @param state State to use for this Context
	 * @return Fake context
	 */
	public static Context create(State state) {
		return create(state,Address.ZERO);
	}

	/**
	 * Creates a execution context for the given address.
	 *
	 * Not valid for use in real transactions, but can be used to
	 * compute stuff off-chain "as-if" the actor made the call.
	 *
	 * @param state State to use for this Context
	 * @param origin Origin address to use
	 * @return Fake context
	 */
	public static Context create(State state, Address origin) {
		return create(state,origin,Constants.MAX_TRANSACTION_JUICE);
	}

	/**
	 * Creates an execution context with the specified actor as origin, and reserving the appropriate
	 * amount of juice.
	 *
	 * Juice reserve is extracted from the actor's current balance.
	 *
	 * @param state Initial State for Context
	 * @param origin Origin Address for Context
	 * @param juiceLimit Initial juice requested for Context
	 * @return Initial execution context with reserved juice.
	 */
	public static Context create(State state, Address origin,long juiceLimit) {
		if (origin==null) throw new IllegalArgumentException("Null address!");
		TransactionContext tctx=TransactionContext.create(state);
		tctx.origin=origin;
		AccountStatus as=state.getAccount(origin);
		if (as==null) {
			// no account
			return Context.create(state).withError(ErrorCodes.NOBODY);
		}
		return create(state,tctx,0,juiceLimit,EMPTY_BINDINGS,NO_RESULT,ZERO_DEPTH,origin,null,origin,INITIAL_JUICE,DEFAULT_LOG,null);
	}


	/**
	 * Performs key actions at the end of a transaction:
	 * <ul>
	 * <li>Refunds juice</li>
	 * <li>Accumulates used juice fees in globals</li>
	 * <li>Increments sequence number</li>
	 * </ul>
	 *
	 * @param initialState State before transaction execution (after prepare)
	 * @param rc Juice price of current execution
	 * @return Updated context
	 */
	public Context completeTransaction(State initialState, ResultContext rc) {
		// get state at end of transaction application
		State state=getState();
		long executionJuice=this.juice;
		rc.juiceUsed=executionJuice;

		// Base fixed juice cost per transaction
		long trxJuice=Juice.priceTransaction(rc.tx);
		
		long totalJuice=executionJuice+trxJuice;
		long juicePrice=rc.juicePrice;
		long juiceFees=Juice.addMul(0,totalJuice,juicePrice);
		
		Address address=getAddress();
		AccountStatus account=state.getAccount(address);
		long balance=account.getBalance();
		boolean juiceFailure=juiceFees>balance;

		boolean memoryFailure=false;
		long memorySpend=0L; // usually zero

		if (juiceFailure) {
			// consume whole balance, reset state
			juiceFees=balance;
			state=initialState;
		} else if (!rc.context.isExceptional()) {
			// Transaction appears to have succeeded, and will do unless memory accounting fails
			// do memory accounting as long as we didn't fail for any other reason
			// compute memory delta (memUsed) and store in ResultContext
			long memUsed=state.getMemorySize()-initialState.getMemorySize();
			rc.memUsed=memUsed;
			
			long allowance=account.getMemory();
			if (memUsed>0) {
				long allowanceUsed=Math.min(allowance, memUsed);
				if (allowanceUsed>0) {
					account=account.withMemory(allowance-allowanceUsed);
				}
	
				// compute additional memory purchase requirement beyond allowance
				long purchaseNeeded=memUsed-allowanceUsed;
				if (purchaseNeeded>0) {
					long poolBalance=state.getGlobalMemoryValue().longValue();
					long poolAllowance=state.getGlobalMemoryPool().longValue();
					memorySpend=(long)Economics.swapPrice(purchaseNeeded, poolAllowance, poolBalance);
	
					if ((balance-juiceFees)>=memorySpend) {
						// enough to cover memory price, so automatically buy from pool
						// System.out.println("Buying "+purchaseNeeded+" memory for: "+price);
						state=state.updateMemoryPool(poolBalance+memorySpend, poolAllowance-purchaseNeeded);
					} else {
						// Insufficient memory, so need to roll back state to before transaction
						// origin should still pay transaction fees, but no memory costs
						memorySpend=0L;
						state=initialState;
						account=state.getAccount(address);
						memoryFailure=true;
					}
				}
			} else {
				// credit any unused memory back to allowance (may be zero)
				long allowanceCredit=-memUsed;
				account=account.withMemory(allowance+allowanceCredit);
			}
		} else {
			// Transaction failed for reason other than juice usage exceeding balance
			AExceptional ex=rc.context.getExceptional();
			// It's user :CODE that caused the error if catchable, otherwise :CVM source 
			rc.source=(ex.isCatchable())?SourceCodes.CODE:SourceCodes.CVM;
		}

		// Compute total fees
		long fees=juiceFees+memorySpend;
		rc.totalFees=fees;
		
		// Make balance changes if needed for refund and memory purchase
		// Also increment sequence number
		account=account.addBalanceAndSequence(-fees);

		// update Account
		state=state.putAccount(address,account);

		// final state update and result reporting
		Context rctx=this.withState(state);
		if (juiceFailure) {
			rctx=rctx.withError(ErrorCodes.JUICE, "Insuffienct balance to cover juice fees of "+rc.getJuiceFees());
			rc.source=SourceCodes.CVM;
		} else if (memoryFailure) {
			rctx=rctx.withError(ErrorCodes.MEMORY, "Unable to allocate additional memory required for transaction ("+rc.memUsed+" bytes)");
			rc.source=SourceCodes.CVM;
		}
		return rctx;
	}

	public Context withState(State newState) {
		return this.withChainState(chainState.withState(newState));
	}

	/**
	 * Get the latest state from this Context
	 * @return State instance
	 */
	public State getState() {
		return chainState.state;
	}

	/**
	 * Get the juice used so far in this Context
	 * @return Juice used
	 */
	public long getJuiceUsed() {
		return juice;
	}
	
	/**
	 * Get the juice available in this Context
	 * @return Juice available
	 */
	public long getJuiceAvailable() {
		return juiceLimit-juice;
	}
	
	/**
	 * Get the juice limit in this Context
	 * @return Juice limit
	 */
	public long getJuiceLimit() {
		return juiceLimit;
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
	 * @param gulp Amount of juice to consume
	 * @return Updated context with juice consumed
	 */
	public Context consumeJuice(long gulp) {
		if (gulp<=0) throw new Error("Juice gulp must be positive!");
		if(!checkJuice(gulp)) return withJuiceError();
		juice=juice+gulp;
		return this;
		// return new Context<R>(chainState,newJuice,localBindings,(R) result,depth,isExceptional);
	}

	/**
	 * Checks if there is sufficient juice for a given gulp of consumption. Does not alter context in any way.
	 *
	 * @param gulp Amount of juice to be consumed.
	 * @return true if juice is sufficient, false otherwise.
	 */
	public boolean checkJuice(long gulp) {
		long juiceUsed = juice + gulp;
		return (juiceUsed >= 0 && juiceUsed <= juiceLimit);
	}

	/**
	 * Looks up a symbol's value in the current execution context, without any effect on the Context (no juice consumed etc.)
	 *
	 * @param symbol Symbol to look up
	 * @return Context with the result of the lookup (may be an undeclared exception)
	 */
	public Context lookup(Symbol symbol) {
		// try lookup in dynamic environment
		Address address=getAddress();
		return lookupDynamic(address,symbol);
	}

	/**
	 * Looks up a value in the dynamic environment. Consumes no juice.
	 * Returns an UNDECLARED exception if the symbol cannot be resolved.
	 * Returns a NOBODY exception if the specified Account does not exist
	 *
	 * @param address Address of account in which to look up value
	 * @param symbol Symbol to look up
	 * @return Updated Context
	 */
	public Context lookupDynamic(Address address, Symbol symbol) {
		AccountStatus as=getAccountStatus(address);
		if (as==null) return withError(ErrorCodes.NOBODY,"No account found for: "+address);
		MapEntry<Symbol,ACell> envEntry=lookupDynamicEntry(as,symbol);

		// if not found, return UNDECLARED error
		if (envEntry==null) {
			return withUndeclaredError(symbol);
		}

		// Result is whatever is defined as the datum value in the environment entry
		ACell result = envEntry.getValue();
		return withResult(result);
	}

	/**
	 * Looks up Metadata for the given symbol in this context
	 * @param sym Symbol to look up
	 * @return Metadata for given symbol (may be empty) or null if undeclared
	 */
	public AHashMap<ACell,ACell> lookupMeta(Symbol sym) {
		return lookupMeta(getAddress(),sym);
	}

	/**
	 * Looks up Metadata for the given symbol in this context
	 * @param address Address to use for lookup (may pass null for current environment)
	 * @param sym Symbol to look up
	 * @return Metadata for given symbol (may be empty) or null if undeclared
	 */
	public AHashMap<ACell,ACell> lookupMeta(Address address,Symbol sym) {
		if (address==null) address=getAddress();
		for (int i=0; i<Constants.LOOKUP_DEPTH; i++) {
			AccountStatus as=getAccountStatus(address);
			if (as==null) return null;
			AHashMap<Symbol, ACell> env=as.getEnvironment();
			if ((env!=null)&&env.containsKey(sym)) {
				return as.getMetadata(sym);
			}
			
			// go to parent
			if (Core.CORE_ADDRESS.equals(address)) break;
			address=getParentAddress(as);
			if (address==null) return null;
		}
		return null;
	}
	
	/**
	 * Looks up the address of the account that defines a given Symbol
	 * @param sym Symbol to look up
	 * @param address Address to look up in first instance (null for current address).
	 * @return Address defining the given symbol (or null if undeclared)
	 */
	public Address lookupDefiningAddress(Address address,Symbol sym) {
		Address addr=(address==null)?getAddress():address;
		for (int i=0; i<Constants.LOOKUP_DEPTH; i++) {
			if (addr==null) break;
			AccountStatus as=getAccountStatus(addr);
			if (as==null) return null;
			
			MapEntry<Symbol, ACell> entry=as.getEnvironmentEntry(sym);
			if (entry!=null) {
				return addr;
			}
			
			// go to parent
			if (addr.equals(Core.CORE_ADDRESS)) break;
			addr=getParentAddress(as);
		}
		return null;
	}

	private Address getParentAddress(AccountStatus as) {
		Address ba=as.getParent();
		if (ba==null) return Core.CORE_ADDRESS;
		return ba;
	}

	/**
	 * Looks up value for the given symbol in this context
	 * @param symName Symbol name to look up, as a Java String for convenience
	 * @return Value for the given symbol or null if undeclared
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T lookupValue(String symName) {
		return (T) lookupValue(getAddress(),Symbol.create(symName));
	}
	
	/**
	 * Looks up value for the given symbol in this context
	 * @param sym Symbol to look up
	 * @return Value for the given symbol or null if undeclared
	 */
	public ACell lookupValue(Symbol sym) {
		return lookupValue(getAddress(),sym);
	}

	/**
	 * Looks up value for the given symbol in this context
	 * @param address Address to look up in (may be null for current environment)
	 * @param sym Symbol to look up
	 * @return Value for the given symbol or null if undeclared
	 */
	public ACell lookupValue(Address address,Symbol sym) {
		MapEntry<Symbol,ACell> entry=lookupDynamicEntry(address,sym);
		if (entry==null) return null;
		return entry.getValue();
	}

	/**
	 * Looks up an environment entry for a specific address without consuming juice.
	 *
	 * @param address Address of Account in which to look up entry
	 * @param sym Symbol to look up
	 * @return Environment entry
	 */
	public MapEntry<Symbol,ACell> lookupDynamicEntry(Address address,Symbol sym) {
		if (address==null) address=getAddress();
		AccountStatus as=getAccountStatus(address);
		if (as==null) return null;
		return lookupDynamicEntry(as,sym);
	}

	private MapEntry<Symbol,ACell> lookupDynamicEntry(AccountStatus as,Symbol sym) {
		// Get environment for Address, or default to initial environment
		for (int i=0; i<Constants.LOOKUP_DEPTH; i++) {
			if (as==null) return Core.ENVIRONMENT.getEntry(sym);

			AHashMap<Symbol, ACell> env = as.getEnvironment();
			if (env!=null) {
				MapEntry<Symbol,ACell> result=env.getEntry(sym);
				if (result!=null) return result;
			}
			
			Address parent=getParentAddress(as);
			as=getAccountStatus(parent); // if not found, will be null
		}
		return null;
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
	 * Gets the holdings map for the current account.
	 * @return Map of holdings, or null if the current account does not exist.
	 */
	public Index<Address,ACell> getHoldings() {
		AccountStatus as=getAccountStatus(getAddress());
		if (as==null) return null;
		Index<Address, ACell> hodls = as.getHoldings();
		if (hodls==null) hodls=AccountStatus.EMPTY_HOLDINGS;
		return hodls;
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
	 * @param address Address to check balance for
	 * @return Balance in Convex Coins
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
	 * Gets the scope of the currently executing context.
	 *
	 * Will be null if no scope was set
	 * @return Caller of the currently executing context
	 */
	public ACell getScope() {
		return chainState.scope;
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
	@SuppressWarnings("unchecked")
	public <R extends ACell> R getResult() {
		if (exception!=null) {
			String msg = "Can't get result with exceptional value: "+exception;
			if (exception instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)exception;
				msg=msg+"\n"+ev.getTrace();
			}
			throw new IllegalStateException(msg);
		}
		return (R) result;
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
		if (exception==null) throw new IllegalStateException("Can't get exceptional value for context with result: "+exception);
		return exception;
	}

	/**
	 * Returns a context updated with the specified result.
	 *
	 * Context may become exceptional depending on the result type.
	 *
	 * @param value Value
	 * @return Context updated with the specified result.
	 */
	public Context withResult(ACell value) {
		result=value;
		exception=null;
		return this;
	}

	/**
	 * Updates this context with a given value, which may either be a normal result or exceptional value
	 * @param value Value
	 * @return Context updated with the specified result value.
	 */
	public Context withValue(Object value) {
		if (value instanceof AExceptional) {
			exception=(AExceptional)value;
			result=null;
		} else {
			result = (ACell)value;
			exception=null;
		}
		return this;
	}

	public Context withResult(long gulp,ACell value) {
		if (!checkJuice(gulp)) return withJuiceError();
		juice=juice+gulp;
		return withResult(value);
	}

	/**
	 * Returns this context with a JUICE error, consuming all juice.
	 * @return Exceptional Context signalling JUICE error.
	 */
	public Context withJuiceError() {
		// set juice to zero. Can't consume more that we have!
		this.juice=juiceLimit;
		return withException(Failure.juice());
	}

	public Context withException(AExceptional exception) {
		//return (Context<R>) new Context<AExceptional>(chainState,juice,localBindings,exception,depth,true);
		this.exception=exception;
		this.result=null;
		return this;
	}

	public Context withException(long gulp,AExceptional value) {
		if (!checkJuice(gulp)) return withJuiceError();
		juice=juice+gulp;
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
	private Context withEnvironment(AHashMap<Symbol, ACell> newEnvironment)  {
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
	private Context withEnvironment(AHashMap<Symbol, ACell> newEnvironment, AHashMap<Symbol,AHashMap<ACell,ACell>> newMeta)  {
		ChainState cs=chainState.withEnvironment(newEnvironment,newMeta);
		return withChainState(cs);
	}

	private Context withChainState(ChainState newChainState) {
		if (chainState==newChainState) return this;
		
		// if the chain state changed, we need to check if coin balance was reduced: 
		// in which case we have to check the juice limit again
		AccountStatus oldOrigin=chainState.getOriginAccount();
		chainState=newChainState;
		AccountStatus newOrigin=newChainState.getOriginAccount();
		if (oldOrigin==newOrigin) return this;
		long newBalance=newOrigin.getBalance();
		if (newBalance<oldOrigin.getBalance()) {
			reviseJuiceLimit(newBalance);
		}
		return this;
	}

	private void reviseJuiceLimit(long newBalance) {
		long juicePrice=chainState.state.getJuicePrice().longValue();
		juiceLimit=Math.min(juiceLimit, Juice.calcAvailable(newBalance, juicePrice));
	}

	/**
	 * Executes an Op within this context, returning an updated context.
	 *
	 * @param op Op to execute
	 * @return Updated Context
	 */
	public Context execute(AOp<?> op) {
		// execute op with adjusted depth
		int savedDepth=getDepth();
		Context ctx =this.withDepth(savedDepth+1);
		if (ctx.isExceptional()) return ctx; // depth error, won't have modified depth

		Context rctx=op.execute(ctx);

		// reset depth after execution.
		rctx=rctx.withDepth(savedDepth);
		return rctx;
	}

	/**
	 * Executes an Op at the top level in a new forked Context. Handles top level halt, recur and return.
	 *
	 * Returning an updated context containing the result or an exceptional error.
	 *
	 * @param op Op to execute
	 * @return Updated Context
	 */
	public Context run(AOp<?> op) {
		// Security: run in fork
		Context ctx=fork().exec(op);

		// must handle state results like halt, rollback etc.
		return handleStateResults(ctx,false);
	}
	
	/**
	 * Executes a form at the top level in the current account. Handles top level halt, recur and return.
	 *
	 * Returning an updated context containing the result or an exceptional error.
	 *
	 * @param code Code to execute
	 * @return Updated Context
	 */
	public Context run(ACell code) {
		Context ctx=fork(); // for so we can handle rollback etc.
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
	 * @param fn Function to execute
	 * @param args Arguments for function
	 * @return Updated Context
	 */
	public Context invoke(AFn<?> fn, ACell... args) {
		// Note: we don't adjust depth here because execute(...) does it for us in the function body
		Context ctx = fn.invoke(this,args);

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
					ctx=ctx.withValue(null); // clear value
					ctx = fn.invoke(ctx,newArgs);
					v = ctx.getValue();
				} else if (v instanceof TailcallValue) {
					if (fn==Core.TAILCALL_STAR) break;
					TailcallValue rv=(TailcallValue)v;
					ACell[] newArgs = rv.getValues();

					// redirect function and invoke
					fn = (AFn<?>) rv.getFunction();
					ctx=ctx.withValue(null); // clear value
					ctx = fn.invoke(ctx,newArgs);
					v = ctx.getValue();
				}
			}

			// unwrap return value if necessary
			if ((v instanceof ReturnValue)&&(!(fn==Core.RETURN))) {
				ACell val = ((ReturnValue<?>) v).getValue();

				// unwrap result
				return ctx.withResult(val);
			}

			if (v instanceof AThrowable) {
				if (fn instanceof CoreFn) {
					AThrowable ev=(AThrowable)v;
					ev.addTrace("In core function: "+RT.str(fn)); // TODO: Core.getCoreName() ?
				}
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
	 * @return Context with local bindings updated
	 */
	public Context executeLocalBinding(ACell bindingForm, AOp<?> op) {
		Context ctx=this.execute(op);
		if (ctx.isExceptional()) return ctx;
		return ctx.updateBindings(bindingForm, ctx.getResult());
	}

	/**
	 * Updates local bindings with a given binding form
	 *
	 * @param bindingForm Binding form
	 * @param args Arguments to bind
	 * @return Non-exceptional Context with local bindings updated, or an exceptional result if bindings fail
	 */
	@SuppressWarnings("unchecked")
	public Context updateBindings(ACell bindingForm, Object args) {
		// Clear any exceptional status
		Context ctx=this.withValue(null);	

		if (bindingForm instanceof Symbol) {
			Symbol sym=(Symbol)bindingForm;
			if (sym.equals(Symbols.UNDERSCORE)) return ctx;
			// args must be an ACell at this point, since we must have descended at least one level of binding from a 
			// function invocation on ACell[]
			return withLocalBindings(localBindings.conj((ACell)args));
		} else if (bindingForm instanceof AVector) {
			AVector<ACell> v=(AVector<ACell>)bindingForm;
			long vcount=v.count(); // count of binding form symbols (may include & etc.)

			// Count the arguments, exit with a CAST error if args are not sequential
			Long argCount=RT.argumentCount(args);
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
					AVector<ACell> rest=RT.vec(args,i,i+consumeCount);
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

	public boolean print(BlobBuilder bb, long limit)  {
		bb.append("{");
		bb.append(":juice "+juice);
		bb.append(',');
		bb.append(":juice-limit "+juiceLimit);
		bb.append(',');
		bb.append(":result ");
		if (!RT.print(bb,result,limit)) return false;
		bb.append(',');
		bb.append(":state ");
		if (!getState().print(bb,limit)) return false;
		bb.append("}");
		return bb.check(limit);
	}
	
	@Override
	public String toString() {
		BlobBuilder bb=new BlobBuilder();
		long LIMIT=1000;
		print(bb,LIMIT);
		return bb.toBlob().toCVMString(LIMIT).toString();
	}

	public AVector<ACell> getLocalBindings() {
		return localBindings;
	}

	/**
	 * Updates this Context with new local bindings. Doesn't affect result state (exceptional or otherwise)
	 * @param newBindings New local bindings map to use.
	 * @return Updated context
	 */
	public Context withLocalBindings(AVector<ACell> newBindings) {
		localBindings=newBindings;
		return this;
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
		return chainState.getOrigin();
	}

	/**
	 * Defines a value in the environment of the current address
	 * @param key Symbol of the mapping to create
	 * @param value Value to define
	 * @return Updated context with symbol defined in environment
	 */
	public Context define(Symbol key, ACell value) {
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
	public Context defineWithSyntax(Syntax syn, ACell value) {
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
	public Context undefine(Symbol key) {
		AHashMap<Symbol, ACell> m = getEnvironment();
		AHashMap<Symbol, ACell> newEnvironment = m.dissoc(key);
		AHashMap<Symbol, AHashMap<ACell,ACell>> newMeta = getMetadata().dissoc(key);

		return withEnvironment(newEnvironment,newMeta);
	}

	/**
	 * Expand and compile a form in this Context.
	 *
	 * @param form Form to expand and compile
	 * @return Updated Context with compiled Op as result
	 */
	public Context expandCompile(ACell form) {
		// run compiler with adjusted depth
		int saveDepth=getDepth();
		Context rctx =this.withDepth(saveDepth+1);
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
	 * @param expandedForm Form to compile
	 * @return Updated Context with compiled Op as result
	 */
	public Context compile(ACell expandedForm) {
		// Save an adjust depth
		int saveDepth=getDepth();
		Context rctx =this.withDepth(saveDepth+1);
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
	 * Other forms will be expanded and compiled before execution, according to `compile` as defined in the current environment
	 *
	 * @param form Form to evaluate
	 * @return Context containing the result of evaluating the specified form
	 */
	public Context eval(ACell form) {
		Context ctx= this;
		AOp<?> op;
	
		if (form instanceof AOp) {
			op=(AOp<?>)form;
		} else {
			ctx=ctx.lookup(Symbols.STAR_LANG);
			if (ctx.isExceptional()) return ctx;
			AFn<?> cfn=RT.ensureFunction(ctx.getResult());
			if (cfn==null) cfn=Core.COMPILE;
			ctx=ctx.invoke(cfn, form);
			if (ctx.isExceptional()) return ctx;
			
			ACell cop=ctx.getResult();
			if (!(cop instanceof AOp)) return ctx.withCompileError("*lang* did not produce CVM op");
			op = (AOp<?>)cop;
			ctx=ctx.withResult(null); // clear result before execution
		}
		return ctx.exec(op);
	}
	
	/**
	 * Executes an op as a top level instruction (no local bindings)
	 * @param <T> Type of Op result
	 * @param op Op to execute
	 * @return Updated Context
	 */
	public <T extends ACell> Context exec(AOp<T> op) {
		AVector<ACell> savedBindings = getLocalBindings();
		Context ctx=withLocalBindings(Vectors.empty());
		ctx= ctx.execute(op);
		return ctx.withLocalBindings(savedBindings);
	}

	/**
	 * Evaluates a form as another Address.
	 *
	 * Causes TRUST error if the Address is not controlled by the current address.
	 * @param target Address of Account in which to evaluate
	 * @param form Form to evaluate
	 * @return Updated Context
	 */
	public Context evalAs(Address target, ACell form) {
		Address caller=getAddress();
		AccountStatus as=this.getAccountStatus(target);
		if (as==null) return withError(ErrorMessages.nobody(target));

		// TODO should probably refactor into a checkControl function or similar
		ACell controller=as.getController();
		boolean canControl=false;
		Context ctx=this;
		if (caller.equals(target)) {
			// can always control own address
			canControl=true;
		} else if (controller==null) {
			return withError(ErrorCodes.TRUST,"Cannot control address with nil controller set: "+target);
		} else if (caller.equals(controller)) {
			// if we are the precisely specified controller, can control
			canControl=true;
		}  else {
			// need to check trust monitor
			Address actorAddress=RT.callableAddress(controller);
			if (actorAddress==null) return ctx.withError(ErrorCodes.TRUST,"Cannot control address because controller is not a valid address or scoped actor");
			AccountStatus actorAccount=this.getAccountStatus(actorAddress);
			if (actorAccount==null) return ctx.withError(ErrorCodes.TRUST,"Cannot control address because controller does not exist: "+controller);

			// (call target amount (receive-coin source amount nil))
			ctx=ctx.actorCall(controller,ZERO_OFFER,Symbols.CHECK_TRUSTED_Q,caller,Keywords.CONTROL,target);
			if (ctx.isExceptional()) {
				return ctx.withError(ErrorCodes.TRUST,"Failure trying to obtain :control rights");
			}
			canControl=RT.bool(ctx.getResult());
		}

		if (!canControl) return ctx.withError(ErrorCodes.TRUST,"Cannot control address: "+target);

		// SECURITY: eval with a context switch
		final Context exContext=Context.create(ctx.getState(),getTransactionContext(), ctx.juice,juiceLimit, EMPTY_BINDINGS, NO_RESULT, depth+1, getOrigin(),caller, target,ZERO_OFFER,ctx.log,NO_COMPILER_STATE);

		final Context rContext=exContext.eval(form);
		// SECURITY: must handle results as if returning from an actor call
		return handleStateResults(rContext,false);
	}

	/**
	 * Executes code as if run in the specified account, but always rolling back state changes.
	 * @param address Address of Account in which to execute the query
	 * @param form Code to execute.
	 * @return Context updated with only query result and juice consumed
	 */
	public Context queryAs(Address address, ACell form) {
		// chainstate with the target address as origin.
		State s=getState();
		ChainState cs=ChainState.create(s,chainState.txContext,getAddress(),address,ZERO_OFFER,NULL_SCOPE);
		if (cs==null) return withError(ErrorCodes.NOBODY,"Address does not exist: "+address);
		Context ctx=Context.create(cs, juice,juiceLimit, EMPTY_BINDINGS, NO_RESULT, depth,log,NO_COMPILER_STATE);
		ctx=ctx.eval(form);
		return handleStateResults(ctx,true);
	}

	/**
	 * Compiles a sequence of forms in the current context.
	 * Returns a vector of ops in the updated Context.
	 *
	 * Maintains depth.
	 *
	 * @param forms A sequence of forms to compile
	 * @return Updated context with vector of compiled forms
	 */
	public Context compileAll(ASequence<ACell> forms) {
		Context rctx = Compiler.compileAll(forms, this);
		return rctx;
	}

//	public <R> Context<R> adjustDepth(int delta) {
//		int newDepth=Math.addExact(depth,delta);
//		return withDepth(newDepth);
//	}

	/**
	 * Changes the depth of this context. Returns exceptional result if depth limit exceeded.
	 * @param newDepth New depth value
	 * @return Updated context with new depth set
	 */
	public Context withDepth(int newDepth) {
		if (newDepth==depth) return this;
		if ((newDepth<0)||(newDepth>Constants.MAX_DEPTH)) return withError(ErrorCodes.DEPTH,"Invalid depth: "+newDepth);
		depth=newDepth;
		return this;
	}

	public Context withJuice(long newJuice) {
		juice=newJuice;
		return this;
	}

	public Context withJuiceLimit(long newJuiceLimit) {
		juiceLimit = newJuiceLimit;
		return this;
	}

	public Context withCompilerState(CompilerState comp) {
		compilerState=comp;
		return this;
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
	 * Tests if this Context's current status contains an Error. Errors are an uncatchable subset of Exceptions.
	 *
	 * @return true if context has an Error value, false otherwise
	 */
	public boolean isError() {
		return (exception!=null)&&(exception instanceof ErrorValue);
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
	 * Transfers funds from the current address to the target.
	 *
	 * Uses no juice
	 *
	 * @param target Target Address, will be created if does not already exist.
	 * @param amount Amount to transfer, must be between 0 and Amount.MAX_VALUE inclusive
	 * @return Context with sent amount if the transaction succeeds, or an exceptional value if the transfer fails
	 */
	public Context transfer(Address target, long amount) {
		if (amount<0) return withError(ErrorCodes.ARGUMENT,"Can't transfer a negative amount");
		if (amount>Constants.MAX_SUPPLY) return withError(ErrorCodes.ARGUMENT,"Can't transfer an amount beyond maximum limit");

		AVector<AccountStatus> accounts=getState().getAccounts();

		Address source=getAddress();
		long sourceIndex=source.longValue();
		AccountStatus sourceAccount=accounts.get(sourceIndex);

		long currentBalance=sourceAccount.getBalance();
		if (currentBalance<amount) {
			return this.withFundsError(ErrorMessages.insufficientFunds(source,amount));
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

		// Special handling for an actor account other than #0 (burn address)
		if (targetAccount.isActor()&&(!(target.longValue()==0))) {
			// (call target amount (receive-coin source amount nil))
			// SECURITY: actorCall must do fork to preserve this
			Context actx=this.fork();
			actx=actorCall(target,amount,Symbols.RECEIVE_COIN,source,CVMLong.create(amount),null);
			if (actx.isExceptional()) return actx;

			long sent=currentBalance-actx.getBalance(source);
			return actx.withResult(CVMLong.create(sent));
		} 
		
		// must be a user account
		long oldTargetBalance=targetAccount.getBalance();
		long newTargetBalance=oldTargetBalance+amount;
		AccountStatus newTargetAccount=targetAccount.withBalance(newTargetBalance);
		accounts=accounts.assoc(targetIndex, newTargetAccount);

		// SECURITY: new context with updated accounts
		Context result=withChainState(chainState.withAccounts(accounts)).withResult(CVMLong.create(amount));

		return result;
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
	public Context transferMemoryAllowance(Address target, CVMLong amountToSend) {
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
		Context result=withChainState(chainState.withAccounts(accounts)).withResult(amountToSend);
		return result;
	}

	/**
	 * Sets the memory allowance for the current account, buying / selling from the pool as necessary to
	 * ensure the correct final allowance
	 * @param allowance New memory allowance
	 * @return Context indicating the price paid for the allowance change (may be zero or negative for refund)
	 */
	public Context setMemory(long allowance) {
		State state=getState();
		AVector<AccountStatus> accounts=state.getAccounts();
		if (allowance<0) return withError(ErrorCodes.ARGUMENT,"Can't transfer a negative allowance amount");

		Address source=getAddress();
		long sourceIndex=source.longValue();
		AccountStatus sourceAccount=accounts.get(sourceIndex);

		long current=sourceAccount.getMemory();
		long balance=sourceAccount.getBalance();
		long delta=allowance-current;
		if (delta==0L) return this.withResult(CVMLong.ZERO);

		try {
			long poolAllowance=state.getGlobalMemoryPool().longValue();
			long poolBalance=state.getGlobalMemoryValue().longValue();
			long price = (long)Economics.swapPrice(delta, poolAllowance,poolBalance);
			if (price>balance) {
				return withError(ErrorCodes.FUNDS,"Cannot afford allowance, would cost: "+price);
			}
			sourceAccount=sourceAccount.withBalances(balance-price, allowance);
			state=state.updateMemoryPool(poolBalance+price, poolAllowance-delta);

			// Update accounts
			AVector<AccountStatus> newAccounts=accounts.assoc(sourceIndex, sourceAccount);
			state=state.withAccounts(newAccounts);
			return withState(state).withResult(Juice.MEMORY_TRADE,CVMLong.create(price));
		} catch (IllegalArgumentException e) {
			return withError(ErrorCodes.FUNDS,"Cannot trade allowance: "+e.getMessage());
		}
	}

	/**
	 * Accepts offered funds for the given address.
	 *
	 * STATE error if offered amount is insufficient. ARGUMENT error if acceptance is negative.
	 *
	 * @param amount Amount to accept
	 * @return Updated context, with long amount accepted as result
	 */
	public Context acceptFunds(long amount) {
		if (amount<0L) return this.withError(ErrorCodes.ARGUMENT,"Negative accept argument");
		if (amount==0L) return this.withResult(Juice.ACCEPT, CVMLong.ZERO);

		long offer=getOffer();
		if (amount>offer) return this.withError(ErrorCodes.STATE,"Insufficient offered funds");

		State state=getState();
		Address addr=getAddress();
		long balance=state.getBalance(addr);
		state=state.withBalance(addr,balance+amount);

		// need to update both state and offer
		ChainState cs=chainState.withStateOffer(state,offer-amount);
		Context ctx=this.withChainState(cs);

		return ctx.withResult(Juice.ACCEPT, CVMLong.create(amount));
	}

	/**
	 * Executes a call to an Actor. Utility function which convert a java String function name
	 *
	 * @param target Target Actor address or scope vector
	 * @param offer Amount of Convex Coins to offer in Actor call
	 * @param functionName Symbol of function name defined by Actor
	 * @param args Arguments to Actor function invocation
	 * @return Context with result of Actor call (may be exceptional)
	 */
	public Context actorCall(ACell target, long offer, String functionName, ACell... args) {
		return actorCall(target,offer,Symbol.create(functionName),args);
	}

	/**
	 * Executes a call to an Actor.
	 *
	 * @param target Target Actor address
	 * @param offer Amount of Convex Coins to offer in Actor call
	 * @param functionName Symbol of function name defined by Actor
	 * @param args Arguments to Actor function invocation
	 * @return Context with result of Actor call (may be exceptional)
	 */
	@SuppressWarnings("unchecked")
	public Context actorCall(ACell target, long offer, ACell functionName, ACell... args) {
		// SECURITY: set up state for actor call
		State state=getState();
		Symbol sym=RT.ensureSymbol(functionName);
		Address targetAddress;
		ACell scope=null;
		
		// Handle target possibilities, may be an Address or Scoped Actor reference
		if (target instanceof Address) {
			targetAddress=(Address)target;
		} else {
			if (!(target instanceof AVector)) {
				return this.withCastError(target, "call target must be an Address or [Address *scope*] vector");
			}
			AVector<ACell> v=(AVector<ACell>)target;
			if (!(v.count()==2)) {
				return this.withCastError(target, "call target vector must have length 2");
			}
			targetAddress=RT.ensureAddress(v.get(0));
			if (targetAddress==null) {
				return this.withCastError(target, "call target vector must start with an Address");			
			}
			scope=v.get(1);
		}
		
		AccountStatus as=state.getAccount(targetAddress);
		if (as==null) return this.withError(ErrorCodes.NOBODY,"Call target Account does not exist: "+target);

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

		AFn<?> fn = as.getCallableFunction(sym);

		if (fn == null) {
			if (as.getEnvironmentEntry(sym)==null) {
				return this.withError(ErrorCodes.STATE, "Account " + targetAddress + " does not define Symbol: " + sym);						
			}
			return this.withError(ErrorCodes.STATE, "Value defined in account " + targetAddress + " is not a callable function: " + sym);
		}

		// Ensure we create a forked Context for the Actor call
		final Context exContext=forkActorCall(state, targetAddress, offer, scope);

		// INVOKE ACTOR FUNCTION
		final Context rctx=exContext.invoke(fn,args);

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
	 * @param state for forked context.
	 * @param target Target actor call address, will become new *address* for context
	 * @param offer Offer amount for actor call. Must have been pre-subtracted from caller account.
	 * @return
	 */
	private Context forkActorCall(State state, Address target, long offer, ACell scope) {
		Context fctx=Context.create(state, getTransactionContext(),juice, juiceLimit,EMPTY_BINDINGS, NO_RESULT, depth+1, getOrigin(),getAddress(), target,offer, log,NO_COMPILER_STATE);
		if (scope!=null) {
			fctx.chainState=fctx.chainState.withScope(scope);
		}
		return fctx;
	}

	private TransactionContext getTransactionContext() {
		return chainState.getTransactionContext();
	}

	/**
	 * Handle results at the end of an execution boundary (actor call, transaction etc.)
	 * @param returnContext Context containing return from child transaction / call
	 * @param rollback If true, always rolls back state effects
	 * @return Updated parent context
	 */
	public
	Context handleStateResults(Context returnContext, boolean rollback) {
		/** Return value */
		Object rv;
		if (returnContext.isExceptional()) {
			// SECURITY: need to handle exceptional states correctly
			AExceptional ex=returnContext.getExceptional();
			if (ex instanceof RollbackValue) {
				// roll back state to before Actor call
				// Note: this will also refund unused offer.
				rollback=true;
				rv=((RollbackValue<?>)ex).getValue();
			} else if (ex instanceof HaltValue) {
				rv=((HaltValue<?>)ex).getValue();
			} else if (ex instanceof ErrorValue) {
				// OK to pass through error, but need to roll back state changes
				rollback=true;
				rv=ex;
			} else if (ex instanceof ReturnValue) {
				// Normally doesn't happen (invoke catches this)
				// but might in a user transaction. Treat as a Halt.
				rv=((ReturnValue<?>)ex).getValue();
			} else {
				rollback=true;
				if (ex instanceof Failure) {
					rv=ex;
				} else {
					String msg;
					if (ex instanceof ATrampoline) {
						msg="attempt to recur or tail call outside of a function body";
					} if (ex instanceof ReducedValue) {
						msg="reduced used outside of a reduce operation";
					} else {
						msg="Unhandled Exception with Code:"+ex.getCode();
					}
					rv=ErrorValue.create(ErrorCodes.EXCEPTION, msg);
				}
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

		Context result=this.withState(returnState);
		result.juice=returnContext.juice;
		result=this.withValue(rv);
		return result;
	}

	/**
	 * Deploys a new account.
	 *
	 * Arguments must be an account setup code, which will be evaluated in the new account.
	 *
	 * Result will contain the new address if successful, an exception otherwise.
	 *
	 * @param code Account initialisation code
	 * @return Updated Context with account deployed, or an exceptional result
	 */
	public Context deploy(ACell... code) {
		int n=code.length;
		final State initialState=getState();

		// deploy initial contract state to next address
		Address address=initialState.nextAddress();
		State stateSetup=initialState.addActor();

		// Deployment execution context with forked context and incremented depth
		Context ctx=Context.create(stateSetup, getTransactionContext(), juice, juiceLimit,EMPTY_BINDINGS, NO_RESULT, depth+1, getOrigin(),getAddress(), address,ZERO_OFFER,log,NO_COMPILER_STATE);
		for (int i=0; i <n; i++) {
			ctx=ctx.eval(code[i]);
			if (ctx.isExceptional()) break;
		}
		Context result=this.handleStateResults(ctx,false);
		if (result.isExceptional()) return result;

		return result.withResult(Juice.DEPLOY_CONTRACT,address);
	}

	public Context withError(Keyword error) {
		return withError(ErrorValue.create(error));
	}

	public Context withError(Keyword errorCode,String message) {
		return withError(ErrorValue.create(errorCode,Strings.create(message)));
	}
	
	public Context withError(Keyword errorCode,AString message) {
		return withError(ErrorValue.create(errorCode,message));
	}
	
	public Context withError(Keyword errorCode, ACell rs) {
		return withError(ErrorValue.createRaw(errorCode,rs));
	}

	public Context withError(ErrorValue error) {
		error.addLog(log);
		error.setAddress(getAddress());
		return withException(error);
	}

	public Context withArityError(String message) {
		return withError(ErrorCodes.ARITY,message);
	}

	public Context withCompileError(String message) {
		return withError(ErrorCodes.COMPILE,message);
	}
	
	public Context withSyntaxError(String message) {
		return withError(ErrorCodes.SYNTAX,message);
	}
	
	public Context withUndeclaredError(Symbol sym) {
		return withError(ErrorCodes.UNDECLARED,sym.getName());
	}

	public Context withBoundsError(long index) {
		return withError(ErrorCodes.BOUNDS,"Index: "+index);
	}

	public Context withCastError(int argIndex, AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert argument at position "+(argIndex+1)+" to type "+klass);
	}

	public Context withCastError(int argIndex, ACell[] args, AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert argument at position "+(argIndex+1)+" (with type "+RT.getType(args[argIndex])+ ") to type "+klass);
	}

	public Context withCastError(ACell a, AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert value of type "+RT.getType(a)+ " to type "+klass);
	}

	public Context withCastError(AType klass) {
		return withError(ErrorCodes.CAST,"Can't convert value(s) to type "+klass);
	}

	public Context withCastError(ACell a, String message) {
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

	public Context withAssertError(String message) {
		return withError(ErrorCodes.ASSERT,message);
	}

	public Context withFundsError(String message) {
		return withError(ErrorCodes.FUNDS,message);
	}

	public Context withArgumentError(String message) {
		return withError(ErrorCodes.ARGUMENT,message);
	}

	/**
	 * Gets the current timestamp for this context. The timestamp is the greatest timestamp
	 * of all blocks in consensus (including the currently executing block).
	 *
	 * @return Timestamp in milliseconds since UNIX epoch
	 */
	public CVMLong getTimeStamp() {
		return getState().getTimestamp();
	}

	/**
	 * Schedules an operation for the specified future timestamp.
	 * Handles integrity checks and schedule juice.
	 *
	 * @param time Timestamp at which to schedule the op.
	 * @param op Operation to schedule.
	 * @return Updated context, with scheduled time as the result
	 */
	public Context schedule(long time, AOp<?> op) {
		// check vs current timestamp
		long timestamp=getTimeStamp().longValue();
		if (timestamp<0L) return withError(ErrorCodes.STATE);
		if (time<timestamp) time=timestamp;

		long juiceNeeded=(time-timestamp)/Juice.SCHEDULE_MILLIS_PER_JUICE_UNIT;
		if (!checkJuice(juiceNeeded)) return withJuiceError();

		State s=getState().scheduleOp(time,getAddress(),op);
		Context ctx=this.withChainState(chainState.withState(s));

		return ctx.withResult(juiceNeeded,CVMLong.create(time));
	}

	/**
	 * Sets the delegated stake on a specified peer to the specified level.
	 * May set to zero to remove stake. Stake will be capped by current balance.
	 *
	 * @param peerKey Peer Account key on which to stake
	 * @param newStake Amount to stake
	 * @return Context with amount of coins transferred to Peer as result (may be negative if stake withdrawn)
	 */
	public Context setDelegatedStake(AccountKey peerKey, long newStake) {
		return setDelegatedStake(peerKey,getAddress(),newStake);
	}
	
	/**
	 * Sets the delegated stake on a specified peer to the specified level.
	 * May set to zero to remove stake. Stake will be capped by current balance.
	 *
	 * @param peerKey Peer Account key on which to stake
	 * @param newStake Amount to stake
	 * @return Context with amount of coins transferred to Peer as result (may be negative if stake withdrawn)
	 */
	public Context setDelegatedStake(AccountKey peerKey, Address staker, long newStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(peerKey);
		if (ps==null) return withError(ErrorCodes.STATE,"Peer does not exist for account key: "+peerKey);
		if (newStake<0) return this.withArgumentError("Cannot set a negative stake");
		if (newStake>Constants.MAX_SUPPLY) return this.withArgumentError("Target stake out of valid Amount range");

		long balance=getBalance(staker);
		long currentStake=ps.getDelegatedStake(staker);
		long delta=newStake-currentStake;

		if (delta==0) return this; // no change
		
		// need to check sufficient balance if increasing stake
		if (delta>balance) return this.withFundsError("Insufficient balance ("+balance+") to increase Delegated Stake to "+newStake);

		// Final updates. Hopefully everything balances. SECURITY: test this. A lot.
		PeerStatus updatedPeer=ps.withDelegatedStake(staker, newStake);
		s=s.withBalance(staker, balance-delta); // adjust own balance
		s=s.withPeer(peerKey, updatedPeer); // adjust peer
		return withState(s).withResult(CVMLong.create(delta));
	}
	
	/**
	 * Sets the stake for a given Peer, transferring coins to/from the current address.
	 * Current address must be the controller of the peer.
	 * 
	 * @param peerKey Peer Account Key for which to update Stake
	 * @param newStake New stake for Peer
	 * @return Updated Context
	 */
	public Context setPeerStake(AccountKey peerKey, long newStake) {
		return setPeerStake(peerKey,getAddress(),newStake);
	}
	
	/**
	 * Sets the stake for a given Peer, transferring coins to/from the controller address.
	 * @param peerKey Peer Account Key for which to update Stake
	 * @param controller Controller address for the specified peer
	 * @param newStake New stake for Peer
	 * @return Updated Context
	 */
	public Context setPeerStake(AccountKey peerKey, Address controller, long newStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(peerKey);
		if (ps==null) return withError(ErrorCodes.STATE,"Peer does not exist for account key: "+peerKey);
		if (newStake<0) return this.withArgumentError("Cannot set a negative stake");
		if (newStake>Constants.MAX_SUPPLY) return this.withArgumentError("Target stake out of valid Amount range");
	
		if (!ps.getController().equals(controller)) return withError(ErrorCodes.STATE,"Address "+controller+" is not the controller of this peer account");
		
		long balance=getBalance(controller);
		long currentStake=ps.getPeerStake();
		long delta=newStake-currentStake;
		
		if (delta==0) return this; // no change
		
		// need to check sufficient balance if increasing stake
		if (delta>balance) return this.withFundsError("Insufficient balance ("+balance+") to increase Peer Stake to "+newStake);

		// Final updates assuming everything OK. Hopefully everything balances. SECURITY: test this. A lot.
		PeerStatus updatedPeer=ps.withPeerStake(newStake);
		s=s.withBalance(controller, balance-delta); // adjust own balance
		s=s.withPeer(peerKey, updatedPeer); // adjust peer

		return withState(s).withResult(CVMLong.create(delta));
	}


	/**
	 * Creates a new peer with the specified stake.
	 * The accountKey must not be in the list of peers.
	 * The accountKey must be assigned to the current transaction address
	 * Stake must be greater than 0.
	 * Stake must be less than to the account balance.
	 *
	 * @param accountKey Peer Account key to create the PeerStatus
	 * @param initialStake Initial stake amount
	 * @return Context with final take set
	 */
	public Context createPeer(AccountKey accountKey, long initialStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(accountKey);
		if (ps!=null) return withError(ErrorCodes.STATE,"Peer already exists for this account key: "+accountKey.toChecksumHex());
		if (initialStake == 0) return this.withArgumentError("Cannot create a peer with zero stake");
		if (!Coin.isValidAmount(initialStake)) return this.withArgumentError("Target stake out of valid Amount range: "+initialStake);

		Address myAddress=getAddress();

		long balance=getBalance(myAddress);
		if (initialStake>balance) return this.withFundsError("Insufficient balance ("+balance+") to assign an initial stake of "+initialStake);

		PeerStatus newPeerStatus = PeerStatus.create(myAddress, initialStake);

		// Final updates. Hopefully everything balances. SECURITY: test this. A lot.
		s=s.withBalance(myAddress, balance-initialStake); // adjust own balance
		s=s.withPeer(accountKey, newPeerStatus); // add peer
		return withState(s);
	}
	
	public Context evictPeer(AccountKey peerKey) {
		Context ctx=this;
		Index<AccountKey, PeerStatus> peers = ctx.getState().getPeers();
		PeerStatus ps=peers.get(peerKey);
		if (ps==null) {
			// no peer to evict
			return this;
		}

		// Check if we are allowed to evict this peer
		Address controller=ps.getController();
		if (Utils.equals(ctx.getAddress(),ps.getController())) {
			// OK
		} else {
			if (ps.getPeerStake()>=CPoSConstants.MINIMUM_EFFECTIVE_STAKE) {
				return ctx.withError(ErrorCodes.STATE,"Peer has too much stake to be evicted");
			}
		}
		if (peers.count()==1) {
			return ctx.withError(ErrorCodes.STATE,"Cannot evict last peer");
		}
		
		// Refund delegated stakes to address in this Index. Stakes are shares, not balances!
		Index<Address, CVMLong> stakes = ps.getStakes();
		long ns=stakes.count();
		for (int i=0; i<ns; i++) {
			// SECURITY: update juice limit while evicting delegated stakes
			// This is safe(ish) because we are only moving / deleting stuff
			// so a sufficiently endowed account should always be able to evict a peer
			MapEntry<Address,CVMLong> staked=stakes.entryAt(i);
			ctx=ctx.withJuiceLimit(getJuiceLimit()+Juice.TRANSFER);
			ctx=ctx.consumeJuice(Juice.TRANSFER);
			Address stakedAddress=staked.getKey();
			ctx=ctx.setDelegatedStake(peerKey, stakedAddress, 0L);
			if (ctx.isExceptional()) return ctx;
		}
		
		// Controller stake refund
		ctx=ctx.setPeerStake(peerKey, controller,0);
		if (ctx.isExceptional()) return ctx;
		ACell peerStake=ctx.getResult();
		
		// Finally remove peer record from state
		State s=ctx.getState();
		ctx=ctx.withState(s.withPeers(s.getPeers().dissoc(peerKey)));
		
		return ctx.withResult(peerStake);
	}

	/**
	 * Sets peer data.
	 * 
	 * @param peerKey Peer to set data for
	 * @param data Map of data to set for the peer
	 * @return Context with final peer data set
	 */
	public Context setPeerData(AccountKey peerKey, AHashMap<ACell, ACell> data) {
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
		// TODO: should use complete Map
		// at the moment only :url is used in the data map
		AHashMap<ACell,ACell> newMeta=data;
		PeerStatus updatedPeer=ps.withPeerData(newMeta);
		s=s.withPeer(ak, updatedPeer); // adjust peer
		
		// if no change just return the current context
		if (lastStateHash.equals(s.getHash())){
			return this;
		}
		return withState(s);
	}
	


	/**
	 * Sets the holding for a specified target account. Returns NOBODY exception if account does not exist.
	 * @param targetAddress Account address at which to set the holding
	 * @param value Value to set for the holding.
	 * @return Updated context
	 */
	public Context setHolding(Address targetAddress, ACell value) {
		AccountStatus as=getAccountStatus();
		as=as.withHolding(targetAddress, value);
		return withAccountStatus(getAddress(),as);
	}

	/**
	 * Sets the controller for the current Account
	 * @param address New controller Address / scoped reference
	 * @return Context with current Account controller set
	 */
	public Context setController(ACell address) {
		AccountStatus as=getAccountStatus();
		as=as.withController(address);
		return withAccountStatus(getAddress(),as);

	}
	
	/**
	 * Sets the parent for the current Account
	 * @param address New parent Address
	 * @return Context with current Account parent set
	 */
	public Context setParent(Address address) {
		AccountStatus as=getAccountStatus();
		as=as.withParent(address);
		return withAccountStatus(getAddress(),as);
	}

	/**
	 * Sets the public key for the current account
	 * @param publicKey New Account Public Key
	 * @return Context with current Account Key set
	 */
	public Context setAccountKey(AccountKey publicKey) {
		AccountStatus as=getAccountStatus();
		as=as.withAccountKey(publicKey);
		return withAccountStatus(getAddress(),as);
	}

	protected Context withAccountStatus(Address target, AccountStatus accountStatus) {
		return withState(getState().putAccount(target, accountStatus));
	}

	/**
	 * Switches the context to a new address, creating a new execution context. Suitable for testing.
	 * @param newAddress New Address to use.
	 * @return Result type of new Context
	 */
	public Context forkWithAddress(Address newAddress) {
		return create(getState(),newAddress);
	}

	/**
	 * Forks this context, creating a new copy of all local state but clears any exceptional value
	 * @return A new forked Context
	 */
	public Context fork() {
		return new Context(chainState, juice, juiceLimit, localBindings,result, depth,NO_EXCEPTION,log, compilerState);
	}
	
	/**
	 * Appends a log entry for the current address.
	 * @param values Values to log
	 * @return Updated Context
	 */
	public Context appendLog(AVector<ACell> values) {
		Address addr=getAddress();
		ACell scope=getScope();
		AVector<AVector<ACell>> log=this.log;
		if (log==null) {
			log=Vectors.empty();
		}
		AVector<CVMLong> location=getLocation();
		AVector<ACell> entry = Vectors.of(addr,scope,location,values);
		log=log.conj(entry);

		this.log=log;
		return this;
	}

	/**
	 * Gets the log map for the current context.
	 *
	 * @return Index of addresses to log entries created in the course of current execution context.
	 */
	public AVector<AVector<ACell>> getLog() {
		if (log==null) return Vectors.empty();
		return log;
	}

	public Context lookupCNS(String name) {
		Context ctx=this.fork();
		ctx=this.actorCall(Init.REGISTRY_ADDRESS, 0, Symbols.CNS_RESOLVE, Symbol.create(name));

		return ctx;
	}

	/**
	 * Expands a form with the default *initial-expander*
	 * @param form Form to expand
	 * @return Syntax Object resulting from expansion.
	 */
	public Context expand(ACell form) {
		return expand(Core.INITIAL_EXPANDER, form, Core.INITIAL_EXPANDER);
	}

	public Context expand(AFn<?> expander, ACell form, AFn<?> cont) {
		// execute with adjusted depth
		int savedDepth=getDepth();
		Context ctx = this.withDepth(savedDepth+1);
		if (ctx.isExceptional()) return ctx; // depth error, won't have modified depth

		//AVector<ACell> savedEnv=getLocalBindings();

		Context rctx= invoke(expander, form, cont);

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
		ACell expBool = me.get(Keywords.EXPANDER_META);
		if (RT.bool(expBool)) {
			// expand form using specified expander and continuation expander
			ACell v = lookupValue(addr,sym);
			AFn<ACell> expander = RT.castFunction(v);
			if (expander != null) return expander;
		}
		return null;
	}

	/**
	 * Gets the peer responsible for the current block
	 * @return Peer key, or null if outside a peer created block
	 */
	public AccountKey getPeer() {
		return chainState.getPeer();
	}

	/**
	 * Gets the most recent log entry, or null if not available.
	 * @return
	 */
	public AVector<ACell> lastLog() {
		AVector<AVector<ACell>> log=getLog();
		long n=log.count();
		if (n==0) return null;
		return log.get(n-1);
	}

	public Context withTransactionContext(TransactionContext tctx) {
		return withChainState(chainState.withTransactionContext(tctx));
	}

	public AVector<CVMLong> getLocation() {
	
		return chainState.txContext.getLocation();
	}

	public AccountKey getSigner() {
		SignedData<ATransaction> sd=chainState.getTransactionContext().tx;
		if (sd==null) return null;
		return sd.getAccountKey();
	}



}
