package convex.core.lang;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import convex.core.ErrorType;
import convex.core.Init;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ABlob;
import convex.core.data.ABlobMap;
import convex.core.data.ADataStructure;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.IGet;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.lang.expanders.AExpander;
import convex.core.lang.expanders.CoreExpander;
import convex.core.lang.expanders.Expander;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.CoreFn;
import convex.core.lang.impl.CorePred;
import convex.core.lang.impl.ErrorValue;
import convex.core.lang.impl.HaltValue;
import convex.core.lang.impl.RecurValue;
import convex.core.lang.impl.ReturnValue;
import convex.core.lang.impl.RollbackValue;
import convex.core.util.Utils;

/**
 * This class builds the core runtime environment at startup. Core runtime
 * functions are required to implement basic language features such as:
 * <ul>
 * <li>Numerics</li>
 * <li>Data structures</li>
 * <li>Interaction with on-chain state and execution context</li>
 * </ul>
 * 
 * In general, core functions defined in this class are thin Java wrappers over
 * static functions in RT, but also need to account for appropriate juice costs
 * / exceptional case handling
 * 
 * Where possible, we implement core functions in Convex Lisp itself, see
 * resources/lang/core.con
 * 
 * "Java is the most distressing thing to hit computing since MS-DOS." - Alan
 * Kay
 */
public class Core {

	/**
	 * Core namespace
	 */
	public static final AHashMap<Symbol, Syntax> CORE_NAMESPACE;
	
	/**
	 * Default initial environment importing core namespace
	 */
	public static final AHashMap<Symbol, Syntax> ENVIRONMENT;
	
	/**
	 * Symbol for core namespace
	 */
	public static final Symbol CORE_SYMBOL = Symbol.create("convex.core");
	
	/**
	 * Address for core library
	 */
	public static final Address CORE_ADDRESS=Address.dummy("cccc");

	private static final HashSet<Object> tempReg = new HashSet<Object>();

	private static <T> T reg(T o) {
		tempReg.add(o);
		return o;
	}

	public static final CoreFn<AVector<?>> VECTOR = reg(new CoreFn<>(Symbols.VECTOR) {
		@Override
		public <I> Context<AVector<?>> invoke(Context<I> context, Object[] args) {
			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;

			if (!context.checkJuice(juice)) return context.withJuiceError();
			AVector<?> result = Vectors.of(args);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASequence<?>> CONCAT = reg(new CoreFn<>(Symbols.CONCAT) {
		@Override
		public <I> Context<ASequence<?>> invoke(Context<I> context, Object[] args) {
			ASequence<?> result = null;
			long juice = Juice.BUILD_DATA;
			for (Object a : args) {
				if (a == null) continue;
				ASequence<?> seq = RT.sequence(a);
				juice += seq.count() * Juice.BUILD_PER_ELEMENT;
				if (!context.checkJuice(juice)) return context.withJuiceError();
				result = RT.concat(result, seq);
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AVector<?>> VEC = reg(new CoreFn<>(Symbols.VEC) {
		@Override
		public <I> Context<AVector<?>> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Object o = args[0];
			Long n = RT.count(o);
			if (n == null) return context.withCastError(o, AVector.class);

			long juice = Juice.BUILD_DATA + n * Juice.BUILD_PER_ELEMENT;

			if (!context.checkJuice(juice)) return context.withJuiceError();
			AVector<?> result = RT.vec(o);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<?>> SET = reg(new CoreFn<>(Symbols.SET) {
		@Override
		public <I> Context<ASet<?>> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Object o = args[0];
			ASet<?> result = RT.set(o);
			if (result == null) return context.withCastError(o, ASet.class);

			// TODO: cost overflow?
			long juice = Juice.BUILD_DATA + result.count() * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AList<?>> LIST = reg(new CoreFn<>(Symbols.LIST) {
		@Override
		public <I> Context<AList<?>> invoke(Context<I> context, Object[] args) {
			// Any arity is OK

			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();
			AList<?> result = Lists.of(args);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<String> STR = reg(new CoreFn<>(Symbols.STR) {
		@Override
		public <I> Context<String> invoke(Context<I> context, Object[] args) {
			String result = RT.str(args);
			long juice = Juice.STR + result.length() * Juice.STR_PER_CHAR;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<String> NAME = reg(new CoreFn<>(Symbols.NAME) {
		@Override
		public <I> Context<String> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object named = args[0];
			String result = RT.getName(named);
			if (result == null) return context.withCastError(named, String.class);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Keyword> KEYWORD = reg(new CoreFn<>(Symbols.KEYWORD) {
		@Override
		public <I> Context<Keyword> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			String name = RT.name(args[0]);
			if (name == null) return context.withCastError(args[0], Keyword.class);

			Keyword result = Keyword.create(name);
			if (result == null) return context.withArgumentError("Invalid Keyword name: " + name);

			return context.withResult(Juice.KEYWORD, result);
		}
	});

	public static final CoreFn<Symbol> SYMBOL = reg(new CoreFn<>(Symbols.SYMBOL) {
		@Override
		public <I> Context<Symbol> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			String name = RT.name(args[0]);
			if (name == null) return context.withCastError(args[0], Symbol.class);

			Symbol result = Symbol.create(name);
			if (result == null) return context.withArgumentError("Invalid Keyword name: " + name);

			long juice = Juice.SYMBOL;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AOp<Object>> COMPILE = reg(new CoreFn<>(Symbols.COMPILE) {

		@Override
		public <I> Context<AOp<Object>> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Object form = args[0];
			// note: compiler takes care of Juice for us
			return context.expandCompile(form);
		}

	});

	public static final CoreFn<Object> EVAL = reg(new CoreFn<>(Symbols.EVAL) {

		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object form = args[0];
			Context<Object> rctx = context.eval(form);
			return rctx.consumeJuice(Juice.EVAL);
		}

	});

	public static final CoreExpander SCHEDULE = reg(new CoreExpander(Symbols.SCHEDULE) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<Syntax> expand(Object scheduleForm, AExpander cont, Context<?> context) {
			Syntax formSyntax=Syntax.create(scheduleForm);
			AList<Object> form = formSyntax.getValue();
			
			int n = form.size();
			if (n != 3) return context.withArityError("Expander requires arity 2 but got:" + n);

			Context<Syntax> ctx = (Context<Syntax>) context;

			// expand target timestamp expression
			ctx = ctx.expand(form.get(1), cont, cont);
			if (ctx.isExceptional()) return ctx;
			Object timeExp = ctx.getResult();

			// expand scheduled operation expression
			// might be embedded unquotes to expand?
			Object opExp = Lists.of(Symbols.COMPILE, (Object) Lists.of(Symbols.QUOTE, form.get(2)));
			ctx = ctx.expand(opExp, cont, cont);
			if (ctx.isExceptional()) return ctx;
			opExp = ctx.getResult();

			// return final expansion
			Syntax eForm = Syntax.create(Lists.of(Syntax.create(Symbols.SCHEDULE_STAR), timeExp, opExp));
			return context.withResult(Juice.SCHEDULE_EXPAND, eForm);
		}
	});

	public static final CoreFn<Long> SCHEDULE_STAR = reg(new CoreFn<>(Symbols.SCHEDULE_STAR) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(this.exactArityMessage(3, n));

			// get timestamp target
			Object tso = args[0];
			if (!(tso instanceof Long)) return context.withError(ErrorType.CAST);
			long sts = (long) tso;

			// get operation
			Object opo = args[1];
			if (!(opo instanceof AOp)) return context.withError(ErrorType.CAST);
			AOp<?> op = (AOp<?>) opo;

			return context.schedule(sts, op);
		}
	});

	public static final CoreFn<Syntax> SYNTAX = reg(new CoreFn<>(Symbols.SYNTAX) {
		@Override
		public <I> Context<Syntax> invoke(Context<I> context, Object[] args) {
			int n=args.length;
			if (n < 1) return context.withArityError(minArityMessage(1, args.length));
			if (n > 2) return context.withArityError(maxArityMessage(2, args.length));

			Syntax result;
			if (n==1) {
				result=Syntax.create(args[0]);
			} else {
				AHashMap<Object,Object> meta=RT.toHashMap(args[1]);
				if (meta==null) return context.withCastError(args[1], AHashMap.class);
				result = Syntax.create(args[0],meta);
			}
			

			long juice = Juice.SYNTAX;

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<Object> UNSYNTAX = reg(new CoreFn<>(Symbols.UNSYNTAX) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object result = Syntax.unwrap(args[0]);

			long juice = Juice.SYNTAX;

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<AHashMap<Object,Object>> META = reg(new CoreFn<>(Symbols.META) {
		@Override
		public <I> Context<AHashMap<Object,Object>> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a=args[0];

			AHashMap<Object,Object> result;
			if (a instanceof Syntax) {
				result = ((Syntax) a).getMeta();
			} else {
				result= null;
			}

			long juice = Juice.META;
			return context.withResult(juice, result);
		}
	});

	public static final CorePred SYNTAX_Q = reg(new CorePred(Symbols.SYNTAX_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Syntax;
		}
	});

	public static final CoreFn<Syntax> EXPAND = reg(new CoreFn<>(Symbols.EXPAND) {
		@SuppressWarnings("unchecked")
		@Override
		public <I> Context<Syntax> invoke(Context<I> context, Object[] args) {
			int n = args.length;

			context = context.lookup(Symbols.STAR_INITIAL_EXPANDER);
			if (context.isExceptional()) return (Context<Syntax>) context;
			AExpander initialExpander = (AExpander) context.getResult();

			AExpander expander;
			if (n == 1) {
				// use initial expander as continuation expander
				expander = initialExpander;
			} else if (n == 2) {
				Object exArg = args[1];
				expander = (exArg instanceof AExpander) ? (AExpander) exArg : Expander.wrap((AFn<Object>) exArg);
			} else {
				return context
						.withArityError(name() + " requires a form argument and optional expander (arity 1 or 2)");
			}
			Object form = args[0];
			Context<Syntax> rctx = expander.expand(form, initialExpander, context);
			return rctx;
		}
	});

	public static final CoreFn<Object> EXPANDER = reg(new CoreFn<>(Symbols.EXPANDER) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AFn<Object> fn = (AFn<Object>) RT.function(args[0]);

			Expander expander = Expander.wrap(fn);
			long juice = Juice.SIMPLE_FN;

			return context.withResult(juice, expander);
		}
	});

	public static final CoreExpander INITIAL_EXPANDER = reg(Compiler.INITIAL_EXPANDER);

	public static final CoreFn<Boolean> EXPORTS_Q = reg(new CoreFn<>(Symbols.EXPORTS_Q) {
		@Override
		public <R> Context<Boolean> invoke(Context<R> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address addr = RT.address(args[0]);
			if (addr == null) return context.withCastError(args[1], Address.class);

			Symbol sym = RT.toSymbol(args[1]);
			if (sym == null) return context.withCastError(args[1], Symbol.class);

			AccountStatus as = context.getState().getAccount(addr);
			if (as == null) return context.withResult(Juice.LOOKUP, Boolean.FALSE);

			Boolean result = as.getActorFunction(sym) != null;

			return context.withResult(Juice.LOOKUP, result);
		}
	});

	public static final CoreExpander EXPORT = reg(new CoreExpander(Symbols.EXPORT) {
		@Override
		public Context<Syntax> expand(Object o, AExpander ex, Context<?> context) {
			Syntax formSyntax=Syntax.create(o);
			AList<Object> form = formSyntax.getValue();
			
			int n = form.size();
			if (n < 1) return context.withError(ErrorType.COMPILE, "export form not valid?: " + form);
			for (int i = 1; i < n; i++) {
				Object so = Syntax.unwrap(form.get(i));
				if (!(so instanceof Symbol)) return context.withError(ErrorType.COMPILE,
						"export requires a list of symbols but got: " + Utils.getClass(so));
			}

			ASequence<Object> syms = form.next();
			Object quotedSyms = (syms == null) ? Vectors.empty() : syms.map(sym -> Lists.of(Symbols.QUOTE, sym));
			AList<Syntax> newForm = Lists.of(Syntax.create(Symbols.DEF), Syntax.create(Symbols.STAR_EXPORTS),
					Syntax.create(RT.cons(Symbols.CONJ, Symbols.STAR_EXPORTS, quotedSyms)));
			return context.withResult(Juice.SIMPLE_MACRO, Syntax.create(newForm));
		}
	});

	public static final CoreFn<Address> DEPLOY = reg(new CoreFn<>(Symbols.DEPLOY) {
		@Override
		public <I> Context<Address> invoke(Context<I> context, Object[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			return context.deployActor(args,false);
		}
	});
	
	public static final CoreFn<Address> DEPLOY_ONCE = reg(new CoreFn<>(Symbols.DEPLOY_ONCE) {
		@Override
		public <I> Context<Address> invoke(Context<I> context, Object[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			return context.deployActor(args,true);
		}
	});

	public static final CoreFn<Long> ACCEPT = reg(new CoreFn<>(Symbols.ACCEPT) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Long amount = RT.toLong(args[0]);
			if (amount == null) return context.withCastError(args[0], Long.class);

			return context.acceptFunds(amount);
		}
	});

	public static final CoreFn<Object> CALL_STAR = reg(new CoreFn<>(Symbols.CALL_STAR) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length < 3) return context.withArityError(minArityMessage(1, args.length));

			// consume juice first?
			Context<Object> ctx = context.consumeJuice(Juice.CALL_OP);
			if (ctx.isExceptional()) return ctx;

			Address target = RT.address(args[0]);
			if (target == null) return context.withCastError(args[0], Address.class);

			Long sendAmount = RT.toLong(args[1]);
			if (sendAmount == null) return context.withCastError(args[1], Long.class);

			Symbol sym = RT.toSymbol(args[2]);
			if (sym == null) return context.withCastError(args[1], Symbol.class);

			// prepare contract call arguments
			int arity = args.length - 3;
			Object[] callArgs = Arrays.copyOfRange(args, 3, 3 + arity);

			return ctx.actorCall(target, sendAmount, sym, callArgs);
		}
	});

	public static final CoreFn<Object> STORE = reg(new CoreFn<>(Symbols.STORE) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ASet<Object> store = context.getState().getStore();
			long initialCount = store.count();

			// this is non-trivial because we need to recursively check if child elements
			// need to be stored.
			Ref<Object> ref = Ref.create(args[0]);
			ASet<Object> newStore = ref.addAllToSet(store);

			long storedCount = newStore.count() - initialCount;
			assert (storedCount >= 0);
			context = context.withStore(newStore);

			long juice = Juice.SIMPLE_FN + Juice.STORE * storedCount;

			return context.withResult(juice, ref.getHash());
		}
	});

	public static final CoreFn<Object> FETCH = reg(new CoreFn<>(Symbols.FETCH) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Hash hash = RT.toHash(args[0]);
			if (hash == null) return context.withCastError(args[0], Hash.class);

			ASet<Object> store = context.getState().getStore();
			Object result = store.getByHash(hash);

			long juice = Juice.FETCH;

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<Object> SET_STAR = reg(new CoreFn<>(Symbols.SET_STAR) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Symbol sym = RT.toSymbol(args[0]);
			if (sym == null) return context.withCastError(args[0], Symbol.class);

			if (sym.isQualified()) {
				return context.withArgumentError("Cannot set! with qualified symbol: " + sym);
			}
			
			Object value=args[1];
			
			context= context.withLocalBindings(context.getLocalBindings().assoc(sym, value));
			return context.withResult(Juice.ASSOC,value);
		}
	});

	public static final CoreFn<Object> LOOKUP = reg(new CoreFn<>(Symbols.LOOKUP) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			String name = RT.name(args[0]);
			if (name == null) return context.withCastError(args[0], Symbol.class);

			Symbol sym = Symbol.create(name);
			if (sym == null) return context.withArgumentError("Invalid Symbol name: " + name);

			MapEntry<Symbol, Syntax> me = context.lookupDynamicEntry(sym);

			long juice = Juice.LOOKUP;
			Object result = (me == null) ? null : me.getValue().getValue();
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<Syntax> LOOKUP_SYNTAX = reg(new CoreFn<>(Symbols.LOOKUP_SYNTAX) {
		@Override
		public <I> Context<Syntax> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			String name = RT.name(args[0]);
			if (name == null) return context.withCastError(args[0], Symbol.class);

			Symbol sym = Symbol.create(name);
			if (sym == null) return context.withArgumentError("Invalid Symbol name: " + name);

			MapEntry<Symbol, Syntax> me = context.lookupDynamicEntry(sym);

			long juice = Juice.LOOKUP;
			Syntax result = (me == null) ? null : me.getValue();
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Address> ADDRESS = reg(new CoreFn<>(Symbols.ADDRESS) {
		@Override
		public <I> Context<Address> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object o = args[0];
			Address address = RT.address(o);
			if (address == null) {
				if (o instanceof String) return context.withArgumentError("Not a valid address: " + o);
				return context.withCastError(o, Address.class);
			}
			long juice = Juice.ADDRESS;

			return context.withResult(juice, address);
		}
	});

	public static final CoreFn<ABlob> BLOB = reg(new CoreFn<>(Symbols.BLOB) {
		@Override
		public <I> Context<ABlob> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// TODO: probably need to pre-cost this?
			ABlob blob = RT.blob(args[0]);
			if (blob == null) return context.withCastError(args[0], ABlob.class);

			long juice = Juice.BLOB + Juice.BLOB_PER_BYTE * blob.length();

			return context.withResult(juice, blob);
		}
	});

	public static final CoreFn<Boolean> ACTOR_Q = reg(new CoreFn<>(Symbols.ACTOR_Q) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a0 = args[0];
			Address address = RT.address(a0);

			// return false if the argument is not an address
			long juice = Juice.SIMPLE_FN;
			if (address == null) return context.withResult(juice, false);

			AccountStatus as = context.getAccountStatus(address);
			boolean result = as.isActor();

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Long> BALANCE = reg(new CoreFn<>(Symbols.BALANCE) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Address address = RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);

			AccountStatus as = context.getAccountStatus(address);
			Long balance = null;
			if (as != null) {
				balance = as.getBalance().getValue();
			}
			long juice = Juice.BALANCE;

			return context.withResult(juice, balance);
		}
	});

	public static final CoreFn<Long> TRANSFER = reg(new CoreFn<>(Symbols.TRANSFER) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);

			Long amount = RT.toLong(args[1]);
			if (amount == null) return context.withCastError(args[0], Long.class);

			return context.transfer(address, amount).consumeJuice(Juice.TRANSFER);

		}
	});

	public static final CoreFn<Long> STAKE = reg(new CoreFn<>(Symbols.STAKE) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);

			Long amount = RT.toLong(args[1]);
			if (amount == null) return context.withCastError(args[0], Long.class);

			return context.setStake(address, amount).consumeJuice(Juice.TRANSFER);

		}
	});

	public static final CoreFn<AMap<?, ?>> HASHMAP = reg(new CoreFn<>(Symbols.HASH_MAP) {
		@Override
		public <I> Context<AMap<?, ?>> invoke(Context<I> context, Object[] args) {
			int len = args.length;
			// specialised arity check since we need even length
			if (Utils.isOdd(len)) return context.withArityError(name() + " requires an even number of arguments");

			long juice = Juice.BUILD_DATA + len * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, Maps.of(args));
		}
	});

	public static final CoreFn<ASet<?>> HASHSET = reg(new CoreFn<>(Symbols.HASH_SET) {
		@Override
		public <I> Context<ASet<?>> invoke(Context<I> context, Object[] args) {
			// any arity is OK

			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, Sets.of(args));
		}
	});

	public static final CoreFn<AVector<Object>> KEYS = reg(new CoreFn<>(Symbols.KEYS) {
		@Override
		public <I> Context<AVector<Object>> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			if (!(a instanceof AMap)) return context.withCastError(a, AMap.class);

			@SuppressWarnings("unchecked")
			AMap<Object, Object> m = (AMap<Object, Object>) a;
			long juice = Juice.BUILD_DATA + m.count() * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<Object> keys = RT.keys(m);

			return context.withResult(juice, keys);
		}
	});

	public static final CoreFn<AVector<Object>> VALUES = reg(new CoreFn<>(Symbols.VALUES) {
		@Override
		public <I> Context<AVector<Object>> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			if (!(a instanceof AMap)) return context.withCastError(a, AMap.class);

			@SuppressWarnings("unchecked")
			AMap<Object, Object> m = (AMap<Object, Object>) a;
			long juice = Juice.BUILD_DATA + m.count() * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<Object> keys = RT.values(m);

			return context.withResult(juice, keys);
		}
	});

	public static final CoreFn<ADataStructure<?>> ASSOC = reg(new CoreFn<>(Symbols.ASSOC) {
		@Override
		public <I> Context<ADataStructure<?>> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (n < 1) return context.withArityError(minArityMessage(1, n));

			if (!Utils.isOdd(n)) return context.withArityError(name() + " requires key/value pairs as successive args");

			long juice = Juice.BUILD_DATA + (n - 1) * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			Object o = args[0];

			// preserve a single nil, with no elements to assoc
			if ((o == null) && (n == 1)) return context.withResult(juice, null);

			// convert to data structure
			ADataStructure<?> result = RT.toDataStructure(o);

			// values that are non-null but not a data structure are a cast error
			if ((o != null) && (result == null)) return context.withCastError(o, ADataStructure.class);

			// assoc additional elements. Must produce a valid non-null data structure after
			// each assoc
			for (int i = 1; i < n; i += 2) {
				result = RT.assoc(result, args[i], args[i + 1]);
				if (result == null) return context.withCastError(o, ADataStructure.class);
			}

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<?> GET_HOLDING = reg(new CoreFn<>(Symbols.GET_HOLDING) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));
			
			Address address=RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);
			
			AccountStatus as=context.getAccountStatus(address);
			if (as==null) return context.withError(ErrorType.NOBODY,"Account with holdings does not exist.");
			ABlobMap<Address,Object> holdings=as.getHoldings();
			
			// we get the target accounts holdings for the currently executing account
			Object result=holdings.get(context.getAddress());
			
			return context.withResult(Juice.LOOKUP, result);
		}
	});
	
	public static final CoreFn<Object> SET_HOLDING = reg(new CoreFn<>(Symbols.SET_HOLDING) {
		@SuppressWarnings("unchecked")
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (n !=2) return context.withArityError(exactArityMessage(1, n));
			
			Address address=RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);
						
			// result is specified by second arg
			Object result=args[1];
			
			// we set the target account holdings for the currently executing account
			// might return NOBODY if account does not exist
			context=(Context<I>) context.setHolding(address,result);
			if (context.isExceptional()) return (Context<Object>) context;
			
			return context.withResult(Juice.ASSOC, result);
		}
	});


	public static final CoreFn<?> GET = reg(new CoreFn<>(Symbols.GET) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if ((n < 2) || (n > 3)) {
				return context.withArityError(name() + " requires exactly 2 or 3 arguments");
			}

			Object result;
			Object coll = args[0];
			if (coll == null) {
				// Treat nil as empty collection with no keys
				result = (n == 3) ? args[2] : null;
			} else if (n == 2) {
				IGet<Object> gettable = RT.toGettable(coll);
				if (gettable == null) return context.withCastError(coll, IGet.class);
				result = gettable.get(args[1]);
			} else {
				IGet<Object> gettable = RT.toGettable(coll);
				if (gettable == null) return context.withCastError(coll, IGet.class);
				result = gettable.get(args[1], args[2]);
			}
			long juice = Juice.GET;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<?> GET_IN = reg(new CoreFn<>(Symbols.GET_IN) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ASequence<Object> ixs = RT.sequence(args[1]);
			if (ixs == null) return context.withCastError(args[1], ASequence.class);

			int il = ixs.size();
			long juice = Juice.GET * (1L + il);
			Object result = args[0];
			for (int i = 0; i < il; i++) {
				if (result == null) break; // gets in nil produce nil
				IGet<?> gettable = RT.toGettable(result);
				if (gettable == null) return context.withCastError(result, IGet.class);
				result = gettable.get(ixs.get(i));
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Boolean> CONTAINS_KEY_Q = reg(new CoreFn<>(Symbols.CONTAINS_KEY_Q) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(exactArityMessage(2, n));

			Boolean result;
			Object coll = args[0];
			if (coll == null) {
				result = false; // treat nil as empty collection
			} else {
				IGet<Object> gettable = RT.toGettable(args[0]);
				if (gettable == null) return context.withCastError(args[0], IGet.class);
				result = gettable.containsKey(args[1]);
			}

			long juice = Juice.GET;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AMap<?, ?>> DISSOC = reg(new CoreFn<>(Symbols.DISSOC) {
		@Override
		public <I> Context<AMap<?, ?>> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			AMap<Object, Object> result = RT.toMap(args[0]);
			if (result == null) return context.withCastError(args[0], AMap.class);

			for (int i = 1; i < n; i++) {
				result = result.dissoc(args[i]);
			}
			long juice = Juice.BUILD_DATA + (n - 1) * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ADataStructure<Object>> CONJ = reg(new CoreFn<>(Symbols.CONJ) {
		@Override
		public <I> Context<ADataStructure<Object>> invoke(Context<I> context, Object[] args) {
			int numAdditions = args.length - 1;
			if (args.length <= 0) return context.withArityError(name() + " requires a collection as first argument");

			// compute juice up front
			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * numAdditions;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ADataStructure<Object> result = RT.collection(args[0]);
			if (result == null) return context.withCastError(args[0], ADataStructure.class);

			for (int i = 0; i < numAdditions; i++) {
				Object val = args[i + 1];
				result = result.conj(val);
				if (result == null) return context.withCastError(val, MapEntry.class); // must be a failed map conj?
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<?>> DISJ = reg(new CoreFn<>(Symbols.DISJ) {
		@Override
		public <I> Context<ASet<?>> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ASet<Object> result = RT.toSet(args[0]);
			if (result == null) return context.withCastError(args[0], ASet.class);

			result = result.exclude((Object) args[1]);
			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASequence<?>> CONS = reg(new CoreFn<>(Symbols.CONS) {
		@Override
		public <I> Context<ASequence<?>> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (args.length < 2) return context.withArityError(minArityMessage(2, args.length));

			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * (n - 1);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			// get sequence from last argument
			ASequence<?> seq = RT.sequence(args[n - 1]);
			if (seq == null) return context.withCastError(seq, ASequence.class);

			AList<?> list = RT.cons(args[n - 2], seq);

			for (int i = n - 3; i >= 0; i--) {
				list = RT.cons(args[i], list);
			}
			return context.withResult(juice, list);
		}
	});

	public static final CoreFn<Object> FIRST = reg(new CoreFn<>(Symbols.FIRST) {
		// note we could define this as (nth coll 0) but this is more efficient
		
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object coll = args[0];
			ASequence<?> seq = RT.sequence(coll);
			if (seq == null) return context.withCastError(coll, ASequence.class);
			if (seq.count()<1) return context.withBoundsError(1);
			Object result = seq.get(0);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Object> SECOND = reg(new CoreFn<>(Symbols.SECOND) {
		// note we could define this as (nth coll 1) but this is more efficient
		
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			ASequence<?> seq = RT.sequence(a);
			if (seq == null) return context.withCastError(a, ASequence.class);
			if (seq.count()<2) return context.withBoundsError(1);
			Object result = seq.get(1);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Object> LAST = reg(new CoreFn<>(Symbols.LAST) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			ASequence<?> seq = RT.sequence(a);
			if (seq == null) return context.withCastError(a, ASequence.class);
			if (seq.isEmpty()) return context.withBoundsError(-1);
			
			Object result = seq.get(seq.count() - 1);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Boolean> EQUALS = reg(new CoreFn<>(Symbols.EQUALS) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {

			// all arities OK, all args OK
			boolean result = RT.allEqual(args);
			return context.withResult(Juice.EQUALS, result);
		}
	});

	public static final CoreFn<Boolean> EQ = reg(new CoreFn<>(Symbols.EQ) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			// all arities OK, but need to watch for non-numeric arguments
			Boolean result = RT.eq(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<Boolean> GE = reg(new CoreFn<>(Symbols.GE) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			// all arities OK
			Boolean result = RT.ge(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<Boolean> GT = reg(new CoreFn<>(Symbols.GT) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			// all arities OK

			Boolean result = RT.gt(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<Boolean> LE = reg(new CoreFn<>(Symbols.LE) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			// all arities OK

			Boolean result = RT.le(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<Boolean> LT = reg(new CoreFn<>(Symbols.LT) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			// all arities OK

			Boolean result = RT.lt(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<Number> INC = reg(new CoreFn<>(Symbols.INC) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Long result = RT.inc(a);
			if (result == null) return context.withCastError(a, Long.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Number> DEC = reg(new CoreFn<>(Symbols.DEC) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Long result = RT.dec(a);
			if (result == null) return context.withCastError(a, Long.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Boolean> BOOLEAN = reg(new CoreFn<>(Symbols.BOOLEAN) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// always works for any value
			Boolean result = RT.toBoolean(args[0]);

			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});
	
	public static final CorePred BOOLEAN_Q = reg(new CorePred(Symbols.BOOLEAN_Q) {
		@Override
		public boolean test(Object val) {
			// TODO Auto-generated method stub
			return RT.isBoolean(val);
		}
	});

	public static final CoreFn<Long> LONG = reg(new CoreFn<>(Symbols.LONG) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Long result = RT.toLong(a);
			if (result == null) return context.withCastError(a, Long.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Double> DOUBLE = reg(new CoreFn<>(Symbols.DOUBLE) {
		@Override
		public <I> Context<Double> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Double result = RT.toDouble(a);
			if (result == null) return context.withCastError(a, Double.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Character> CHAR = reg(new CoreFn<>(Symbols.CHAR) {
		@Override
		public <I> Context<Character> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Character result = RT.toCharacter(a);
			if (result == null) return context.withCastError(a, Byte.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Byte> BYTE = reg(new CoreFn<>(Symbols.BYTE) {
		@Override
		public <I> Context<Byte> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Byte result = RT.toByte(a);
			if (result == null) return context.withCastError(a, Byte.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Short> SHORT = reg(new CoreFn<>(Symbols.SHORT) {
		@Override
		public <I> Context<Short> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Short result = RT.toShort(a);
			if (result == null) return context.withCastError(a, Short.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Integer> INT = reg(new CoreFn<>(Symbols.INT) {
		@Override
		public <I> Context<Integer> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object a = args[0];
			Integer result = RT.toInteger(a);
			if (result == null) return context.withCastError(a, Integer.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Number> PLUS = reg(new CoreFn<>(Symbols.PLUS) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			// All arities OK

			Number result = RT.plus(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Number> MINUS = reg(new CoreFn<>(Symbols.MINUS) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));
			Number result = RT.minus(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Number> TIMES = reg(new CoreFn<>(Symbols.TIMES) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			// All arities OK
			Number result = RT.times(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Number> DIVIDE = reg(new CoreFn<>(Symbols.DIVIDE) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			Number result = RT.divide(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Number> SQRT = reg(new CoreFn<>(Symbols.SQRT) {
		@Override
		public <I> Context<Number> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Double result = RT.sqrt(args[0]);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Double> POW = reg(new CoreFn<>(Symbols.POW) {
		@Override
		public <I> Context<Double> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
			Double result = RT.pow(args);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Double> EXP = reg(new CoreFn<>(Symbols.EXP) {
		@Override
		public <I> Context<Double> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Double result = RT.exp(args[0]);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Boolean> NOT = reg(new CoreFn<>(Symbols.NOT) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			boolean result = !RT.bool(args[0]);
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<Hash> HASH = reg(new CoreFn<>(Symbols.HASH) {
		@Override
		public <I> Context<Hash> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Hash result = Hash.compute(args[0]);
			return context.withResult(Juice.HASH, result);
		}
	});

	public static final CoreFn<Long> COUNT = reg(new CoreFn<>(Symbols.COUNT) {
		@Override
		public <I> Context<Long> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Long result = RT.count(args[0]);
			if (result == null) return context.withCastError(args[0], ADataStructure.class);

			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<Object> EMPTY = reg(new CoreFn<>(Symbols.EMPTY) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Object o = args[0];

			// emptying nil is still nil
			if (o == null) return context.withResult(Juice.SIMPLE_FN, null);

			ADataStructure<?> coll = RT.toDataStructure(o);
			if (coll == null) return context.withCastError(o, ADataStructure.class);

			Object result = coll.empty();
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<Object> NTH = reg(new CoreFn<>(Symbols.NTH) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Object coll = args[0];
			Long ix = RT.toLong(args[1]);
			if (ix == null) return context.withCastError(args[1], Long.class);
			Long n = RT.count(coll);
			if (n == null) return context.withCastError(coll, ASequence.class);
			if ((ix < 0) || (ix >= n)) return context.withBoundsError(ix);

			Object result = RT.nth(coll, ix);

			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<Object> NEXT = reg(new CoreFn<>(Symbols.NEXT) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ASequence<Object> seq = RT.sequence(args[0]);
			if (seq == null) return context.withCastError(args[0], ASequence.class);

			Object result = seq.next();
			// TODO: probably needs to cost a lot?
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreExpander IF = reg(new CoreExpander(Symbols.IF) {
		@Override
		public Context<Syntax> expand(Object o, AExpander cont, Context<?> context) {
			if (o instanceof Syntax) {
				o = ((Syntax) o).getValue();
			}

			@SuppressWarnings("unchecked")
			AList<Object> form = (AList<Object>) o;
			int n = form.size();
			if (n < 3) return context.withCompileError("if requires at least two expressions but got: " + form);
			if (n > 4) return context.withCompileError("if requires at most three expressions but got: " + form);

			Object newForm = RT.cons(Symbols.COND, form.next());
			context = context.consumeJuice(Juice.SIMPLE_MACRO);
			return context.expand(newForm, cont, cont);
		}
	});

	public static final CoreExpander MACRO = reg(new CoreExpander(Symbols.MACRO) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<Syntax> expand(Object o, AExpander cont, Context<?> context) {
			// wrap form in Syntax object if needed
			Syntax formSyntax=Syntax.create(o);
			AList<Object> form = (AList<Object>) formSyntax.getValue();
			
			int n = form.size();
			if (n != 3) {
				return context.withCompileError("macro requires a binding form and expansion expression " + form);
			}

			Object paramForm = form.get(1);
			Object body = form.get(2);

			// expansion function is: (fn [x e] (let [<paramForm> (next (unsyntax x))] <body>))
			Object expansionFn = Lists.of(
					Symbols.FN, Vectors.of(Symbols.X, Symbols.E),
					Lists.of(Symbols.LET, Vectors.of(paramForm, Lists.of(Symbols.NEXT, Lists.of(Symbols.UNSYNTAX,Symbols.X))), 
							body));

			Object newForm = Syntax.create(Lists.of(Symbols.EXPANDER, expansionFn)).withMeta(formSyntax.getMeta());
			context = context.consumeJuice(Juice.SIMPLE_MACRO);
			return context.expand(newForm, cont, cont);
		}
	});

	public static final CoreFn<?> RECUR = reg(new CoreFn<>(Symbols.RECUR) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			// any arity OK?

			AExceptional result = RecurValue.wrap(args);

			return context.withException(Juice.RECUR, result);
		}
	});

	public static final CoreFn<?> ROLLBACK = reg(new CoreFn<>(Symbols.ROLLBACK) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = RollbackValue.wrap(args[0]);

			return context.withException(Juice.ROLLBACK, result);
		}
	});

	public static final CoreFn<?> HALT = reg(new CoreFn<>(Symbols.HALT) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			if (n > 1) return context.withArityError(this.maxArityMessage(1, n));

			AExceptional result = HaltValue.wrap((n > 0) ? args[0] : null);

			return context.withException(Juice.HALT, result);
		}
	});

	public static final CoreFn<?> RETURN = reg(new CoreFn<>(Symbols.RETURN) {
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = ReturnValue.wrap(args[0]);
			return context.withException(Juice.RETURN, result);
		}
	});

	public static final CoreFn<Boolean> ASSERT = reg(new CoreFn<>(Symbols.ASSERT) {
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			int n = args.length;
			for (int i = 0; i < n; i++) {
				if (!RT.bool(args[i])) return context.withAssertError("assert failed");
			}
			return context.withResult(Juice.SIMPLE_FN, Boolean.TRUE);
		}
	});

	public static final CoreFn<Boolean> FAIL = reg(new CoreFn<>(Symbols.FAIL) {
		@SuppressWarnings("unused")
		@Override
		public <I> Context<Boolean> invoke(Context<I> context, Object[] args) {
			int alen = args.length;
			if (alen > 2) return context.withArityError(maxArityMessage(2, alen));

			Long eval = (alen > 0) ? RT.toLong(args[0]) : ErrorType.ASSERT.code();
			if (eval == null) return context.withCastError(args[0], Long.class);

			ErrorType type = ErrorType.decode(eval);
			if (type == null) return context.withArgumentError("Unknown error type in fail: "+eval);

			Object message = (alen == 2) ? args[1] : null;
			ErrorValue error = ErrorValue.create(type, message);

			return context.withException(error);
		}
	});

	public static final CoreFn<?> APPLY = reg(new CoreFn<>(Symbols.APPLY) {
		@SuppressWarnings("unchecked")
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			int alen = args.length;
			if (alen < 2) return context.withArityError(minArityMessage(2, alen));

			AFn<Object> fn = (AFn<Object>) args[0];

			Object lastArg = args[alen - 1];
			ASequence<?> coll = RT.sequence(lastArg);
			if (coll == null) return context.withCastError(lastArg, ASequence.class);

			int vlen = coll.size(); // variable arg length

			// Build an array of arguments for the function
			// TODO: bounds on number of arguments?
			int n = (alen - 2) + vlen; // number of args to pass to function
			Object[] applyArgs;
			if (alen > 2) {
				applyArgs = new Object[n];
				for (int i = 0; i < (alen - 2); i++) {
					applyArgs[i] = args[i + 1];
				}
				int ix = alen - 2;
				for (Iterator<?> it = coll.iterator(); it.hasNext();) {
					applyArgs[ix++] = it.next();
				}
			} else {
				applyArgs = coll.toArray();
			}

			Context<Object> rctx = context.invoke(fn, applyArgs);
			return rctx.consumeJuice(Juice.APPLY);
		}
	});

	public static final CoreFn<ADataStructure<Object>> INTO = reg(new CoreFn<>(Symbols.INTO) {

		@Override
		public <I> Context<ADataStructure<Object>> invoke(Context<I> context, Object[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Object a0 = args[0];
			ADataStructure<Object> result = RT.toDataStructure(a0);
			if ((a0 != null) && (result == null)) return context.withCastError(args[0], ADataStructure.class);

			long juice = Juice.BUILD_DATA;
			Object a1 = args[1];
			if (a0 == null) {
				// just keep second arg as complete data structure
				result = RT.toDataStructure(a1);
				if ((a1 != null) && (result == null)) return context.withCastError(args[0], ADataStructure.class);
			} else {
				ASequence<Object> seq = RT.sequence(a1);
				if (seq == null) return context.withCastError(a1, ADataStructure.class);
				long n = seq.count();
				juice += Juice.BUILD_PER_ELEMENT * n;
				if (!context.checkJuice(juice)) return context.withJuiceError();

				result = RT.into(result, seq);
				if (result == null) return context.withCastError(args[0], MapEntry.class);
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASequence<?>> MAP = reg(new CoreFn<>(Symbols.MAP) {
		@SuppressWarnings("unchecked")
		@Override
		public <I> Context<ASequence<?>> invoke(Context<I> context, Object[] args) {
			if (args.length < 2) return context.withArityError(minArityMessage(2, args.length));

			// check and cast first argument to a function
			Object fnArg = args[0];
			IFn<?> f = RT.function(fnArg);
			if (f == null) return context.withCastError(fnArg, IFn.class);

			// remaining arguments determine function arity to use
			int fnArity = args.length - 1;
			Object[] xs = new Object[fnArity];
			ASequence<?>[] seqs = new ASequence[fnArity];

			int length = Integer.MAX_VALUE;
			for (int i = 0; i < fnArity; i++) {
				Object maybeSeq = args[1 + i];
				ASequence<?> seq = RT.sequence(maybeSeq);
				if (seq == null) return context.withCastError(maybeSeq, ASequence.class);
				seqs[i] = seq;
				length = Math.min(length, seq.size());
			}

			final long juice = Juice.MAP + Juice.BUILD_DATA * length;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ArrayList<Object> al = new ArrayList<>();
			for (int i = 0; i < length; i++) {
				for (int j = 0; j < fnArity; j++) {
					xs[j] = seqs[j].get(i);
				}
				context = (Context<I>) context.invoke(f, xs);
				if (context.isExceptional()) return (Context<ASequence<?>>) context;
				Object r = context.getResult();
				al.add(r);
			}

			ASequence<?> result = Vectors.create(al);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Object> REDUCE = reg(new CoreFn<>(Symbols.REDUCE) {
		@SuppressWarnings("unchecked")
		@Override
		public <I> Context<Object> invoke(Context<I> context, Object[] args) {
			if (args.length != 3) return context.withArityError(exactArityMessage(3, args.length));

			// check and cast first argument to a function
			Object fnArg = args[0];
			IFn<?> fn = RT.function(fnArg);
			if (fn == null) return context.withCastError(fnArg, IFn.class);

			// Initial value
			Object result = args[1];

			Object maybeSeq = args[2];
			ASequence<?> seq = RT.sequence(maybeSeq);
			if (seq == null) return context.withCastError(maybeSeq, ASequence.class);

			long c = seq.count();
			Object[] xs = new Object[2]; // accumulator, next element

			for (long i = 0; i < c; i++) {
				xs[0] = result;
				xs[1] = seq.get(i);
				context = (Context<I>) context.invoke(fn, xs);
				result = context.getResult();
			}

			return context.withResult(Juice.REDUCE, result);
		}
	});

	// =====================================================================================================
	// Predicates

	public static final CorePred NIL_Q = reg(new CorePred(Symbols.NIL_Q) {
		@Override
		public boolean test(Object val) {
			return val == null;
		}
	});

	public static final CorePred VECTOR_Q = reg(new CorePred(Symbols.VECTOR_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof AVector;
		}
	});

	public static final CorePred LIST_Q = reg(new CorePred(Symbols.LIST_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof AList;
		}
	});

	public static final CorePred SET_Q = reg(new CorePred(Symbols.SET_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof ASet;
		}
	});

	public static final CorePred MAP_Q = reg(new CorePred(Symbols.MAP_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof AMap;
		}
	});

	public static final CorePred COLL_Q = reg(new CorePred(Symbols.COLL_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof ADataStructure;
		}
	});

	public static final CorePred EMPTY_Q = reg(new CorePred(Symbols.EMPTY_Q) {
		@Override
		public boolean test(Object val) {
			// consider null as an empty object
			// like with clojure
			if (val == null) return true;

			return (val instanceof ADataStructure) && ((ADataStructure<?>) val).isEmpty();
		}
	});

	public static final CorePred SYMBOL_Q = reg(new CorePred(Symbols.SYMBOL_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Symbol;
		}
	});

	public static final CorePred KEYWORD_Q = reg(new CorePred(Symbols.KEYWORD_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Keyword;
		}
	});

	public static final CorePred BLOB_Q = reg(new CorePred(Symbols.BLOB_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof ABlob;
		}
	});

	public static final CorePred ADDRESS_Q = reg(new CorePred(Symbols.ADDRESS_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Address;
		}
	});
	
	public static final CorePred HASH_Q = reg(new CorePred(Symbols.HASH_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Hash;
		}
	});

	public static final CorePred LONG_Q = reg(new CorePred(Symbols.LONG_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Long;
		}
	});

	public static final CorePred STR_Q = reg(new CorePred(Symbols.STR_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof String;
		}
	});

	public static final CorePred NUMBER_Q = reg(new CorePred(Symbols.NUMBER_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof Number;
		}
	});

	public static final CorePred FN_Q = reg(new CorePred(Symbols.FN_Q) {
		@Override
		public boolean test(Object val) {
			return val instanceof IFn;
		}
	});

	public static final CorePred ZERO_Q = reg(new CorePred(Symbols.ZERO_Q) {
		@Override
		public boolean test(Object val) {
			if (!(val instanceof Number)) return false;
			if ((val instanceof Long)) return ((long) val == 0L);
			Number n = (Number) val;

			// According to the IEEE 754 standard, negative zero and positive zero should
			// compare as equal with the usual (numerical) comparison operators
			// This is the behaviour in Java
			return n.doubleValue() == 0.0;
		}
	});



	// =====================================================================================================
	// Core environment generation

	static Symbol symbolFor(Object o) {
		if (o instanceof CoreFn) return ((CoreFn<?>) o).getSymbol();
		if (o instanceof CoreExpander) return ((CoreExpander) o).getSymbol();
		throw new Error("Cant get symbol for Object of type " + o.getClass());
	}

	private static AHashMap<Symbol, Syntax> register(AHashMap<Symbol, Syntax> env, Object o) {
		Symbol sym = symbolFor(o);
		if (env.containsKey(sym)) throw new Error("Duplicate core declaration: " + sym);
		return env.assoc(sym, Syntax.create(o));
	}

	/**
	 * Bootstrap procedure to load the core.con library
	 * 
	 * @param env Initial environment map
	 * @return Loaded environment map
	 * @throws IOException
	 */
	private static AHashMap<Symbol, Syntax> registerCoreCode(AHashMap<Symbol, Syntax> env) {
		// we use a fake State to build the initial environment
		State state = State.EMPTY.putAccount(Init.HERO,
				AccountStatus.createActor(0, Amount.create(1000000000), null, env));
		Context<?> ctx = Context.createInitial(state, Init.HERO, 1000000L);

		Syntax form = null;
		
		try {
			AList<Syntax> forms = Reader.readAllSyntax(Utils.readResourceAsString("lang/core.con"));
			for (Syntax f : forms) {
				form = f;
				ctx = ctx.eval(form);
				// System.out.println("Core compilation juice: "+ctx.getJuice());
				if (ctx.isExceptional()) {
					throw new Error("Error compiling form: "+ Syntax.unwrapAll(form)+ " : "+ ctx.getExceptional());
				}
			}
		} catch (IOException t) {
			throw Utils.sneakyThrow(t);
		}

		return ctx.getAccountStatus(Init.HERO).getEnvironment();
	}

	private static AHashMap<Symbol, Syntax> registerSpecials(AHashMap<Symbol, Syntax> env) {
		env = env.assoc(Symbols.NAN, Syntax.create(Double.NaN));
		return env;
	}

	private static AHashMap<Symbol, Syntax> applyDocumentation(AHashMap<Symbol, Syntax> env) throws IOException {
		AMap<Symbol, AHashMap<Object, Object>> m = Reader.read(Utils.readResourceAsString("lang/core-metadata.doc"));
		for (Map.Entry<Symbol, AHashMap<Object, Object>> de : m.entrySet()) {
			Symbol sym = de.getKey();
			MapEntry<Symbol, Syntax> me = env.getEntry(sym);
			if (me == null) {
				System.err.println("CORE WARNING: Documentation for non-existent core symbol: " + sym);
				continue;
			}

			Syntax oldSyn = me.getValue();
			Syntax newSyn = oldSyn.mergeMeta(de.getValue());
			env = env.assoc(sym, newSyn);
		}

		return env;
	}

	static {
		// Set up convex.core environment
		AHashMap<Symbol, Syntax> coreEnv = Maps.empty();
		
		try {

			// Register all objects from registered runtime
			for (Object o : tempReg) {
				coreEnv = register(coreEnv, o);
			}

			coreEnv = registerCoreCode(coreEnv);
			coreEnv = registerSpecials(coreEnv);

			coreEnv = applyDocumentation(coreEnv);
		} catch (Throwable e) {
			e.printStackTrace();
		}
		
		CORE_NAMESPACE = coreEnv;

		// Copy aliases into default environment
		// TODO: should be only definition in default environment?
		Syntax ALIASES=coreEnv.get(Symbols.STAR_ALIASES);
		assert(ALIASES!=null);
		AHashMap<Symbol, Syntax> defaultEnv = coreEnv.assoc(Symbols.STAR_ALIASES,ALIASES);
		
		ENVIRONMENT = defaultEnv;
	}

	public static void main(String[] args) {
		System.out.println("Core environment constructed with " + Core.ENVIRONMENT.count() + " definitions");
		System.out.println("Core environment hash: " + ENVIRONMENT.getHash());
	}

}
