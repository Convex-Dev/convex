package convex.core.lang;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import convex.core.ErrorCodes;
import convex.core.State;
import convex.core.crypto.Hash;
import convex.core.data.ABlob;
import convex.core.data.ABlobMap;
import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.ADataStructure;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMaps;
import convex.core.data.Format;
import convex.core.data.IAssociative;
import convex.core.data.IGet;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Set;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.APrimitive;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.prim.CVMChar;
import convex.core.lang.expanders.AExpander;
import convex.core.lang.expanders.CoreExpander;
import convex.core.lang.expanders.Expander;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.CoreFn;
import convex.core.lang.impl.CorePred;
import convex.core.lang.impl.ErrorValue;
import convex.core.lang.impl.HaltValue;
import convex.core.lang.impl.RecurValue;
import convex.core.lang.impl.Reduced;
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
 * functions available in the runtime, but also need to account for:
 * <ul>
 * <li>Argument checking </li>
 * <li>Exceptional case handling</li>
 * <li>Appropriate juice costs</li>
 * </ul>
 * 
 * Where possible, we implement core functions in Convex Lisp itself, see
 * resources/lang/core.con
 * 
 * "Java is the most distressing thing to hit computing since MS-DOS." - Alan
 * Kay
 */
@SuppressWarnings("rawtypes")
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

	private static final HashSet<ACell> tempReg = new HashSet<ACell>();

	private static <T extends ACell> T reg(T o) {
		tempReg.add(o);
		return o;
	}

	public static final CoreFn<AVector<ACell>> VECTOR = reg(new CoreFn<>(Symbols.VECTOR) {
		@Override
		public Context<AVector<ACell>> invoke(Context<ACell> context, ACell[] args) {
			// Need to charge juice on per-element basis
			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;

			// Check juice before building a big vector. 
			// OK to fail early since will fail with JUICE anyway if vector is too big.
			if (!context.checkJuice(juice)) return context.withJuiceError();
			
			// Build and return requested vector
			AVector<ACell> result = Vectors.create(args);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASequence<ACell>> CONCAT = reg(new CoreFn<>(Symbols.CONCAT) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<ASequence<ACell>> invoke(Context context, ACell[] args) {
			ASequence<?> result = null;
			
			// initial juice is a load of null
			long juice = Juice.CONSTANT;
			for (ACell a : args) {
				if (a == null) continue;
				ASequence<?> seq = RT.sequence(a);
				if (seq == null) return context.withCastError(a, ASequence.class);
				
				// check juice per element of concatenated sequences
				juice += Juice.BUILD_DATA+ seq.count() * Juice.BUILD_PER_ELEMENT;
				if (!context.checkJuice(juice)) return context.withJuiceError();
				result = RT.concat(result, seq);
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AVector<ACell>> VEC = reg(new CoreFn<>(Symbols.VEC) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<AVector<ACell>> invoke(Context context, ACell[] args) {
			// Arity 1 exactly
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell o = args[0];
			
			// Need to compute juice before building potentially big vector
			Long n = RT.count(o);
			if (n == null) return context.withCastError(o, AVector.class);
			long juice = Juice.BUILD_DATA + n * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();
			
			AVector<?> result = RT.vec(o);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> SET = reg(new CoreFn<>(Symbols.SET) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<ASet<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell o = args[0];
			
			// Need to compute juice before building a potentially big set
			Long n = RT.count(o);
			if (n == null) return context.withCastError(o, ACollection.class);
			long juice = Juice.addMul(Juice.BUILD_DATA ,n,Juice.BUILD_PER_ELEMENT);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ASet<?> result = RT.set(o);
			if (result == null) return context.withCastError(o, ASet.class);

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<ASet<ACell>> UNION = reg(new CoreFn<>(Symbols.UNION) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<ASet<ACell>> invoke(Context context, ACell[] args) {
			int n=args.length;
			Set<ACell> result=Sets.empty();
			
			long juice=Juice.BUILD_DATA;
			
			for (int i=0; i<n; i++) {
				ACell arg=args[i];
				Set<ACell> set=RT.ensureSet(arg);
				if (set==null) return context.withCastError(arg, ASet.class);
				
				// check juice before expensive operation
				long size=set.count();
				juice = Juice.addMul(juice, size, Juice.BUILD_PER_ELEMENT);
				if (!context.checkJuice(juice)) return context.withJuiceError();
			
				result=result.includeAll(set);
			}

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<ASet<ACell>> INTERSECTION = reg(new CoreFn<>(Symbols.INTERSECTION) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<ASet<ACell>> invoke(Context context, ACell[] args) {
			if (args.length <1) return context.withArityError(minArityMessage(1, args.length));

			int n=args.length;
			ACell arg0=(ACell) args[0];
			Set<ACell> result=(arg0==null)?Sets.empty():RT.ensureSet(arg0);
			if (result==null) return context.withCastError(arg0, ASet.class);
			
			long juice=Juice.BUILD_DATA;
			
			for (int i=1; i<n; i++) {
				ACell arg=(ACell) args[i];
				Set<ACell> set=(arg==null)?Sets.empty():RT.ensureSet(args[i]);
				if (set==null) return context.withCastError(args[i], ASet.class);
				long size=set.count();
				
				juice = Juice.addMul(juice, size, Juice.BUILD_PER_ELEMENT);
				if (!context.checkJuice(juice)) return context.withJuiceError();
			
				result=result.intersectAll(set);
			}

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<ASet<ACell>> DIFFERENCE = reg(new CoreFn<>(Symbols.DIFFERENCE) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<ASet<ACell>> invoke(Context context, ACell[] args) {
			if (args.length <1) return context.withArityError(minArityMessage(1, args.length));

			int n=args.length;
			ACell arg0=args[0];
			Set<ACell> result=(arg0==null)?Sets.empty():RT.ensureSet(arg0);
			if (result==null) return context.withCastError(arg0, ASet.class);
			
			long juice=Juice.BUILD_DATA;
			
			for (int i=1; i<n; i++) {
				ACell arg=args[i];
				Set<ACell> set=RT.ensureSet(arg);
				if (set==null) return context.withCastError(args[i], ASet.class);
				long size=set.count();
				
				juice = Juice.addMul(juice, size, Juice.BUILD_PER_ELEMENT);
				if (!context.checkJuice(juice)) return context.withJuiceError();
			
				result=result.excludeAll(set);
			}

			return context.withResult(juice, result);
		}
	});



	public static final CoreFn<AList<ACell>> LIST = reg(new CoreFn<>(Symbols.LIST) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AList<ACell>> invoke(Context context, ACell[] args) {
			// Any arity is OK

			// Need to compute juice before building a potentially big list
			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();
			
			AList<ACell> result = List.create(args);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AString> STR = reg(new CoreFn<>(Symbols.STR) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<AString> invoke(Context context, ACell[] args) {
			// TODO: pre-check juice? String rendering definitions?
			AString result = RT.str(args);
			long juice = Juice.STR + result.length() * Juice.STR_PER_CHAR;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AString> NAME = reg(new CoreFn<>(Symbols.NAME) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<AString> invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Check can get as a String name
			ACell arg = args[0];
			AString result = RT.name(arg);
			if (result == null) return context.withCastError(arg, String.class);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Keyword> KEYWORD = reg(new CoreFn<>(Symbols.KEYWORD) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<Keyword> invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Check argument is valid name
			AString name = RT.name(args[0]);
			if (name == null) return context.withCastError(args[0], Keyword.class);

			// Check name converts to Keyword 
			Keyword result = Keyword.create(name);
			if (result == null) return context.withArgumentError("Invalid Keyword name: " + name);

			return context.withResult(Juice.KEYWORD, result);
		}
	});

	public static final CoreFn<Symbol> SYMBOL = reg(new CoreFn<>(Symbols.SYMBOL) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<Symbol> invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Check argument is valid name
			ACell symArg=args[0];
			Symbol sym = RT.toSymbol(symArg);
			if (sym == null) return context.withCastError(symArg, Symbol.class);

			long juice = Juice.SYMBOL;
			return context.withResult(juice, sym);
		}
	});

	public static final CoreFn<AOp<ACell>> COMPILE = reg(new CoreFn<>(Symbols.COMPILE) {

		@SuppressWarnings("unchecked")
		@Override
		public Context<AOp<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell form = (ACell) args[0];
			// note: compiler takes care of Juice for us
			return context.expandCompile(form);
		}

	});

	public static final CoreFn<ACell> EVAL = reg(new CoreFn<>(Symbols.EVAL) {

		@SuppressWarnings("unchecked")
		@Override
		public Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell form = (ACell) args[0];
			Context<ACell> rctx = context.eval(form);
			return rctx.consumeJuice(Juice.EVAL);
		}

	});
	
	public static final CoreFn<ACell> EVAL_AS = reg(new CoreFn<>(Symbols.EVAL_AS) {

		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.address(args[0]);
			if (address==null) return context.withCastError(args[0], Address.class);
			
			ACell form = (ACell) args[1];
			Context<ACell> rctx = context.evalAs(address,form);
			return rctx.consumeJuice(Juice.EVAL);
		}
	});

	public static final CoreFn<CVMLong> SCHEDULE_STAR = reg(new CoreFn<>(Symbols.SCHEDULE_STAR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(this.exactArityMessage(3, n));

			// get timestamp target
			CVMLong tso = RT.toLong(args[0]);
			if (tso==null) return context.withCastError(args[0],Long.class);
			long scheduleTimestamp = tso.longValue();

			// get operation
			ACell opo = args[1];
			if (!(opo instanceof AOp)) return context.withCastError(opo,AOp.class);
			AOp<?> op = (AOp<?>) opo;

			return context.schedule(scheduleTimestamp, op);
		}
	});

	public static final CoreFn<Syntax> SYNTAX = reg(new CoreFn<>(Symbols.SYNTAX) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Syntax> invoke(Context context, ACell[] args) {
			int n=args.length;
			if (n < 1) return context.withArityError(minArityMessage(1, args.length));
			if (n > 2) return context.withArityError(maxArityMessage(2, args.length));

			Syntax result;
			if (n==1) {
				result=Syntax.create((ACell)args[0]);
			} else {
				AHashMap<ACell,ACell> meta=RT.toHashMap(args[1]);
				if (meta==null) return context.withCastError(args[1], AHashMap.class);
				result = Syntax.create((ACell) args[0],meta);
			}

			long juice = Juice.SYNTAX;

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<ACell> UNSYNTAX = reg(new CoreFn<>(Symbols.UNSYNTAX) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Unwrap Syntax. Cannot fail.
			ACell result = Syntax.unwrap(args[0]);

			// Return unwrapped value with juice
			long juice = Juice.SYNTAX;
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<AHashMap<ACell,ACell>> META = reg(new CoreFn<>(Symbols.META) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AHashMap<ACell,ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a=args[0];

			AHashMap<ACell,ACell> result;
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
		public boolean test(ACell val) {
			return val instanceof Syntax;
		}
	});

	public static final CoreFn<Syntax> EXPAND = reg(new CoreFn<>(Symbols.EXPAND) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Syntax> invoke(Context context, ACell[] args) {
			int n = args.length;
			if ((n<1)||(n>2)) {
				return context.withArityError(name() + " requires a form argument and optional expander (arity 1 or 2)");
			}

			context = context.lookup(Symbols.STAR_INITIAL_EXPANDER);
			if (context.isExceptional()) return (Context<Syntax>) context;
			ACell maybeEx=context.getResult();
			if (!(maybeEx instanceof AExpander)) {
				return context.withError(ErrorCodes.CAST,name()+" requires a valid *initial-expander*, not found in enviornment");
			}
			AExpander initialExpander = (AExpander) maybeEx;

			AExpander expander=initialExpander;
			if (n == 2) {
				// use provided expander
				ACell exArg = args[1];
				expander=Expander.wrap(exArg);
			}
			if (expander==null) return context.withCastError(ErrorCodes.CAST, AExpander.class);
			ACell form = args[0];
			Context<Syntax> rctx = expander.expand(form, initialExpander, context);
			return rctx;
		}
	});

	public static final CoreFn<ACell> EXPANDER = reg(new CoreFn<>(Symbols.EXPANDER) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			IFn<ACell> fn = RT.function(args[0]);

			// check cast to function
			if (!(fn instanceof AFn)) return context.withCastError(args[0], AFn.class);
			
			Expander expander = Expander.wrap((AFn)fn);
			if (expander==null) return context.withError(ErrorCodes.CAST, "Expander requires a valid function");
			long juice = Juice.SIMPLE_FN;

			return context.withResult(juice, expander);
		}
	});

	public static final CoreExpander INITIAL_EXPANDER = reg(Compiler.INITIAL_EXPANDER);

	public static final CoreFn<CVMBool> EXPORTS_Q = reg(new CoreFn<>(Symbols.EXPORTS_Q) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<CVMBool> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address addr = RT.address(args[0]);
			if (addr == null) return context.withCastError(args[1], Address.class);

			Symbol sym = RT.toSymbol(args[1]);
			if (sym == null) return context.withCastError(args[1], Symbol.class);

			AccountStatus as = context.getState().getAccount(addr);
			if (as == null) return context.withResult(Juice.LOOKUP, CVMBool.FALSE);

			CVMBool result = RT.toBoolean(as.getExportedFunction(sym) != null);

			return context.withResult(Juice.LOOKUP, result);
		}
	});

	public static final CoreExpander EXPORT = reg(new CoreExpander(Symbols.EXPORT) {
		@Override
		public Context<Syntax> expand(ACell o, AExpander ex, Context<?> context) {
			Syntax formSyntax=Syntax.create(o);
			AList<ACell> form = formSyntax.getValue();
			
			int n = form.size();
			if (n < 1) return context.withError(ErrorCodes.COMPILE, "export form not valid?: " + form);
			for (int i = 1; i < n; i++) {
				ACell so = Syntax.unwrap(form.get(i));
				if (!(so instanceof Symbol)) return context.withError(ErrorCodes.COMPILE,
						"export requires a list of symbols but got: " + Utils.getClass(so));
			}

			ASequence<ACell> syms = form.next();
			ASequence<ACell> quotedSyms = (syms == null) ? Vectors.empty() : syms.map(sym -> Lists.of(Symbols.QUOTE, sym));
			AList<Syntax> newForm = Lists.of(Syntax.create(Symbols.DEF), Syntax.create(Symbols.STAR_EXPORTS),
					Syntax.create(RT.cons(Symbols.CONJ, Symbols.STAR_EXPORTS, quotedSyms)));
			return context.withResult(Juice.SIMPLE_MACRO, Syntax.create(newForm));
		}
	});

	public static final CoreFn<Address> DEPLOY = reg(new CoreFn<>(Symbols.DEPLOY) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Address> invoke(Context context, ACell[] args) {
			if (args.length !=1) return context.withArityError(exactArityMessage(1, args.length));

			return context.deployActor((ACell) args[0]);
		}
	});


	public static final CoreFn<CVMLong> ACCEPT = reg(new CoreFn<>(Symbols.ACCEPT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// must cast to Long
			CVMLong amount = RT.toLong(args[0]);
			if (amount == null) return context.withCastError(args[0], Long.class);

			return context.acceptFunds(amount.longValue());
		}
	});

	public static final CoreFn<ACell> CALL_STAR = reg(new CoreFn<>(Symbols.CALL_STAR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length < 3) return context.withArityError(minArityMessage(1, args.length));

			// consume juice first?
			Context<ACell> ctx = context.consumeJuice(Juice.CALL_OP);
			if (ctx.isExceptional()) return ctx;

			Address target = RT.address(args[0]);
			if (target == null) return ctx.withCastError(args[0], Address.class);

			CVMLong sendAmount = RT.toLong(args[1]);
			if (sendAmount == null) return ctx.withCastError(args[1], Long.class);

			Symbol sym = RT.toSymbol(args[2]);
			if (sym == null) return ctx.withCastError(args[2], Symbol.class);

			// prepare contract call arguments
			int arity = args.length - 3;
			ACell[] callArgs = Arrays.copyOfRange(args, 3, 3 + arity);

			return ctx.actorCall(target, sendAmount.longValue(), sym, callArgs);
		}
	});

	public static final CoreFn<Hash> STORE = reg(new CoreFn<>(Symbols.STORE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Hash> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ASet<ACell> store = context.getState().getStore();

			Ref<ACell> ref = Ref.get(args[0]);
			ASet<ACell> newStore = store.includeRef(ref);
			context = context.withStore(newStore);

			long juice = Juice.STORE;

			return context.withResult(juice, ref.getHash());
		}
	});
	
	public static final CoreFn<Hash> LOG = reg(new CoreFn<>(Symbols.LOG) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Hash> invoke(Context context, ACell[] args) {
			// any arity fine
			int n=args.length;
			long juice = Juice.LOG+Juice.BUILD_DATA+n*Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) {
				return context.withJuiceError();
			}
			AVector<ACell> values=Vectors.create(args);
			
			context=context.appendLog(values);

			return context.withResult(juice, values);
		}
	});

	public static final CoreFn<ACell> FETCH = reg(new CoreFn<>(Symbols.FETCH) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Hash hash = RT.toHash(args[0]);
			if (hash == null) return context.withCastError(args[0], Hash.class);

			ASet<ACell> store = context.getState().getStore();
			ACell result = store.getByHash(hash);

			long juice = Juice.FETCH;

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<ACell> SET_STAR = reg(new CoreFn<>(Symbols.SET_STAR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Symbol sym = RT.toSymbol(args[0]);
			if (sym == null) return context.withCastError(args[0], Symbol.class);

			if (sym.isQualified()) {
				return context.withArgumentError("Cannot set local binding with qualified symbol: " + sym);
			}
			
			ACell value=(ACell) args[1];
			
			context= context.withLocalBindings(context.getLocalBindings().assoc(sym, value));
			return context.withResult(Juice.ASSOC,value);
		}
	});
	
	public static final CoreFn<ACell> UNDEF_STAR = reg(new CoreFn<>(Symbols.UNDEF_STAR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Symbol sym=RT.toSymbol(args[0]);
			if (sym == null) return context.withArgumentError("Invalid Symbol name for undef: " + Utils.toString(args[0]));
			
			Context<ACell> ctx=(Context<ACell>) context.undefine(sym);
			
			// return nil
			return ctx.withResult(Juice.DEF, null);

		}
	});
	
	

	public static final CoreFn<ACell> LOOKUP = reg(new CoreFn<>(Symbols.LOOKUP) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int n=args.length;
			if ((n<1)||(n>2)) return context.withArityError(rangeArityMessage(1,2, args.length));

			// get Address to perform lookup
			Address address=(n==1)?context.getAddress():RT.address(args[0]);
			if (address==null) return context.withCastError(args[0], Address.class);
			
			// ensure argument converts to a Symbol correctly.
			ACell symArg=args[n-1];
			Symbol sym = RT.toSymbol(symArg);
			if (sym == null) return context.withCastError(symArg,Symbol.class);

			MapEntry<Symbol, Syntax> me = context.lookupDynamicEntry(address,sym);

			long juice = Juice.LOOKUP;
			ACell result = (me == null) ? null : me.getValue().getValue();
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<Syntax> LOOKUP_SYNTAX = reg(new CoreFn<>(Symbols.LOOKUP_SYNTAX) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Syntax> invoke(Context context, ACell[] args) {
			int n=args.length;
			if ((n<1)||(n>2)) return context.withArityError(rangeArityMessage(1,2, args.length));

			// get Address to perform lookup
			Address address=(n==1)?context.getAddress():RT.address(args[0]);
			if (address==null) return context.withCastError(args[0], Address.class);
			
			// ensure argument converts to a Symbol correctly.
			ACell symArg=args[n-1];
			Symbol sym = RT.toSymbol(symArg);
			if (sym == null) return context.withCastError(symArg,Symbol.class);

			MapEntry<Symbol, Syntax> me = context.lookupDynamicEntry(address,sym);

			long juice = Juice.LOOKUP;
			Syntax result = (me == null) ? null : me.getValue();
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Address> ADDRESS = reg(new CoreFn<>(Symbols.ADDRESS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Address> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell o = args[0];
			Address address = RT.address(o);
			if (address == null) {
				if (o instanceof AString) return context.withArgumentError("String not convertible to a valid Address: " + o);
				if (o instanceof ABlob) return context.withArgumentError("Blob not convertiable a valid Address: " + o);
				return context.withCastError(o, Address.class);
			}
			long juice = Juice.ADDRESS;

			return context.withResult(juice, address);
		}
	});
	
	public static final CoreFn<Address> CREATE_ACCOUNT = reg(new CoreFn<>(Symbols.CREATE_ACCOUNT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Address> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell o = args[0];
			AccountKey key = RT.accountKey(o);
			if ((o!=null)&&(key == null)) {
				return context.withCastError(o, AccountKey.class);
			}
			long juice = Juice.CREATE_ACCOUNT;
			
			Context<Address> rctx=context.createAccount(key);
			return rctx.consumeJuice(juice);
		}
	});

	public static final CoreFn<ABlob> BLOB = reg(new CoreFn<>(Symbols.BLOB) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ABlob> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// TODO: probably need to pre-cost this?
			ABlob blob = RT.blob(args[0]);
			if (blob == null) return context.withCastError(args[0], ABlob.class);

			long juice = Juice.BLOB + Juice.BLOB_PER_BYTE * blob.length();

			return context.withResult(juice, blob);
		}
	});
	
	public static final CoreFn<ABlobMap> BLOB_MAP = reg(new CoreFn<>(Symbols.BLOB_MAP) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ABlobMap> invoke(Context context, ACell[] args) {
			if (args.length != 0) return context.withArityError(exactArityMessage(0, args.length));

			long juice = Juice.BUILD_DATA;

			return context.withResult(juice, BlobMaps.empty());
		}
	});

	public static final CoreFn<CVMBool> ACTOR_Q = reg(new CoreFn<>(Symbols.ACTOR_Q) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a0 = args[0];
			Address address = RT.address(a0);

			// return false if the argument is not castable to an address
			long juice = Juice.SIMPLE_FN;
			if (address == null) return context.withResult(juice, CVMBool.FALSE);

			AccountStatus as = context.getAccountStatus(address);
			if (as == null) return context.withResult(juice, CVMBool.FALSE);
			
			CVMBool result = CVMBool.create(as.isActor());

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<CVMBool> ACCOUNT_Q = reg(new CoreFn<>(Symbols.ACCOUNT_Q) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			long juice = Juice.SIMPLE_FN;
			ACell a0 = args[0];
			
			// Return false if argument is not an Address
			if (!(a0 instanceof Address)) return context.withResult(juice, CVMBool.FALSE);
			Address address = (Address)a0;

			// return false if the address does not refer to an existing account
			if (context.getAccountStatus(address) == null) return context.withResult(juice, CVMBool.FALSE);

			// We have proved it is a valid account
			return context.withResult(juice,  CVMBool.TRUE);
		}
	});
	
	public static final CoreFn<AccountStatus> ACCOUNT = reg(new CoreFn<>(Symbols.ACCOUNT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AccountStatus> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a0 = args[0];
			Address address = RT.address(a0);
			if (address == null) return context.withCastError(a0, Address.class);

			// Note: returns null if the argument is not an address
			AccountStatus as = context.getAccountStatus(address);

			return context.withResult(Juice.SIMPLE_FN, as);
		}
	});

	public static final CoreFn<CVMLong> BALANCE = reg(new CoreFn<>(Symbols.BALANCE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Address address = RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);

			AccountStatus as = context.getAccountStatus(address);
			CVMLong balance = null;
			if (as != null) {
				balance = CVMLong.create(as.getBalance());
			}
			long juice = Juice.BALANCE;

			return context.withResult(juice, balance);
		}
	});

	public static final CoreFn<CVMLong> TRANSFER = reg(new CoreFn<>(Symbols.TRANSFER) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);

			CVMLong amount = RT.toLong(args[1]);
			if (amount == null) return context.withCastError(args[1], Long.class);

			return context.transfer(address, amount.longValue()).consumeJuice(Juice.TRANSFER);

		}
	});
	
	public static final CoreFn<CVMLong> SET_MEMORY = reg(new CoreFn<>(Symbols.SET_MEMORY) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			CVMLong amount = RT.toLong(args[0]);
			if (amount == null) return context.withCastError(args[0], Long.class);

			return context.setMemory(amount.longValue()).consumeJuice(Juice.TRANSFER);
		}
	});
	
	public static final CoreFn<CVMLong> TRANSFER_MEMORY = reg(new CoreFn<>(Symbols.TRANSFER_MEMORY) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);

			CVMLong amount = RT.toLong(args[1]);
			if (amount == null) return context.withCastError(args[1], Long.class);

			return context.transferAllowance(address, amount.longValue()).consumeJuice(Juice.TRANSFER);
		}
	});

	public static final CoreFn<CVMLong> STAKE = reg(new CoreFn<>(Symbols.STAKE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AccountKey address = RT.accountKey(args[0]);
			if (address == null) return context.withCastError(args[0], AccountKey.class);

			CVMLong amount = RT.toLong(args[1]);
			if (amount == null) return context.withCastError(args[0], Long.class);

			return context.setStake(address, amount.longValue()).consumeJuice(Juice.TRANSFER);

		}
	});

	public static final CoreFn<AMap<?, ?>> HASHMAP = reg(new CoreFn<>(Symbols.HASH_MAP) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AMap<?, ?>> invoke(Context context, ACell[] args) {
			int len = args.length;
			// specialised arity check since we need even length
			if (Utils.isOdd(len)) return context.withArityError(name() + " requires an even number of arguments");

			long juice = Juice.BUILD_DATA + len * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, Maps.create(args));
		}
	});

	public static final CoreFn<ASet<?>> HASHSET = reg(new CoreFn<>(Symbols.HASH_SET) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ASet<?>> invoke(Context context, ACell[] args) {
			// any arity is OK

			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, Sets.create(args));
		}
	});

	public static final CoreFn<AVector<ACell>> KEYS = reg(new CoreFn<>(Symbols.KEYS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AVector<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			if (!(a instanceof AMap)) return context.withCastError(a, AMap.class);

			AMap<ACell, ACell> m = (AMap<ACell,ACell>) a;
			long juice = Juice.BUILD_DATA + m.count() * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<ACell> keys = RT.keys(m);

			return context.withResult(juice, keys);
		}
	});

	public static final CoreFn<AVector<ACell>> VALUES = reg(new CoreFn<>(Symbols.VALUES) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AVector<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			if (!(a instanceof AMap)) return context.withCastError(a, AMap.class);

			AMap<ACell, ACell> m = (AMap<ACell, ACell>) a;
			long juice = Juice.BUILD_DATA + m.count() * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<ACell> keys = RT.values(m);

			return context.withResult(juice, keys);
		}
	});

	public static final CoreFn<ADataStructure<ACell>> ASSOC = reg(new CoreFn<>(Symbols.ASSOC) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ADataStructure<ACell>> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n < 1) return context.withArityError(minArityMessage(1, n));

			if (!Utils.isOdd(n)) return context.withArityError(name() + " requires key/value pairs as successive args");

			long juice = Juice.BUILD_DATA + (n - 1) * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ACell o = args[0];

			// preserve a single nil, with no elements to assoc
			if ((o == null) && (n == 1)) return context.withResult(juice, null);

			// convert to data structure
			ADataStructure<ACell> result = RT.ensureDataStructure(o);

			// values that are non-null but not a data structure are a cast error
			if ((o != null) && (result == null)) return context.withCastError(o, ADataStructure.class);

			// assoc additional elements. Must produce a valid non-null data structure after
			// each assoc
			for (int i = 1; i < n; i += 2) {
				ACell key=(ACell)args[i];
				result = RT.assoc(result, key, (ACell)args[i + 1]);
				if (result == null) return context.withCastError(key, "Cannot assoc value - invalid map key type");
			}

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<ACell> ASSOC_IN = reg(new CoreFn<>(Symbols.ASSOC_IN) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 3) return context.withArityError(exactArityMessage(3, args.length));

			ASequence<ACell> ixs = RT.sequence(args[1]);
			if (ixs == null) return context.withCastError(args[1], ASequence.class);

			int n = ixs.size();
			long juice = (Juice.GET+Juice.ASSOC) * (1L + n);
			ACell data = (ACell) args[0];
			ACell value=(ACell)args[2];
			// simply substitute value if key sequence is empty
			if (n==0) return context.withResult(juice, value);
			
			IAssociative<ACell,ACell>[] ass=new IAssociative[n];
			ACell[] ks=new ACell[n];
			for (int i = 0; i < n; i++) {
				IAssociative<ACell,ACell> struct = RT.ensureAssociative(data);
				if (struct == null) return context.withCastError(data, IAssociative.class);
				ass[i]=struct;
				ACell k=ixs.get(i);
				ks[i]=k;
				data=struct.get(k);
			}
			
			for (int i = n-1; i >=0; i--) {
				IAssociative<ACell,ACell> struct=ass[i];
				ACell k=ks[i];
				value=RT.assoc(struct, k, value);
				if (value==null) return context.withCastError(struct, IAssociative.class);
			}
			return context.withResult(juice, value);
		}
	});
	
	public static final CoreFn<ACell> GET_HOLDING = reg(new CoreFn<>(Symbols.GET_HOLDING) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));
			
			Address address=RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);
			
			AccountStatus as=context.getAccountStatus(address);
			if (as==null) return context.withError(ErrorCodes.NOBODY,"Account with holdings does not exist.");
			ABlobMap<Address,ACell> holdings=as.getHoldings();
			
			// we get the target accounts holdings for the currently executing account
			ACell result=holdings.get(context.getAddress());
			
			return context.withResult(Juice.LOOKUP, result);
		}
	});
	
	public static final CoreFn<ACell> SET_HOLDING = reg(new CoreFn<>(Symbols.SET_HOLDING) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=2) return context.withArityError(exactArityMessage(2, n));
			
			Address address=RT.address(args[0]);
			if (address == null) return context.withCastError(args[0], Address.class);
						
			// result is specified by second arg
			ACell result=(ACell) args[1];
			
			// we set the target account holdings for the currently executing account
			// might return NOBODY if account does not exist
			context=(Context) context.setHolding(address,result);
			if (context.isExceptional()) return (Context<ACell>) context;
			
			return context.withResult(Juice.ASSOC, result);
		}
	});
	
	public static final CoreFn<ACell> SET_CONTROLLER = reg(new CoreFn<>(Symbols.SET_CONTROLLER) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));
			
			// Get requested controller. Must be a valid address or null
			Address controller=RT.address(args[0]);
			if ((controller == null)&&(args[0]!=null)) return context.withCastError(args[0], Address.class);
			
			context=(Context) context.setController(controller);
			if (context.isExceptional()) return (Context<ACell>) context;
			
			return context.withResult(Juice.ASSOC, controller);
		}
	});
	
	public static final CoreFn<AccountKey> SET_KEY = reg(new CoreFn<>(Symbols.SET_KEY) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AccountKey> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));
			
			// Get requested controller. Must be a valid address or null
			AccountKey publicKey=RT.accountKey(args[0]);
			if ((publicKey == null)&&(args[0]!=null)) return context.withCastError(args[0], AccountKey.class);
			
			context=(Context) context.setAccountKey(publicKey);
			if (context.isExceptional()) return (Context<AccountKey>) context;
			
			return context.withResult(Juice.ASSOC, publicKey);
		}
	});


	public static final CoreFn<ACell> GET = reg(new CoreFn<>(Symbols.GET) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int n = args.length;
			if ((n < 2) || (n > 3)) {
				return context.withArityError(name() + " requires exactly 2 or 3 arguments");
			}

			ACell result;
			ACell coll = (ACell) args[0];
			if (coll == null) {
				// Treat nil as empty collection with no keys
				result = (n == 3) ? (ACell)args[2] : null;
			} else if (n == 2) {
				IGet<ACell> gettable = RT.toGettable(coll);
				if (gettable == null) return context.withCastError(coll, IGet.class);
				result = gettable.get(args[1]);
			} else {
				IGet<ACell> gettable = RT.toGettable(coll);
				if (gettable == null) return context.withCastError(coll, IGet.class);
				result = gettable.get(args[1], args[2]);
			}
			long juice = Juice.GET;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> GET_IN = reg(new CoreFn<>(Symbols.GET_IN) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ASequence<ACell> ixs = RT.sequence(args[1]);
			if (ixs == null) return context.withCastError(args[1], ASequence.class);

			int il = ixs.size();
			long juice = Juice.GET * (1L + il);
			ACell result = (ACell) args[0];
			for (int i = 0; i < il; i++) {
				if (result == null) break; // gets in nil produce nil
				IGet<ACell> gettable = RT.toGettable(result);
				if (gettable == null) return context.withCastError(result, IGet.class);
				result = gettable.get(ixs.get(i));
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMBool> CONTAINS_KEY_Q = reg(new CoreFn<>(Symbols.CONTAINS_KEY_Q) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(exactArityMessage(2, n));

			CVMBool result;
			ACell coll = args[0];
			if (coll == null) {
				result = CVMBool.FALSE; // treat nil as empty collection
			} else {
				IGet<ACell> gettable = RT.toGettable(args[0]);
				if (gettable == null) return context.withCastError(args[0], IGet.class);
				result = RT.toBoolean(gettable.containsKey((ACell) args[1]));
			}

			long juice = Juice.GET;
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<CVMBool> SUBSET_Q = reg(new CoreFn<>(Symbols.SUBSET_Q) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(exactArityMessage(2, n));

			Set<ACell> s0=RT.ensureSet(args[0]);
			if (s0==null) return context.withCastError(args[0], ASet.class);
			
			long juice = Juice.SET_COMPARE_PER_ELEMENT*s0.count();
			if (!context.checkJuice(juice)) return context.withJuiceError();
			
			Set<ACell> s1=RT.ensureSet(args[1]);
			if (s1==null) return context.withCastError(args[1], ASet.class);

			CVMBool result=RT.toBoolean(s0.isSubset(s1));
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AMap<?, ?>> DISSOC = reg(new CoreFn<>(Symbols.DISSOC) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AMap<?, ?>> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			AMap<ACell, ACell> result = RT.toMap(args[0]);
			if (result == null) return context.withCastError(args[0], AMap.class);

			for (int i = 1; i < n; i++) {
				result = result.dissoc((ACell) args[i]);
			}
			long juice = Juice.BUILD_DATA + (n - 1) * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ADataStructure<ACell>> CONJ = reg(new CoreFn<>(Symbols.CONJ) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ADataStructure<ACell>> invoke(Context context, ACell[] args) {
			int numAdditions = args.length - 1;
			if (args.length <= 0) return context.withArityError(name() + " requires a collection as first argument");

			// compute juice up front
			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * numAdditions;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ADataStructure<ACell> result = RT.dataStructure(args[0]);
			if (result == null) return context.withCastError(args[0], ADataStructure.class);

			for (int i = 0; i < numAdditions; i++) {
				ACell val = (ACell) args[i + 1];
				result = result.conj(val);
				if (result == null) return context.withCastError(val, MapEntry.class); // must be a failed map conj?
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> DISJ = reg(new CoreFn<>(Symbols.DISJ) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ASet<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ASet<ACell> result = RT.toSet(args[0]);
			if (result == null) return context.withCastError(args[0], ASet.class);

			result = result.exclude((ACell) args[1]);
			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AList<ACell>> CONS = reg(new CoreFn<>(Symbols.CONS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<AList<ACell>> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (args.length < 2) return context.withArityError(minArityMessage(2, args.length));

			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * (n - 1);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			// get sequence from last argument
			ASequence<?> seq = RT.sequence(args[n - 1]);
			if (seq == null) return context.withCastError(seq, ASequence.class);

			AList<ACell> list = RT.cons((ACell) args[n - 2], seq);

			for (int i = n - 3; i >= 0; i--) {
				list = RT.cons((ACell)args[i], list);
			}
			return context.withResult(juice, list);
		}
	});

	public static final CoreFn<ACell> FIRST = reg(new CoreFn<>(Symbols.FIRST) {
		// note we could define this as (nth coll 0) but this is more efficient
		
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell coll = args[0];
			ASequence<?> seq = RT.sequence(coll);
			if (seq == null) return context.withCastError(coll, ASequence.class);
			if (seq.count()<1) return context.withBoundsError(0);
			ACell result = seq.get(0);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> SECOND = reg(new CoreFn<>(Symbols.SECOND) {
		// note we could define this as (nth coll 1) but this is more efficient
		
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = (ACell) args[0];
			ASequence<?> seq = RT.sequence(a);
			if (seq == null) return context.withCastError(a, ASequence.class);
			if (seq.count()<2) return context.withBoundsError(1);
			ACell result = seq.get(1);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> LAST = reg(new CoreFn<>(Symbols.LAST) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];

			Long n = RT.count(a);
			if (n == null) return context.withCastError(a, ASequence.class);
			if (n==0) return context.withBoundsError(-1);
			
			ACell result = RT.nth(a,n-1);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMBool> EQUALS = reg(new CoreFn<>(Symbols.EQUALS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {

			// all arities OK, all args OK
			CVMBool result = RT.toBoolean(RT.allEqual(args));
			return context.withResult(Juice.EQUALS, result);
		}
	});

	public static final CoreFn<CVMBool> EQ = reg(new CoreFn<>(Symbols.EQ) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			// all arities OK, but need to watch for non-numeric arguments
			Boolean result = RT.eq(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, CVMBool.create(result));
		}
	});

	public static final CoreFn<CVMBool> GE = reg(new CoreFn<>(Symbols.GE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			// all arities OK
			Boolean result = RT.ge(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, CVMBool.create(result));
		}
	});

	public static final CoreFn<CVMBool> GT = reg(new CoreFn<>(Symbols.GT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			// all arities OK

			Boolean result = RT.gt(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, CVMBool.create(result));
		}
	});

	public static final CoreFn<CVMBool> LE = reg(new CoreFn<>(Symbols.LE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			// all arities OK

			Boolean result = RT.le(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, CVMBool.create(result));
		}
	});

	public static final CoreFn<CVMBool> LT = reg(new CoreFn<>(Symbols.LT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			// all arities OK

			Boolean result = RT.lt(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);

			return context.withResult(Juice.NUMERIC_COMPARE, CVMBool.create(result));
		}
	});

	public static final CoreFn<CVMLong> INC = reg(new CoreFn<>(Symbols.INC) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMLong result = RT.inc(a);
			if (result == null) return context.withCastError(a, Long.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMLong> DEC = reg(new CoreFn<>(Symbols.DEC) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMLong result = RT.dec(a);
			if (result == null) return context.withCastError(a, Long.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMBool> BOOLEAN = reg(new CoreFn<>(Symbols.BOOLEAN) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// always works for any value
			CVMBool result = RT.toBoolean(args[0]);

			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});
	
	public static final CorePred BOOLEAN_Q = reg(new CorePred(Symbols.BOOLEAN_Q) {
		@Override
		public boolean test(ACell val) {
			// TODO Auto-generated method stub
			return RT.isBoolean(val);
		}
	});
	
	public static final CoreFn<ABlob> ENCODING = reg(new CoreFn<>(Symbols.ENCODING) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ABlob> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			ABlob encoding=Format.encodedBlob(a);

			long juice=Juice.addMul(Juice.BLOB, encoding.length(), Juice.BLOB_PER_BYTE);
			return context.withResult(juice, encoding);
		}
	});

	public static final CoreFn<CVMLong> LONG = reg(new CoreFn<>(Symbols.LONG) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMLong result = RT.toLong(a);
			if (result == null) return context.withCastError(a, Long.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> DOUBLE = reg(new CoreFn<>(Symbols.DOUBLE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMDouble result = RT.toDouble(a);
			if (result == null) return context.withCastError(a, Double.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMChar> CHAR = reg(new CoreFn<>(Symbols.CHAR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMChar> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMChar result = RT.toCharacter(a);
			if (result == null) return context.withCastError(a, Character.class);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMByte> BYTE = reg(new CoreFn<>(Symbols.BYTE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMByte> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMByte result = RT.toByte(a);
			if (result == null) return context.withCastError(a, Byte.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> PLUS = reg(new CoreFn<>(Symbols.PLUS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<APrimitive> invoke(Context context, ACell[] args) {
			// All arities OK

			APrimitive result = RT.plus(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> MINUS = reg(new CoreFn<>(Symbols.MINUS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<APrimitive> invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));
			APrimitive result = RT.minus(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> TIMES = reg(new CoreFn<>(Symbols.TIMES) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<APrimitive> invoke(Context context, ACell[] args) {
			// All arities OK
			APrimitive result = RT.times(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> DIVIDE = reg(new CoreFn<>(Symbols.DIVIDE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			CVMDouble result = RT.divide(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMDouble> FLOOR = reg(new CoreFn<>(Symbols.FLOOR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMDouble result = RT.floor(args[0]);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	
	public static final CoreFn<CVMDouble> CEIL = reg(new CoreFn<>(Symbols.CEIL) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMDouble result = RT.ceil(args[0]);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});


	public static final CoreFn<CVMDouble> SQRT = reg(new CoreFn<>(Symbols.SQRT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMDouble result = RT.sqrt(args[0]);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<APrimitive> ABS = reg(new CoreFn<>(Symbols.ABS) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<APrimitive> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			APrimitive result = RT.abs(args[0]);
			if (result == null) return context.withCastError(RT.findNonNumeric(args), Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> SIGNUM = reg(new CoreFn<>(Symbols.SIGNUM) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMLong result = RT.signum(args[0]);
			if (result == null) return context.withCastError(args[0], Number.class);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> MOD = reg(new CoreFn<>(Symbols.MOD) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
			
			CVMLong la=RT.toLong(args[0]);
			CVMLong lb=RT.toLong(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(args, CVMLong.class);
			
			long num = la.longValue();
			long denom = lb.longValue();
			if (denom==0) return context.withArgumentError("Divsion by zero in "+name());
			
			long m = num % denom;
			if (m<0) m+=Math.abs(denom); // Correct for Euclidean modular function
			CVMLong result=CVMLong.create(m);
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> REM = reg(new CoreFn<>(Symbols.REM) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
			
			CVMLong la=RT.toLong(args[0]);
			CVMLong lb=RT.toLong(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(args, CVMLong.class);
			
			long num = la.longValue();
			long denom = lb.longValue();
			if (denom==0) return context.withArgumentError("Divsion by zero in "+name());
			
			long m = num % denom;
			CVMLong result=CVMLong.create(m);
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> QUOT = reg(new CoreFn<>(Symbols.QUOT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
			
			CVMLong la=RT.toLong(args[0]);
			CVMLong lb=RT.toLong(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(args, CVMLong.class);
			
			long num = la.longValue();
			long denom = lb.longValue();
			if (denom==0) return context.withArgumentError("Divsion by zero in "+name());
			
			long m = num / denom;
			CVMLong result=CVMLong.create(m);
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});


	public static final CoreFn<CVMDouble> POW = reg(new CoreFn<>(Symbols.POW) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
			
			CVMDouble result = RT.pow(args);
			if (result==null) return context.withCastError(args, CVMDouble.class);
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> EXP = reg(new CoreFn<>(Symbols.EXP) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMDouble> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			
			CVMDouble result = RT.exp(args[0]);
			if (result==null) return context.withCastError(args, CVMDouble.class);
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMBool> NOT = reg(new CoreFn<>(Symbols.NOT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			CVMBool result = RT.toBoolean(!RT.bool(args[0]));
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<Hash> HASH = reg(new CoreFn<>(Symbols.HASH) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<Hash> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
 
			ABlob blob=RT.ensureBlob(args[0]);
			if (blob==null) return context.withCastError(args[0], ABlob.class);
			
			Hash result = blob.getContentHash();
			return context.withResult(Juice.HASH, result);
		}
	});

	public static final CoreFn<CVMLong> COUNT = reg(new CoreFn<>(Symbols.COUNT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMLong> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Long result = RT.count(args[0]);
			if (result == null) return context.withCastError(args[0], ADataStructure.class);

			return context.withResult(Juice.SIMPLE_FN, CVMLong.create(result));
		}
	});

	public static final CoreFn<ACell> EMPTY = reg(new CoreFn<>(Symbols.EMPTY) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell o = args[0];

			// emptying nil is still nil
			if (o == null) return context.withResult(Juice.SIMPLE_FN, null);

			ADataStructure<?> coll = RT.ensureDataStructure(o);
			if (coll == null) return context.withCastError(o, ADataStructure.class);

			ACell result = coll.empty();
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<ACell> NTH = reg(new CoreFn<>(Symbols.NTH) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			// Arity 2
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			// First argument must be a Long index
			ACell arg = (ACell) args[0];
			CVMLong ix = RT.toLong(args[1]);
			if (ix == null) return context.withCastError(args[1], Long.class);
			
			// Second arg should be a countable data structure
			Long n = RT.count(arg);
			if (n == null) return context.withCastError(arg, ASequence.class);
			
			long i=ix.longValue();
			
			// BOUNDS error if access is out of bounds
			if ((i < 0) || (i >= n)) return context.withBoundsError(i);

			// We know the object is a countable collection
			ACell result = RT.nth(arg, i);
			
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<ASequence<ACell>> NEXT = reg(new CoreFn<>(Symbols.NEXT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ASequence<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ASequence<ACell> seq = RT.sequence(args[0]);
			if (seq == null) return context.withCastError(args[0], ASequence.class);

			ASequence<ACell> result = seq.next();
			// TODO: probably needs to cost a lot?
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreExpander IF = reg(new CoreExpander(Symbols.IF) {
		@Override
		public Context<Syntax> expand(ACell o, AExpander cont, Context<?> context) {
			if (o instanceof Syntax) {
				o = ((Syntax) o).getValue();
			}

			@SuppressWarnings("unchecked")
			AList<ACell> form = (AList<ACell>) o;
			int n = form.size();
			if (n < 3) return context.withArityError("if requires at least two expressions but got: " + form);
			if (n > 4) return context.withArityError("if requires at most three expressions but got: " + form);

			ACell newForm = RT.cons(Symbols.COND, form.next());
			context = context.consumeJuice(Juice.SIMPLE_MACRO);
			return context.expand(newForm, cont, cont);
		}
	});

	public static final CoreExpander MACRO = reg(new CoreExpander(Symbols.MACRO) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<Syntax> expand(ACell o, AExpander cont, Context<?> context) {
			// wrap form in Syntax object if needed
			Syntax formSyntax=Syntax.create(o);
			AList<ACell> form = (AList<ACell>) formSyntax.getValue();
			
			int n = form.size();
			if (n != 3) {
				return context.withCompileError("macro requires a binding form and expansion expression " + form);
			}

			ACell paramForm = form.get(1);
			ACell body = form.get(2);

			// expansion function is: (fn [x e] (let [<paramForm> (next (unsyntax x))] <body>))
			List expansionFn = List.create(new ACell[] {
					Symbols.FN, Vectors.of(Symbols.X, Symbols.E),
					Lists.of(Symbols.LET, Vectors.of(paramForm, Lists.of(Symbols.NEXT, Lists.of(Symbols.UNSYNTAX,Symbols.X))), 
							body)});

			ACell newForm = Syntax.create(Lists.of(Symbols.EXPANDER, expansionFn)).withMeta(formSyntax.getMeta());
			context = context.consumeJuice(Juice.SIMPLE_MACRO);
			return context.expand(newForm, cont, cont);
		}
	});

	public static final CoreFn<?> RECUR = reg(new CoreFn<>(Symbols.RECUR) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			// any arity OK?

			AExceptional result = RecurValue.wrap(args);

			return context.withException(Juice.RECUR, result);
		}
	});

	public static final CoreFn<?> ROLLBACK = reg(new CoreFn<>(Symbols.ROLLBACK) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = RollbackValue.wrap((ACell)args[0]);

			return context.withException(Juice.ROLLBACK, result);
		}
	});

	public static final CoreFn<?> HALT = reg(new CoreFn<>(Symbols.HALT) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n > 1) return context.withArityError(this.maxArityMessage(1, n));

			AExceptional result = HaltValue.wrap((n > 0) ? (ACell)args[0] : null);

			return context.withException(Juice.HALT, result);
		}
	});

	public static final CoreFn<?> RETURN = reg(new CoreFn<>(Symbols.RETURN) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = ReturnValue.wrap((ACell)args[0]);
			return context.withException(Juice.RETURN, result);
		}
	});

	public static final CoreFn<CVMBool> FAIL = reg(new CoreFn<>(Symbols.FAIL) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<CVMBool> invoke(Context context, ACell[] args) {
			int alen = args.length;
			if (alen > 2) return context.withArityError(maxArityMessage(2, alen));

			// default to :ASSERT if no error code provided. Error code cannot be nil.
			ACell code = (alen == 2) ? (ACell)args[0] : ErrorCodes.ASSERT;
			if (code==null) return context.withError(ErrorCodes.ARGUMENT,"Error code cannot be nil");

			// get message, or nil if not provided
			ACell message = (alen >0) ? (ACell)args[alen-1] : null;
			ErrorValue error = ErrorValue.createRaw(code, message);

			return context.withException(error);
		}
	});

	public static final CoreFn<?> APPLY = reg(new CoreFn<>(Symbols.APPLY) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			int alen = args.length;
			if (alen < 2) return context.withArityError(minArityMessage(2, alen));

			IFn<ACell> fn = RT.function(args[0]);

			ACell lastArg = args[alen - 1];
			ASequence<ACell> coll = RT.ensureSequence(lastArg);
			if (coll == null) return context.withCastError(lastArg, ASequence.class);

			int vlen = coll.size(); // variable arg length

			// Build an array of arguments for the function
			// TODO: bounds on number of arguments?
			int n = (alen - 2) + vlen; // number of args to pass to function
			ACell[] applyArgs;
			if (alen > 2) {
				applyArgs = new ACell[n];
				for (int i = 0; i < (alen - 2); i++) {
					applyArgs[i] = args[i + 1];
				}
				int ix = alen - 2;
				for (Iterator<ACell> it = coll.iterator(); it.hasNext();) {
					applyArgs[ix++] = it.next();
				}
			} else {
				applyArgs = coll.toCellArray();
			}
			
			if (fn==null ) return context.withCastError(args[0], IFn.class);

			Context<ACell> rctx = context.invoke(fn, applyArgs);
			return rctx.consumeJuice(Juice.APPLY);
		}
	});

	public static final CoreFn<ADataStructure<ACell>> INTO = reg(new CoreFn<>(Symbols.INTO) {

		@SuppressWarnings("unchecked")
		@Override
		public  Context<ADataStructure<ACell>> invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ACell a0 = args[0];
			ADataStructure<ACell> result = RT.ensureDataStructure(a0);
			if ((a0 != null) && (result == null)) return context.withCastError(args[0], ADataStructure.class);

			long juice = Juice.BUILD_DATA;
			ACell a1 = args[1];
			if (a0 == null) {
				// just keep second arg as complete data structure
				result = RT.ensureDataStructure(a1);
				if ((a1 != null) && (result == null)) return context.withCastError(a1, ADataStructure.class);
			} else {
				ASequence<ACell> seq = RT.sequence(a1);
				if (seq == null) return context.withCastError(a1, ADataStructure.class);
				long n = seq.count();
				
				// check juice before running expensive part
				juice += Juice.BUILD_PER_ELEMENT * n;
				if (!context.checkJuice(juice)) return context.withJuiceError();

				result = result.conjAll(seq);
				if (result == null) return context.withError(ErrorCodes.CAST,"Invalid element type for 'into'");
			}

			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<AHashMap<ACell,ACell>> MERGE = reg(new CoreFn<>(Symbols.MERGE) {

		@SuppressWarnings("unchecked")
		@Override
		public  Context<AHashMap<ACell, ACell>> invoke(Context context, ACell[] args) {
			int n=args.length;
			if (n==0) return context.withResult(Juice.BUILD_DATA,Maps.empty());
			
			// TODO: handle blobmaps?
			
			ACell arg0=args[0];
			AHashMap<ACell,ACell> result=RT.ensureHashMap(arg0);
			if (result == null) return context.withCastError(arg0, AHashMap.class);
			
			long juice=Juice.BUILD_DATA;
			for (int i=1; i<n; i++) {
				ACell argi=args[i];
				AHashMap<ACell,ACell> argMap=RT.ensureHashMap(argi);
				if (argMap == null) return context.withCastError(argi, AHashMap.class);
				
				long size=argMap.count();
				juice=Juice.addMul(juice,size,Juice.BUILD_PER_ELEMENT);
				
				if (!context.checkJuice(juice)) return context.withJuiceError();
				
				result=result.merge(argMap);
			}
			
			return context.withResult(juice, result);
		}
		
	});

	public static final CoreFn<ASequence<?>> MAP = reg(new CoreFn<>(Symbols.MAP) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ASequence<?>> invoke(Context context, ACell[] args) {
			if (args.length < 2) return context.withArityError(minArityMessage(2, args.length));

			// check and cast first argument to a function
			ACell fnArg = args[0];
			IFn<?> f = RT.function(fnArg);
			if (f == null) return context.withCastError(fnArg, IFn.class);

			// remaining arguments determine function arity to use
			int fnArity = args.length - 1;
			ACell[] xs = new ACell[fnArity];
			ASequence<?>[] seqs = new ASequence[fnArity];

			int length = Integer.MAX_VALUE;
			for (int i = 0; i < fnArity; i++) {
				ACell maybeSeq = args[1 + i];
				ASequence<?> seq = RT.sequence(maybeSeq);
				if (seq == null) return context.withCastError(maybeSeq, ASequence.class);
				seqs[i] = seq;
				length = Math.min(length, seq.size());
			}

			final long juice = Juice.addMul(Juice.MAP, Juice.BUILD_DATA , length);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ArrayList<ACell> al = new ArrayList<>();
			for (int i = 0; i < length; i++) {
				for (int j = 0; j < fnArity; j++) {
					xs[j] = seqs[j].get(i);
				}
				context = (Context) context.invoke(f, xs);
				if (context.isExceptional()) return (Context<ASequence<?>>) context;
				ACell r = context.getResult();
				al.add(r);
			}

			ASequence<?> result = Vectors.create(al);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> REDUCE = reg(new CoreFn<>(Symbols.REDUCE) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context ctx, ACell[] args) {
			if (args.length != 3) return ctx.withArityError(exactArityMessage(3, args.length));

			// check and cast first argument to a function
			ACell fnArg = args[0];
			IFn<?> fn = RT.function(fnArg);
			if (fn == null) return ctx.withCastError(fnArg, IFn.class);

			// Initial value
			ACell result = (ACell) args[1];

			ACell maybeSeq = (ACell) args[2];
			ASequence<?> seq = RT.sequence(maybeSeq);
			if (seq == null) return ctx.withCastError(maybeSeq, ASequence.class);

			long c = seq.count();
			ACell[] xs = new ACell[2]; // accumulator, next element

			Context<ACell> rc=(Context<ACell>) ctx;
			for (long i = 0; i < c; i++) {
				xs[0] = result;
				xs[1] = seq.get(i);
				rc = (Context<ACell>) rc.invoke(fn, xs);
				if (rc.isExceptional()) {
					AExceptional ex=rc.getExceptional();
				 	if (ex instanceof Reduced) {
				 		result=((Reduced)ex).getValue();
				 		break;
				 	}
				 	return rc;
				} else {
					result=rc.getResult();
				}
			}

			return rc.withResult(Juice.REDUCE, result);
		}
	});
	
	public static final CoreFn<ACell> REDUCED = reg(new CoreFn<>(Symbols.REDUCED) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context<ACell> invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = Reduced.wrap((ACell) args[0]);
			return context.withException(Juice.REDUCED, result);
		}
	});

	// =====================================================================================================
	// Predicates

	public static final CorePred NIL_Q = reg(new CorePred(Symbols.NIL_Q) {
		@Override
		public boolean test(ACell val) {
			return val == null;
		}
	});

	public static final CorePred VECTOR_Q = reg(new CorePred(Symbols.VECTOR_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AVector;
		}
	});

	public static final CorePred LIST_Q = reg(new CorePred(Symbols.LIST_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AList;
		}
	});

	public static final CorePred SET_Q = reg(new CorePred(Symbols.SET_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof ASet;
		}
	});

	public static final CorePred MAP_Q = reg(new CorePred(Symbols.MAP_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AMap;
		}
	});

	public static final CorePred COLL_Q = reg(new CorePred(Symbols.COLL_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof ADataStructure;
		}
	});

	public static final CorePred EMPTY_Q = reg(new CorePred(Symbols.EMPTY_Q) {
		@Override
		public boolean test(ACell val) {
			// consider null as an empty object
			// like with clojure
			if (val == null) return true;

			return (val instanceof ADataStructure) && ((ADataStructure<?>) val).isEmpty();
		}
	});

	public static final CorePred SYMBOL_Q = reg(new CorePred(Symbols.SYMBOL_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Symbol;
		}
	});

	public static final CorePred KEYWORD_Q = reg(new CorePred(Symbols.KEYWORD_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Keyword;
		}
	});

	public static final CorePred BLOB_Q = reg(new CorePred(Symbols.BLOB_Q) {
		@Override
		public boolean test(ACell val) {
			if (!(val instanceof ABlob)) return false;
			return ((ABlob)val).isRegularBlob();
		}
	});

	public static final CorePred ADDRESS_Q = reg(new CorePred(Symbols.ADDRESS_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Address;
		}
	});
	
	public static final CorePred HASH_Q = reg(new CorePred(Symbols.HASH_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Hash;
		}
	});

	public static final CorePred LONG_Q = reg(new CorePred(Symbols.LONG_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof CVMLong;
		}
	});

	public static final CorePred STR_Q = reg(new CorePred(Symbols.STR_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AString;
		}
	});

	public static final CorePred NUMBER_Q = reg(new CorePred(Symbols.NUMBER_Q) {
		@Override
		public boolean test(ACell val) {
			return RT.isNumber(val);
		}
	});

	public static final CorePred FN_Q = reg(new CorePred(Symbols.FN_Q) {
		@Override
		public boolean test(ACell val) {
			return val instanceof IFn;
		}
	});

	public static final CorePred ZERO_Q = reg(new CorePred(Symbols.ZERO_Q) {
		@Override
		public boolean test(ACell val) {
			if (!RT.isNumber(val)) return false;
			Number n = RT.number(val);

			// According to the IEEE 754 standard, negative zero and positive zero should
			// compare as equal with the usual (numerical) comparison operators
			// This is the behaviour in Java
			return n.doubleValue() == 0.0;
		}
	});



	// =====================================================================================================
	// Core environment generation

	static Symbol symbolFor(ACell o) {
		if (o instanceof CoreFn) return ((CoreFn<?>) o).getSymbol();
		if (o instanceof CoreExpander) return ((CoreExpander) o).getSymbol();
		throw new Error("Cant get symbol for object of type " + o.getClass());
	}

	private static AHashMap<Symbol, Syntax> register(AHashMap<Symbol, Syntax> env, ACell o) {
		Symbol sym = symbolFor(o);
		assert (!env.containsKey(sym)) : "Duplicate core declaration: " + sym;
		return env.assoc(sym, Syntax.create(o));
	}

	/**
	 * Bootstrap procedure to load the core.con library
	 * 
	 * @param env Initial environment map
	 * @return Loaded environment map
	 * @throws IOException
	 */
	private static AHashMap<Symbol, Syntax> registerCoreCode(AHashMap<Symbol, Syntax> env) throws IOException {
		// we use a fake State to build the initial environment with core address
		Address ADDR=Address.ZERO;
		State state = State.EMPTY.putAccount(ADDR,
				AccountStatus.createActor(env));
		Context<?> ctx = Context.createFake(state, ADDR);

		Syntax form = null;
		
		// Compile and execute forms in turn. Later definitions can use earlier macros!
		AList<Syntax> forms = Reader.readAllSyntax(Utils.readResourceAsString("lang/core.con"));
		for (Syntax f : forms) {
			form = f;
			ctx=ctx.expandCompile(form);
			if (ctx.isExceptional()) {
				throw new Error("Error compiling form: "+ Syntax.unwrapAll(form)+ " : "+ ctx.getExceptional());
			}
			AOp<?> op=(AOp<?>) ctx.getResult();
			ctx = ctx.execute(op);
			// System.out.println("Core compilation juice: "+ctx.getJuice());
			assert (!ctx.isExceptional()) : "Error executing op: "+ op+ " : "+ ctx.getExceptional();
			
			try {
				ctx.getEnvironment().getHash();
			} catch (Throwable t) {
				throw t;
			}
			
		}

		return ctx.getAccountStatus(ADDR).getEnvironment();
	}

	private static AHashMap<Symbol, Syntax> registerSpecials(AHashMap<Symbol, Syntax> env) {
		// Replaced with ##NaN
		// env = env.assoc(Symbols.NAN, Syntax.create(CVMDouble.create(Double.NaN)));
		return env;
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<Symbol, Syntax> applyDocumentation(AHashMap<Symbol, Syntax> env) throws IOException {
		AMap<Symbol, AHashMap<ACell, ACell>> m = Reader.read(Utils.readResourceAsString("lang/core-metadata.doc"));
		for (Map.Entry<Symbol, AHashMap<ACell, ACell>> de : m.entrySet()) {
			try {
				Symbol sym = de.getKey();
				AHashMap<ACell, ACell> newMeta = de.getValue();
				MapEntry<Symbol, Syntax> me = env.getEntry(sym);
				if (me == null) {
					AHashMap<Keyword, ACell> doc=(AHashMap<Keyword, ACell>) newMeta.get(Keywords.DOC);
					if (doc==null) {
						System.err.println("CORE WARNING: Missing :doc tag in metadata for: " + sym);
					} else if (me==null) {
						if (Keywords.SPECIAL.equals(doc.get(Keywords.TYPE))) {
							// create a fake entry
							me=MapEntry.create(sym, Syntax.create(sym,newMeta));		
						} else {
							System.err.println("CORE WARNING: Documentation for non-existent core symbol: " + sym);
							continue;
						}
					}
				}
	
				Syntax oldSyn = me.getValue();
				Syntax newSyn = oldSyn.mergeMeta(newMeta);
				env = env.assoc(sym, newSyn);
			} catch (Throwable t) {
				throw new Error("Error apply documentation: "+de,t);
			}
		}

		return env;
	}

	static {
		// Set up convex.core environment
		AHashMap<Symbol, Syntax> coreEnv = Maps.empty();
		
		try {

			// Register all objects from registered runtime
			for (ACell o : tempReg) {
				coreEnv = register(coreEnv, o);
			}

			coreEnv = registerCoreCode(coreEnv);
			coreEnv = registerSpecials(coreEnv);
			
			coreEnv = applyDocumentation(coreEnv);
		} catch (Throwable e) {
			e.printStackTrace();
			throw new Error("Error initialising core!",e);
		}
		
		CORE_NAMESPACE = coreEnv;

		// Default environment is empty. Empty aliases resolve to CORE_NAMESPACE
		AHashMap<Symbol, Syntax> defaultEnv = Maps.empty();
		
		ENVIRONMENT = defaultEnv;
	}
}
