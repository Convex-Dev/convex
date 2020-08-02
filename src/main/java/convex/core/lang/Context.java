package convex.core.lang;

import java.util.concurrent.ExecutionException;

import convex.core.Constants;
import convex.core.ErrorType;
import convex.core.Init;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ABlobMap;
import convex.core.data.AHashMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.BlobMap;
import convex.core.data.IObject;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.exceptions.TODOException;
import convex.core.lang.expanders.AExpander;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.ErrorValue;
import convex.core.lang.impl.HaltValue;
import convex.core.lang.impl.RollbackValue;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Immutable representation of on-chain execution context.
 * 
 * Execution context includes:
 * - The current on-Chain state, including the defined execution environment for each Address
 * - Local lexical bindings for the current execution position
 * - The identity (as an Address) for the origin, caller and currently executing actor
 * - Juice and execution depth current status for
 * - Result of the last operation executed (which may be exceptional)
 * 
 * Interestingly, this behaves like Scala's ZIO[<Context-Stuff>, AExceptional, T]
 * 
 * Contexts maintain execution depth and juice to control against arbitrary on-chain
 * execution. Coupled with limits on total juice and limits on memory allocation
 * per unit juice, this places an upper bound on execution time and space.
 * 
 * Contexts also support returning exceptional values. Exceptional results may come 
 * from arbitrary nested depth (which requires a bit of complexity to reset depth when
 * catching exceptional values). We avoid using Java exceptions here, because exceptionals
 * are "normal" in the context of on-chain execution, and we'd like to avoid the overhead
 * of exception handling - may be especially important in DoS scenarios.
 * 
 * "If you have a procedure with 10 parameters, you probably missed some" 
 * - Alan Perlis
 */
public class Context<T> implements IObject {

	private static final long MAX_DEPTH = 256;
	
	// private static final Logger log=Logger.getLogger(Context.class.getName());

	
	/*
	 *  Frequently changing fields during execution. Might consider mutability later,
	 *  but the key idea is that it is very cheap to throw away short-lived Contexts 
	 *  because the JVM GC generational GC will just sweep them up shortly afterwards.
	 */
	
	private final long juice;
	private final T result;
	private final int depth;
	private final AHashMap<Symbol,Object> localBindings;	
	private final boolean isExceptional;
	private final ChainState chainState;
	
	/**
	 * Inner class for less-frequently changing state related to Actor execution
	 * Should save some allocation / GC on average, since it will change less 
	 * frequently than the surrounding context and can be cheaply copied by reference.
	 * 
	 * SECURITY: security critical, since it determines the current address
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
		private final AHashMap<Symbol, Syntax> environment;
		
		private ChainState(State state, Address origin,Address caller, Address address,AHashMap<Symbol, Syntax> environment, long offer) {
			this.state=state;
			this.origin=origin;
			this.caller=caller;
			this.address=address;	
			this.environment=environment;
			this.offer=offer;
		}
		
		public static ChainState create(State state, Address origin, Address caller, Address address, long offer) {
			AHashMap<Symbol, Syntax> environment=Core.ENVIRONMENT;
			if (address!=null) {
				AccountStatus as=state.getAccount(address);
				if (as!=null) environment=as.getEnvironment();
			}
			return new ChainState(state,origin,caller,address,environment,offer);
		}

		private ChainState withStore(ASet<Object> store) {
			State newState=state.withStore(store);
			return withState(newState);
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
		private AHashMap<Symbol, Syntax> getEnvironment() {
			return environment;
		}

		private ChainState withEnvironment(AHashMap<Symbol, Syntax> newEnvironment)  {
			AccountStatus as=state.getAccount(address);
			AccountStatus nas=as.withEnvironment(newEnvironment);
			State newState=state.putAccount(address,nas);
			return withState(newState);
		}

		private ChainState withAccounts(BlobMap<Address, AccountStatus> newAccounts) {
			return withState(state.withAccounts(newAccounts));
		}
	}

	private Context(ChainState chainState, long juice, AHashMap<Symbol,Object> localBindings, T result,int depth, boolean isExceptional) {
		this.chainState=chainState;
		this.juice=juice;
		this.localBindings=localBindings;
		this.result=result;
		this.depth=depth;
		this.isExceptional=isExceptional;
	}
	
	private static <T> Context<T> create(ChainState cs, long juice, AHashMap<Symbol, Object> localBindings, T result, int depth) {
		if (juice<0) throw new IllegalArgumentException("Negative juice! "+juice);
		return new Context<T>(cs,juice,localBindings,result,depth,(result instanceof AExceptional));
	}
	
	private static <T> Context<T> create(State state, long juice,AHashMap<Symbol, Object> localBindings, T result, int depth, Address origin,Address caller, Address address, long offer) {
		ChainState chainState=ChainState.create(state,origin,caller,address,offer);
		return create(chainState,juice,localBindings,result,depth);
	}
	
	private static <T> Context<T> create(State state, long juice,AHashMap<Symbol, Object> localBindings, T result, int depth, Address origin,Address caller, Address address) {
		ChainState chainState=ChainState.create(state,origin,caller,address,0L);
		return create(chainState,juice,localBindings,result,depth);
	}
		
	/**
	 * Creates an execution context with a default actor address. 
	 * 
	 * Useful for Testing
	 * 
	 * @param state
	 * @return Fake context
	 */
	public static <R> Context<R> createFake(State state) {
		return createFake(state,Init.HERO);
	}
	
	/**
	 * Creates a "fake" execution context for the given actor address.
	 * 
	 * Not valid for use in real transactions, but can be used to 
	 * compute stuff off-chain "as-if" the actor made the call.
	 * 
	 * @param state
	 * @param oracleAddress
	 * @return Fake context
	 */
	public static <R> Context<R> createFake(State state, Address actor) {
		if (actor==null) throw new Error("Null actor address!");
		return create(state,Constants.MAX_TRANSACTION_JUICE,Maps.empty(),null,0,actor,null,actor);
	}
	
	/**
	 * Creates an initial execution context with the specified actor as origin, and reserving the appropriate 
	 * amount of juice.
	 * 
	 * Juice limit is extracted from the actor's current balance.
	 * 
	 * @param <T>
	 * @param state
	 * @param juice
	 * @return Initial execution context with reserved juice.
	 * @throws NoAccountException 
	 */
	public static <T> Context<T> createInitial(State state, Address actor,long juice) {
		AccountStatus as=state.getAccounts().get(actor);
		if (as==null) {
			// no account
			return Context.createFake(state).withError(ErrorType.NOBODY);
		}
		
		long juicePrice=state.getJuicePrice();
		long reserve=juicePrice*juice;
		if (!as.hasBalance(reserve)) {
			// insufficient balance to fund juice supply
			return Context.createFake(state).withError(ErrorType.FUNDS);
		}
		Amount newBalance=as.getBalance().subtract(Amount.create(reserve));
		as=as.withBalance(newBalance);
		state=state.putAccount(actor, as);
		return create(state,juice,Maps.empty(),null,0,actor,null,actor);
	}
	


	
	/**
	 * Performs key actions at the end of a transaction:
	 * <ul>
	 * <li>Refunds juice</li>
	 * <li>Accumulates used juice fees in globals</li>
	 * </ul>
	 * 
	 * @param totalJuice total juice reserved at start of transaction
	 * @param juicePrice juice price for transaction
	 * @return Updated context
	 */
	public Context<T> completeTransaction(long totalJuice, long juicePrice) {
		State state=getState();
		long remainingJuice=Math.max(0L, juice);
		long usedJuice=totalJuice-remainingJuice;
		assert(usedJuice>=0);
	
		// maybe refund remaining juice
		if (remainingJuice>0L) {
			// Compute refund. Shouldn't be possible to overflow?
			// But do a paranoid checked multiply just in case
			long refund=Math.multiplyExact(remainingJuice,juicePrice);
			
			Address address=getAddress();
			state=state.withBalance(address,state.getBalance(address).add(refund));
		}
		
		// maybe add used juice to miner fees
		if (usedJuice>0L) {
			long juiceFees = usedJuice*juicePrice;
			long oldFees=(long)state.getGlobal(Symbols.FEES);
			long newFees=oldFees+juiceFees;
			state=state.withGlobal(Symbols.FEES,newFees);
		}
		
		return withState(state);
	}
	
	@SuppressWarnings("unchecked")
	private <R> Context<R> withState(State newState) {
		return (Context<R>) this.withChainState(chainState.withState(newState));
	}
	
	/**
	 * Get the latest state from this Context
	 * @return State instance
	 */
	public State getState() {
		return chainState.state;
	}
	
	public long getJuice() {
		return juice;
	}
	
	public long getOffer() {
		return chainState.getOffer();
	}
	
	public AHashMap<Symbol,Syntax> getEnvironment() {
		return chainState.getEnvironment();
	}

	/**
	 * Consumes juice, returning an updated context or exceptional JUICE error.
	 * @param <R>
	 * @param gulp
	 * @return Updated context with juice consumed
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> consumeJuice(long gulp) {
		if(!checkJuice(gulp)) return withJuiceError();
		long newJuice=juice-gulp;
		return new Context<R>(chainState,newJuice,localBindings,(R) result,depth,isExceptional);
	}
	
	public boolean checkJuice(long gulp) {
		assert(gulp>0);
		if (gulp>juice) {
			return false;
		}
		return true;
	}
	
	/**
	 * Looks up a local entry in the current execution context. 
	 * 
	 * @param <R> Type of value associated with the given symbol
	 * @param sym
	 * @return MapEntry for the given symbol in the current context, or null if not defined as a local
	 */
	@SuppressWarnings("unchecked")
	public <R> MapEntry<Symbol,R> lookupLocalEntry(Symbol sym) {
		MapEntry<Symbol,R> me = (MapEntry<Symbol, R>) localBindings.getEntry(sym);
		return me;
	}
	
	/**
	 * Looks up a symbol in the current execution context, without consuming any juice
	 * 
	 * @param <R> Type of value associated with the given symbol
	 * @param sym Symbol to look up
	 * @return Context with the result of the lookup (may be an undeclared exception)
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> lookup(Symbol symbol) {
		// first try lookup in local bindings
		MapEntry<Symbol,T> le=lookupLocalEntry(symbol);
		if (le!=null) return (Context<R>) withResult(le.getValue());
		
		// second try lookup in dynamic environment
		MapEntry<Symbol,Syntax> de=lookupDynamicEntry(symbol);
		if (de!=null) return withResult(de.getValue().getValue());
		
		// finally fallback to special symbol lookup
		return lookupSpecial(symbol);
	}
	
	/**
	 * Looks up an environment entry in the current dynamic environment without consuming juice.
	 * 
	 * If the symbol is qualified, try lookup via *aliases*
	 * 
	 * @param sym Symbol to look up
	 * @return
	 */
	public MapEntry<Symbol,Syntax> lookupDynamicEntry(Symbol sym) {
		AccountStatus as=getAccountStatus();
		return lookupDynamicEntry(as,sym);
	}
	
	/**
	 * Looks up an environment entry for a specific address without consuming juice.
	 * 
	 * If the symbol is qualified, try lookup via *aliases*
	 * 
	 * @param sym Symbol to look up
	 * @return
	 */
	public MapEntry<Symbol,Syntax> lookupDynamicEntry(Address address,Symbol sym) {
		AccountStatus as=getAccountStatus(address);
		if (as==null) return null;
		return lookupDynamicEntry(as,sym);
	}
	
	private MapEntry<Symbol,Syntax> lookupDynamicEntry(AccountStatus as,Symbol sym) {
		// Get environment for Address, or default to initial environment
		AHashMap<Symbol, Syntax> env = (as==null)?Core.ENVIRONMENT:as.getEnvironment();
		
		MapEntry<Symbol,Syntax> result=env.getEntry(sym);
		
		if (result==null) {
			Symbol alias=sym.getNamespace();
			AccountStatus aliasAccount=getAliasedAccount(env,alias);
			result = lookupAliasedEntry(aliasAccount,sym);
		}
		return result;
	}
	
	private MapEntry<Symbol,Syntax> lookupAliasedEntry(AccountStatus as,Symbol sym) {
		if (as==null) return null;
		Symbol unqualified=sym.toUnqualified();
		AHashMap<Symbol, Syntax> env = as.getEnvironment();
		return env.getEntry(unqualified);
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
	 * @param alias 
	 * @return AccountStatus for the aliased, or null if not present
	 */
	@SuppressWarnings("unchecked")
	private AccountStatus getAliasedAccount(AHashMap<Symbol, Syntax> env, Symbol alias) {
		// Check for *aliases* entry. Might not exist.
		Object maybeAliases=env.get(Symbols.STAR_ALIASES);
		if (maybeAliases==null) return null;
		
		Object aliasesValue=((Syntax)maybeAliases).getValue();
		if ((env==null)||(!(aliasesValue instanceof AHashMap))) return null; 
		
		AHashMap<Symbol,Object> aliasMap=((AHashMap<Symbol,Object>)aliasesValue);
		Object value=aliasMap.get(alias);
		if (!(value instanceof Address)) return null;
		
		return getAccountStatus((Address)value);
	}

	/**
	 * Looks up a special symbol in the environment
	 * @param <R>
	 * @param sym
	 * @return Context with value or special symbol, or undeclared exception if not found.
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> lookupSpecial(Symbol sym)  {
		if (sym.getName().charAt(0)==Symbols.SPECIAL_STAR) {
			if (sym.equals(Symbols.STAR_JUICE)) return (Context<R>) withResult(getJuice());
			if (sym.equals(Symbols.STAR_CALLER)) return (Context<R>) withResult(getCaller());
			if (sym.equals(Symbols.STAR_ADDRESS)) return (Context<R>) withResult(getAddress());
			if (sym.equals(Symbols.STAR_BALANCE)) return (Context<R>) withResult(getBalance());
			if (sym.equals(Symbols.STAR_ORIGIN)) return (Context<R>) withResult(getOrigin());
			if (sym.equals(Symbols.STAR_RESULT)) return (Context<R>) this;
			if (sym.equals(Symbols.STAR_TIMESTAMP)) return (Context<R>) withResult(getState().getTimeStamp());
			if (sym.equals(Symbols.STAR_DEPTH)) return (Context<R>) withResult((long)getDepth());
			if (sym.equals(Symbols.STAR_OFFER)) return (Context<R>) withResult((long)getOffer());
			if (sym.equals(Symbols.STAR_STATE)) return (Context<R>) withResult(getState());
			if (sym.equals(Symbols.STAR_HOLDINGS)) return (Context<R>) withResult(getHoldings());
		}
		return withError(ErrorType.UNDECLARED,sym.toString());
	}

	/**
	 * Gets the holdings map for the current account.
	 * @return Map of holdings, or null if the current account does not exist.
	 */
	public ABlobMap<Address,Object> getHoldings() {
		AccountStatus as=getAccountStatus(getAddress());
		if (as==null) return null;
		return as.getHoldings();
	}

	private long getBalance() {
		return getBalance(getAddress());
	}
	
	public long getBalance(Address address) {
		AccountStatus as=getAccountStatus(address);
		if (as==null) return 0L;
		return as.getBalance().getValue();
	}

	/**
	 * Gets the caller of the currently executing context. 
	 * 
	 * Will be null if this context was not called from elsewhere (e.g. is an origin context)
	 * @return
	 */
	public Address getCaller() {
		return chainState.caller;
	}

	/**
	 * Gets the address of the currently executing Actor, or the address of the
	 * account that executed this transaction if no Actors have been called.
	 * 
	 * @return Address of the current account
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
		if (isExceptional) {
			throw new Error("Can't get result with exceptional value: "+result);
		}
		return result;
	}
	
	/**
	 * Gets the resulting value from this context. May be either exceptional or a normal result.
	 * @return Either the normal result, or an AExceptional instance
	 */
	public T getValue() {
		return result;
	}
	
	/**
	 * Gets the exceptional value from this context. Throws an Error is the context return value is normal.
	 * @return an AExceptional instance
	 */
	public AExceptional getExceptional() {
		if (!isExceptional) throw new Error("Can't get exceptional value for context with result: "+result);
		return (AExceptional) result;
	}

	/**
	 * Returns a context updated with the specified result. 
	 * 
	 * Context may become exceptional depending on the result type.
	 * 
	 * @param <R>
	 * @param value
	 * @return Context updated with the specified result.
	 */
	public <R> Context<R> withResult(R value) {
		return withResult(0,value);
	}
	
	public <R> Context<R> withResult(R result, AHashMap<Symbol, Object> restoredBindings) {
		return create(chainState,juice,restoredBindings,result,depth);
	}
	
	@SuppressWarnings("unchecked")
	public <R extends X, X> Context<X> withResult(long gulp,R value) {
		long newJuice=juice-gulp;
		if (newJuice<0) return withJuiceError();
		
		if ((this.result==value)&&(this.juice==newJuice)) return (Context<X>) this;
		return new Context<X>(chainState,newJuice,localBindings,value,depth,false);
	}
	
	/**
	 * Returns this context with a JUICE error, consuming all juice.
	 * @param <R>
	 * @return Exceptional Context signalling JUICE error.
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> withJuiceError() {
		AExceptional err=ErrorValue.create(ErrorType.JUICE);
		return (Context<R>) new Context<>(chainState,0L,localBindings,err,depth,true);
	}
	
	@SuppressWarnings("unchecked")
	public <R> Context<R> withException(AExceptional exception) {
		return (Context<R>) new Context<AExceptional>(chainState,juice,localBindings,exception,depth,true);
	}
	
	@SuppressWarnings("unchecked")
	public <R> Context<R> withException(long gulp,AExceptional value) {
		long newJuice=juice-gulp;
		if (newJuice<0) return withJuiceError();
		assert(value instanceof AExceptional);
		
		if ((this.result==value)&&(this.juice==newJuice)) return (Context<R>) this;
		return (Context<R>) new Context<AExceptional>(chainState,newJuice,localBindings,value,depth,true);
	}
	
	/**
	 * Updates the environment of this execution context. This changes the environment stored in the
	 * state for the current Address.
	 * 
	 * @param newEnvironment
	 * @return Updated Context with the given dynamic environment
	 */
	private Context<T> withEnvironment(AHashMap<Symbol, Syntax> newEnvironment)  {
		ChainState cs=chainState.withEnvironment(newEnvironment);
		return withChainState(cs);	
	}
	
	public Context<T> withStore(ASet<Object> store) {
		ChainState cs=chainState.withStore(store);
		return withChainState(cs);
	}
	
	private Context<T> withChainState(ChainState newChainState) {
		if (chainState==newChainState) return this;
		return create(newChainState,juice,localBindings,result,depth);
	}
	
	/**
	 * Executes an Op within this context, returning an updated context.
	 * 
	 * @param <I> Return type of the Op
	 * @param op Op to execute
	 * @return Updated Context
	 */
	@SuppressWarnings("unchecked")
	public <I> Context<I> execute(AOp<I> op) {			
		// execute op with adjusted depth
		final Context<T> ctx=this.adjustDepth(1);
		if (ctx.isExceptional()) return (Context<I>) ctx;
		
		Context<I> rctx=op.execute(ctx);
		
		// reset depth after execution. We can't just decrease depth, because return value may be exceptional.
		// TODO: is there a smarter way? Perhaps only reset depth on normal return
		if (!rctx.isExceptional()) {
			rctx=rctx.withDepth(this.depth);
		}
		
		return rctx;
	}
	
	/**
	 * Executes an Op at the top level. Handles top level halt, recur and return. 
	 * 
	 * Returning an updated context containing the result or an exceptional error.
	 * 
	 * @param <I> Return type of the Op
	 * @param op Op to execute
	 * @return Updated Context
	 */
	public <R> Context<R> run(AOp<R> op) {			
		Context<R> ctx=execute(op);
		
		// must handle state results like halt, rollback etc.
		return handleStateResults(ctx);
	}
	
	/**
	 * Invokes a function within this context, returning an updated context. 
	 * 
	 * Keeps depth constant upon return.
	 * 
	 * @param <R> Return type of the function
	 * @param fn Function to execute
	 * @return Updated Context
	 */
	public <R> Context<R> invoke(IFn<R> fn, Object[] args) {
		// Note: we don't adjust depth here because execute(...) does it for us in the function body
		Context<R> ctx = fn.invoke(this,args);

		if (ctx.isExceptional()) {
			AExceptional ex=ctx.getExceptional();
			if (ex instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)ex;
				ev.addTrace("In function: "+fn.toString());
			}
		}
		return ctx;
	}
	
	/**
	 * Execute an op, and bind the result to the given binding form in the lexical environment
	 * 
	 * Binding form may be a destructuring form
	 * @param bindingForm
	 * @param op
	 * 
	 * @param <I>
	 * @return Context with local bindings updated
	 * @throws ExecutionException
	 */
	public <I> Context<I> executeLocalBinding(Object bindingForm, AOp<I> op) {
		Context<I> ctx=this.execute(op);
		if (ctx.isExceptional()) return ctx;
		return (Context<I>) ctx.updateBindings(bindingForm, ctx.getResult());
	}
	
	/**
	 * Updates local bindings with a given binding form
	 * 
	 * @param bindingForm
	 * @param args
	 * @return Context with local bindings updated
	 * @throws ExecutionException
	 */
	@SuppressWarnings("unchecked")
	public Context<T> updateBindings(Object bindingForm, Object args) {
		Context<T> ctx=this;
		
		if (bindingForm instanceof Syntax) bindingForm=((Syntax)bindingForm).getValue();
		
		if (bindingForm instanceof Symbol) {
			Symbol sym=(Symbol)bindingForm;
			if (sym.equals(Symbols.UNDERSCORE)) return this;
			if (sym.isQualified()) return ctx.withCompileError("Can't create local binding for qualified symbol: "+sym);
			return withLocalBindings( localBindings.assoc(sym,args));
		} else if (bindingForm instanceof AVector) {
			AVector<Syntax> v=(AVector<Syntax>)bindingForm;
			long bindCount=v.count(); // count of binding form symbols (may include & etc.)
			long argCount=RT.count(args);
						
			boolean foundAmpersand=false;
			for (long i=0; i<bindCount; i++) {
				// get datum for syntax element in binding form
				Object bf=v.get(i).getValue(); 
				
				if (Symbols.AMPERSAND.equals(bf)) {
					if (foundAmpersand) return ctx.withCompileError("Can't bind two or more ampersands n single binding vector");
					
					long nLeft=bindCount-i-2; // number of following bindings should be zero in usual usage [... & more]
					if (nLeft<0) return ctx.withCompileError("Can't bind ampersand at end of binding form");
					
					// bind variadic form at position i+1 to all args except nLeft
					AVector<Object> rest=RT.vec(args).slice(i,(argCount - i)-nLeft);
					ctx= ctx.updateBindings(v.get(i+1), rest);
					
					// mark ampersand as found, and skip to next binding form (i.e. past the variadic symbol following &)
					foundAmpersand=true;
					i++;
				} else {
					// just a regular binding
					long argIndex=foundAmpersand?(argCount-(bindCount-i)):i;
					if (argIndex>=argCount) return ctx.withArityError("Insufficient arguments ("+argCount+") for binding form: "+bindingForm);
					ctx=ctx.updateBindings(bf,RT.nth(args, argIndex));
				}
			}
			
			// at this point, should have consumed all bindings
			if (!foundAmpersand) {
				if (bindCount!=argCount) {
					return ctx.withArityError("Expected "+bindCount+" arguments but got "+argCount+" for binding form: "+bindingForm);
				}
			}
		} else {
			throw new TODOException("Don't understand binding form: "+bindingForm);
		}
		// return after clearing result. Don't want to be exceptional....
		return ctx.withResult(null);
	}

	@Override
	public void ednString(StringBuilder sb)  {
		sb.append("#context {");
		sb.append(":juice "+juice);
		sb.append(',');
		sb.append(":result "+Utils.ednString(result));
		sb.append(',');
		sb.append(":state ");
		getState().ednString(sb);
		sb.append("}");
	}

	public convex.core.data.AHashMap<Symbol, Object> getLocalBindings() {
		return localBindings;
	}

	@SuppressWarnings("unchecked")
	public <R> Context<R> withLocalBindings(AHashMap<Symbol, Object> newBindings) {
		if (localBindings==newBindings) return (Context<R>) this;
		return create(chainState,juice,newBindings,(R)result,depth);
	}

	/**
	 * Gets the account status record, or null if not found
	 * 
	 * @param address Address of account
	 * @return AccountStatus for the specified address, or null if the account does not exist
	 */
	public AccountStatus getAccountStatus(Address address) {
		return getState().getAccounts().get(address);
	}

	public int getDepth() {
		return depth;
	}

	public Address getOrigin() {
		return chainState.origin;
	}

	/**
	 * Defines a value in the environment of the current address
	 * @param key
	 * @param value
	 * @return Updated context with symbol defined in environment
	 */
	public Context<T> define(Symbol key, Syntax value) {
		AHashMap<Symbol, Syntax> m = getEnvironment();
		AHashMap<Symbol, Syntax> newEnvironment = m.assoc(key, value);
		
		return withEnvironment(newEnvironment);
	}

	/**
	 * Expand and compile a form in this Context. 
	 * 
	 * @param <R> Return type of compiled op
	 * @param form
	 * @return Updated Context with compiled Op as result
	 * @throws ExecutionException
	 */
	public <R> Context<AOp<R>> expandCompile(Object form) {
		// run compiler with adjusted depth
		Context<AOp<R>> rctx =this.adjustDepth(1);
		if (rctx.isExceptional()) return rctx;
		
		// EXPAND AND COMPILE
		rctx = Compiler.expandCompile(form, rctx);
		
		// reset depth after expansion and compilation, unless there is an error
		if (!rctx.isExceptional()) rctx=rctx.withDepth(depth);
		
		return rctx;
	}
	
	/**
	 * Compile a form in this Context. Form must already be fully expanded to a Syntax Object
	 * 
	 * @param <R> Return type of compiled op
	 * @param expandedForm
	 * @return Updated Context with compiled Op as result
	 * @throws ExecutionException
	 */
	public <R> Context<AOp<R>> compile(Syntax expandedForm) {
		// run compiler with adjusted depth
		Context<AOp<R>> rctx =this.adjustDepth(1);
		if (rctx.isExceptional()) return rctx;
		
		// COMPILE
		rctx = Compiler.compile(expandedForm, rctx);
		
		if (rctx.isExceptional()) {
			AExceptional ex=rctx.getExceptional();
			if (ex instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)ex;
				ev.addTrace("Compiling: syntax object with datum of type "+Utils.getClassName(expandedForm.getValue()));
			}
		} else {
			rctx=rctx.withDepth(depth);
		}
		
		return rctx;
	}
	
	/**
	 * Expand a form in this context.
	 * 
	 * @param form
	 * @return Updated Context with expanded form as result.
	 * @throws ExecutionException
	 */
	public Context<Syntax> expand(Object form) {
		// run expansion phase with adjusted depth
		Context<Syntax> rctx =this.adjustDepth(1);
		if (rctx.isExceptional) return rctx;
		
		rctx = Compiler.expand(form, rctx);
		
		if (rctx.isExceptional()) {
			
		} else {
			rctx=rctx.withDepth(depth);
		}
		
		return rctx;
	}
	
	/**
	 * Expand a form in this context, using the given expander and continuation expander
	 * 
	 * @param form
	 * @return Context with expanded syntax as result
	 * @throws ExecutionException
	 */ 
	public Context<Syntax> expand(Object form, AExpander expander, AExpander cont) {
		// Adjusted depth
		Context<Syntax> rctx =this.adjustDepth(1);
		if (rctx.isExceptional) return rctx;
		
		// EXPAND
		rctx = expander.expand(form, cont, rctx);
		
		if (rctx.isExceptional()) {
			AExceptional ex=rctx.getExceptional();
			if (ex instanceof ErrorValue) {
				ErrorValue ev=(ErrorValue)ex;
				ev.addTrace("Expanding with: "+Utils.toString(expander));
			}
		} else {
			rctx=rctx.withDepth(depth);
		}
		
		return rctx;
	}
	
	/**
	 * Expands, compile and executes a form in the current context. 
	 * 
	 * @param <R> Return type of evaluation
	 * @param form
	 * @return Context containing the result of evaluating the specified form
	 * @throws ExecutionException
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> eval(Object form) {
		Context<AOp<R>> compiledContext=expandCompile(form);
		if (compiledContext.isExceptional()) return (Context<R>) compiledContext;
		AOp<R> op=compiledContext.getResult();
		return compiledContext.execute(op);
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
	 * @throws ExecutionException
	 */
	public <R> Context<AVector<AOp<R>>> compileAll(ASequence<Syntax> forms) {
		Context<AVector<AOp<R>>> rctx = Compiler.compileAll(forms, this);
		return rctx;
	}

	public <R> Context<R> adjustDepth(int delta) {
		int newDepth=Math.addExact(depth,delta);
		return withDepth(newDepth);
	}
	
	@SuppressWarnings("unchecked")
	public <R> Context<R> withDepth(int newDepth) {
		if (newDepth==depth) return (Context<R>) this;
		if (newDepth>MAX_DEPTH) return withError(ErrorType.DEPTH);
		return new Context<R>(chainState,juice,localBindings,(R) result,newDepth,isExceptional);
	}
	
	/**
	 * Tests if this context holds an exceptional result.
	 * 
	 * Ops should cancel and return exceptional results unchanged, unless they can handle them.
	 * @return true if context has an exceptional value, false otherwise
	 */
	public boolean isExceptional() {
		return isExceptional;
	}

	/**
	 * Transfers funds from the current address to the target.
	 * 
	 * Uses no juice
	 * 
	 * @param target Target Address, will be created if does not already exist.
	 * @param amount Amount to transfer, must be between 0 and Amount.MAX_VALUE inclusive
	 * @return The remaining balance for this address.
	 * @throws ExecutionException
	 */
	public Context<Long> transfer(Address target, long amount) {
		if (amount<0) return withError(ErrorType.ARGUMENT,"Can't transfer a negative amount");
		if (amount>Amount.MAX_AMOUNT) return withError(ErrorType.ARGUMENT,"Can't transfer an amount beyong maximum limit");
		
		BlobMap<Address,AccountStatus> accounts=getState().getAccounts();
		
		Address source=getAddress();
		AccountStatus sourceAccount=accounts.get(source);
		if (sourceAccount==null) {
			return withError(ErrorType.STATE,"Cannot transfer from non-existent account: "+source);
		}
		
		long currentBalance=sourceAccount.getBalance().getValue();
		if (currentBalance<amount) {
			return this.withFundsError(Errors.insufficientFunds(source,amount));
		}
		
		long newSourceBalance=currentBalance-amount;
		AccountStatus newSourceAccount=sourceAccount.withBalance(newSourceBalance);
		accounts=accounts.assoc(source, newSourceAccount);

		// new target account (note: could be source account, so we get from latest accounts)
		AccountStatus targetAccount=accounts.get(target);	
		if (targetAccount==null) {
			targetAccount=AccountStatus.create();
		}
		
		Amount oldTargetBalance=targetAccount.getBalance();
		Amount newTargetBalance=oldTargetBalance.add(amount);
		AccountStatus newTargetAccount=targetAccount.withBalance(newTargetBalance);
		accounts=accounts.assoc(target, newTargetAccount);

		// SECURITY: new context with updated accounts
		Context<Long> result=new Context<>(chainState.withAccounts(accounts),juice,localBindings,null,depth,false);
		
		return result;
	}

	/**
	 * Accepts offered funds for the given address.
	 * 
	 * STATE error if offered amount is insufficient. ARGUMENT error if acceptance is negative.
	 * 
	 * @param <R>
	 * @param amount
	 * @return Updated context, with long amount accepted as result
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> acceptFunds(long amount) {
		if (amount<0L) return this.withError(ErrorType.ARGUMENT,"Negative accept argument");
		if (amount==0L) return (Context<R>) this.withResult(Juice.ACCEPT, 0L);
		
		long offer=getOffer();
		if (amount>offer) return this.withError(ErrorType.STATE,"Insufficient offered funds");
		
		State state=getState();
		Address addr=getAddress();
		long balance=state.getBalance(addr).getValue();
		state=state.withBalance(addr,Amount.create(balance+amount));
		
		// need to update both state and offer
		ChainState cs=chainState.withStateOffer(state,offer-amount);
		Context<T> ctx=this.withChainState(cs);
		
		return (Context<R>) ctx.withResult(Juice.ACCEPT, amount);
	}
	
	/**
	 * Executes a call to an Actor.
	 * 
	 * @param <R> Return type of Actor call
	 * @param target Target Actor address
	 * @param sym Symbol of function name defined by Actor
	 * @param args Arguments to Actor function invocation
	 * @return Context with result of Actor call (may be exceptional)
	 * @throws ExecutionException
	 */
	public <R> Context<R> actorCall(Address target, long offer, Object functionName, Object... args) {
		State state=getState();
		Symbol sym=RT.toSymbol(functionName);
		AccountStatus as=state.getAccount(target);
		if (as==null) return this.withError(ErrorType.STATE,"Actor does not exist: "+target);
		IFn<R> fn=as.getActorFunction(sym);
		if (fn==null) return this.withError(ErrorType.STATE,"Actor does not have exported function to call: "+sym);
		
		if (offer>0L) {
			Address senderAddress=getAddress();
			AccountStatus cas=state.getAccount(senderAddress);
			long balance=cas.getBalance().getValue();
			if (balance<offer) {
				return this.withFundsError("Insufficient funds for offer: "+offer);
			}
			cas=cas.withBalance(Amount.create(balance-offer));
			state=state.putAccount(senderAddress, cas);
		} else if (offer<0) {
			return this.withError(ErrorType.ARGUMENT, "Cannot make negative offer in Actor call: "+offer);
		}
		
		// Context for execution of Actor call. Increments depth
		// SECURITY: Must change address to the target Actor address.
		// SECURITY: Must change caller to current address.
		final Context<R> exContext=Context.create(state, juice, Maps.empty(), null, depth+1, getOrigin(),getAddress(), target,offer);
		
		// INVOKE ACTOR FUNCTION
		final Context<R> ctx=exContext.invoke(fn,args);
		
		// SECURITY: must handle state transitions in results correctly
		return handleStateResults(ctx);
	}
	
	@SuppressWarnings("unchecked")
	private <R> Context<R> handleStateResults(Context<R> ctx) {
		State returnState=ctx.getState();
		
		// refund offer if needed
		final Address address=getAddress(); // address we are returning to
		long refund=ctx.getOffer();
		if (refund>0) {
			// we need to refund caller
			AccountStatus cas=returnState.getAccount(address);
			long balance=cas.getBalance().getValue();
			cas=cas.withBalance(Amount.create(balance+refund));
			returnState=returnState.putAccount(address, cas);
		}
		
		R rv=ctx.getValue();
		if (rv instanceof RollbackValue) {
			// roll back state to before Actor call
			// Note: this will also refund unused offer.
			returnState=this.getState(); 
			rv=(R) ((RollbackValue<R>)rv).getValue();
		} else if (rv instanceof HaltValue) {
			rv=(R) ((HaltValue<R>)rv).getValue();
		}
		
		// Rebuild context for the current execution
		// SECURITY: must restore origin,caller,address,local bindings, offer
		Context<R> result=Context.create(returnState, ctx.getJuice(), getLocalBindings(), rv, depth, getOrigin(),getCaller(), address,getOffer());
		return result;
	}
	
	/**
	 * Deploys an Actor in this context.
	 * 
	 * First argument must be an Actor generation code, which will be evaluated in the new Actor account 
	 * to initialise the Actor
	 * 
	 * A Actor may be generated with a deterministic Address, in which case a repeated call with 
	 * the same Actor generation code will always return the same Actor, without re-running the generation.
	 * 
	 * Result will contain the Actor address if successful.
	 * 
	 * @param code Actor initialisation code
	 * @param deterministic Flag to indicate if a deterministic address should be computed
	 * @return Updated Context with Actor deployed, or an exceptional result
	 * @throws ExecutionException 
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> deployActor(Object code, boolean deterministic) {
		State state=getState();
		
		Address address;
		if (deterministic) {
			Hash hash=Hash.compute(code);
			address=Address.fromHash(hash);
			
			// Need to check if deterministic Account Address already exists for 'deploy-once'. If so, return it.
			AccountStatus as=state.getAccount(address);
			if (as!=null) return (Context<R>) withResult(Juice.DEPLOY_CONTRACT,address);
		} else {
			address=Address.fromHash(state.getHash());
		}
		
		return deployActor(code,address);
	}
	
	/**
	 * Deploys an Actor in this context to a specified Address
	 * 
	 * First argument must be an Actor generator function.
	 * 
	 * Result will contain the Actor address if successful.
	 * 
	 * @param code Actor initialisation code
	 * @param args
	 * @return Updated Context with Actor deployed, or an exceptional result
	 * @throws ExecutionException 
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> deployActor(Object code, Address address) {
		State state=getState();
		// deploy initial contract state
		State stateSetup=state.tryAddActor(address, Core.ENVIRONMENT);
		if (stateSetup==null) return withError(ErrorType.STATE,"Contract deployment address conflict: "+address);
		
		final Context<R> exContext=Context.create(stateSetup, juice, Maps.empty(), null, depth+1, getOrigin(),getAddress(), address);
		final Context<R> rctx=exContext.eval(code);
		
		// TODO: think about error returns from actors
		if (rctx.isExceptional()) return rctx; 
		
		//SECURITY: make sure this always works!!!
		// restore context for the current execution
		Context<R> result=Context.create(rctx.getState(), rctx.getJuice(), getLocalBindings(), rctx.getValue(), depth, getOrigin(),getCaller(), getAddress());
		
		return (Context<R>) result.withResult(Juice.DEPLOY_CONTRACT, address);
	}


	@SuppressWarnings("unchecked")
	public <R> Context<R> withError(ErrorType error) {
		return (Context<R>) withException(ErrorValue.create(error));
	}
	
	@SuppressWarnings("unchecked")
	public <R> Context<R> withError(ErrorType error,Object message) {
		return (Context<R>) withException(ErrorValue.create(error,message));
	}

	public <R> Context<R> withArityError(String string) {
		return withError(ErrorType.ARITY,string);
	}
	
	public <R> Context<R> withCompileError(String string) {
		return withError(ErrorType.COMPILE,string);
	}
	
	public Context<Object> withBoundsError(long index) {
		return withError(ErrorType.BOUNDS,"Index: "+index);
	}
	
	public <R> Context<R> withCastError(Object a, Class<?> klass) {
		return withError(ErrorType.CAST,"Can't convert "+a+" to class "+klass);
	}

	/**
	 * Gets the error type of this context's return value
	 * 
	 * @return The ErrorType of the current exceptional value, or null if there is no error.
	 */
	public ErrorType getErrorType() {
		if (result instanceof ErrorValue) {
			return ((ErrorValue)result).getType();
		}
		return null;
	}
 
	public <R> Context<R> withAssertError(String message) {
		return withError(ErrorType.ASSERT);
	}
	
	public <R> Context<R> withFundsError(String message) {
		return withError(ErrorType.FUNDS,message);
	}

	public <R> Context<R> withArgumentError(String message) {
		return withError(ErrorType.ARGUMENT,message);
	}

	public long getTimeStamp() {
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
	public Context<Long> schedule(long time, AOp<?> op) {
		// check vs current timestamp
		long timestamp=getTimeStamp();
		if (timestamp<0) return withError(ErrorType.ARGUMENT);
		if (time<timestamp) time=timestamp;
		
		long juice=(time-timestamp)/Juice.SCHEDULE_MILLIS_PER_JUICE_UNIT;
		if (this.juice<juice) return withJuiceError();
		
		State s=getState().scheduleOp(time,getAddress(),op);
		Context<?> ctx=this.withChainState(chainState.withState(s));
		
		return ctx.withResult(juice,time);
	}

	/**
	 * Sets the delegated stake on a specified peer to the specified level.
	 * May set to zero to remove stake. Stake will be capped by current balance.
	 * 
	 * @param peerAddress Peer Address on which to stake
	 * @param newStake Amount to stake
	 * @return Context with final take set
	 */
	@SuppressWarnings("unchecked")
	public <R> Context<R> setStake(Address peerAddress, long newStake) {
		State s=getState();
		PeerStatus ps=s.getPeer(peerAddress);
		if (ps==null) return withError(ErrorType.STATE,"Peer does not exist for Address: "+peerAddress.toChecksumHex());
		if (newStake<0) return this.withArgumentError("Cannot set a negative stake");
		if (newStake>Amount.MAX_AMOUNT) return this.withArgumentError("Target stake out of valid Amount range");
		
		Address myAddress=getAddress();
		long balance=getBalance(myAddress);
		long currentStake=ps.getDelegatedStake(myAddress); 
		long delta=newStake-currentStake;
		
		if (delta==0) return (Context<R>) this; // no change
		if (delta>0) {
			// we are increasing stake, so need to check sufficient balance
			if (delta>balance) return this.withFundsError("Insufficient balance ("+balance+") to increase stake to "+newStake);
		} 
		
		PeerStatus updatedPeer=ps.withDelegatedStake(myAddress, newStake);
		
		// Final updates. Hopefully everything balances. SECURITY: test this. A lot.
		s=s.withBalance(myAddress, Amount.create(balance-delta)); // adjust own balance
		s=s.withPeer(peerAddress, updatedPeer); // adjust peer
		return withState(s);
	}

	/**
	 * Sets the holding for a specified target account. Returns NOBODY exception if account does not exist.
	 * @param targetAddress Account address at which to set the holding
	 * @param value Value to set for the holding.
	 * @return Updated context
	 */
	public Context<Object> setHolding(Address targetAddress, Object value) {
		AccountStatus as=getAccountStatus(targetAddress);
		if (as==null) return withError(ErrorType.NOBODY,"No account in which to set holding");
		as=as.withHolding(getAddress(), value);
		return withAccountStatus(targetAddress,as);
	}

	protected <R> Context<R> withAccountStatus(Address target, AccountStatus accountStatus) {
		return withState(getState().putAccount(target, accountStatus));
	}
}
