package convex.core.lang;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.State;
import convex.core.crypto.Hashing;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.ACountable;
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
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.List;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.APrimitive;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.exception.AExceptional;
import convex.core.lang.exception.ErrorValue;
import convex.core.lang.exception.HaltValue;
import convex.core.lang.exception.RecurValue;
import convex.core.lang.exception.ReducedValue;
import convex.core.lang.exception.ReturnValue;
import convex.core.lang.exception.RollbackValue;
import convex.core.lang.exception.TailcallValue;
import convex.core.lang.impl.CoreFn;
import convex.core.lang.impl.CorePred;
import convex.core.lang.impl.ICoreDef;
import convex.core.lang.ops.Special;
import convex.core.util.Errors;
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
 * functions available in the CVM implementation, but also need to account for:
 * <ul>
 * <li>Argument checking </li>
 * <li>Exceptional case handling</li>
 * <li>Appropriate juice costs</li>
 * </ul>
 *
 * Where possible, we implement core functions in Convex Lisp itself, see
 * resources/lang/core.cvx
 *
 * "Java is the most distressing thing to hit computing since MS-DOS." - Alan
 * Kay
 */
@SuppressWarnings("rawtypes")
public class Core {
	/**
	 * The constant core address for Convex V1
	 */
	public static final Address CORE_ADDRESS = Address.create(8);

	/**
	 * Default initial environment metadata importing core namespace
	 */
	public static final AHashMap<Symbol, ACell> ENVIRONMENT;

	/**
	 * Default initial core metadata
	 */
	public static final AHashMap<Symbol, AHashMap<ACell,ACell>> METADATA;
	
	/**
	 * Mapping from implicit symbols like #%count to core definitions
	 */
	public static final HashMap<Symbol, ACell> CORE_FORMS=new HashMap<>();

	/**
	 * Symbol for core namespace
	 */
	public static final Symbol CORE_SYMBOL = Symbol.create("convex.core");

	private static final HashSet<ACell> tempReg = new HashSet<ACell>();
	
	private static final ACell[] CODE_MAP=new ACell[512];

	/**
	 * Register an intrinsic core value
	 * @param <T>
	 * @param o
	 * @return
	 */
	private static <T extends ACell> T reg(T o) {
		o=Cells.intern(o);
		if (tempReg.contains(o)) throw new Error("Duplicate core form! = "+o);
		tempReg.add(o);
		
		if (o instanceof ICoreDef) {
			ICoreDef cd=(ICoreDef)o;
			Symbol stm=cd.getSymbol();
			
			
			
			Symbol implicitForm=Symbol.create("#%"+stm.getName().toString());
			CORE_FORMS.put(implicitForm, o);
		} else {
			System.err.println("Not a core Def: "+o);
		}
		
		return o;
	}

	public static final CoreFn<AVector<ACell>> VECTOR = reg(new CoreFn<>(Symbols.VECTOR,1) {
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Need to charge juice on per-element basis
			long juice = Juice.BUILD_DATA + args.length * Juice.BUILD_PER_ELEMENT;

			// Check juice before building a big vector.
			// OK to fail early since will fail with JUICE anyway if vector is too big.
			if (!context.checkJuice(juice)) return context.withJuiceError();

			// Build and return requested vector
			AVector<ACell> result = Vectors.wrap(args);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASequence<ACell>> CONCAT = reg(new CoreFn<>(Symbols.CONCAT,2) {
		@Override
		public Context invoke(Context context, ACell[] args) {
			ASequence<?> result = null;
			int n=args.length;

			// initial juice is a load of null
			long juice = Juice.CONSTANT;
			for (int ix=0; ix<n; ix++) {
				ACell a=args[ix];
				if (a == null) continue;
				ASequence<?> seq = RT.sequence(a);
				if (seq == null) return context.withCastError(ix,args, Types.SEQUENCE);

				// check juice per element of concatenated sequences
				juice += Juice.BUILD_DATA+ seq.count() * Juice.BUILD_PER_ELEMENT;
				if (!context.checkJuice(juice)) return context.withJuiceError();
				result = RT.concat(result, seq);
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AVector<ACell>> VEC = reg(new CoreFn<>(Symbols.VEC,3) {
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1 exactly
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell o = args[0];

			// Need to compute juice before building potentially big vector
			Long n = RT.count(o);
			if (n == null) return context.withCastError(0,args, Types.VECTOR);

			long juice = Juice.BUILD_DATA + n * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<?> result = RT.castVector(o);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AVector<ACell>> REVERSE = reg(new CoreFn<>(Symbols.REVERSE,4) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1 exactly
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell o = args[0];

			// Need to compute juice before building potentially big vector
			ASequence<ACell> seq = RT.ensureSequence(o);
			if (seq == null) return context.withCastError(0,args, Types.SEQUENCE);

			long juice = Juice.BUILD_DATA;

			ASequence<ACell> result = seq.reverse();
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> SET = reg(new CoreFn<>(Symbols.SET,5) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell o = args[0];

			// Need to compute juice before building a potentially big set
			Long n = RT.count(o);
			if (n == null) return context.withCastError(0,args, Types.SEQUENCE);
			long juice = Juice.addMul(Juice.BUILD_DATA ,n,Juice.BUILD_PER_ELEMENT);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ASet<?> result = RT.castSet(o);
			if (result == null) return context.withCastError(0,args, Types.SET);

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> UNION = reg(new CoreFn<>(Symbols.UNION,6) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n=args.length;
			ASet<ACell> result=Sets.empty();

			long juice=Juice.BUILD_DATA;

			for (int i=0; i<n; i++) {
				ACell arg=args[i];
				ASet<ACell> set=RT.ensureSet(arg);
				if (set==null) return context.withCastError(i,args, Types.SET);

				// check juice before expensive operation
				long size=set.count();
				juice = Juice.addMul(juice, size, Juice.BUILD_PER_ELEMENT);
				if (!context.checkJuice(juice)) return context.withJuiceError();

				result=result.includeAll(set);
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> INTERSECTION = reg(new CoreFn<>(Symbols.INTERSECTION,7) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length <1) return context.withArityError(minArityMessage(1, args.length));

			int n=args.length;
			ACell arg0=(ACell) args[0];
			ASet<ACell> result=(arg0==null)?Sets.empty():RT.ensureSet(arg0);
			if (result==null) return context.withCastError(0,args, Types.SET);

			long juice=Juice.BUILD_DATA;

			for (int i=1; i<n; i++) {
				ACell arg=(ACell) args[i];
				ASet<ACell> set=(arg==null)?Sets.empty():RT.ensureSet(args[i]);
				if (set==null) return context.withCastError(i,args, Types.SET);
				long size=set.count();

				juice = Juice.addMul(juice, size, Juice.BUILD_PER_ELEMENT);
				if (!context.checkJuice(juice)) return context.withJuiceError();

				result=result.intersectAll(set);
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> DIFFERENCE = reg(new CoreFn<>(Symbols.DIFFERENCE,8) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length <1) return context.withArityError(minArityMessage(1, args.length));

			int n=args.length;
			ACell arg0=args[0];
			ASet<ACell> result=(arg0==null)?Sets.empty():RT.ensureSet(arg0);
			if (result==null) return context.withCastError(0,args, Types.SET);

			long juice=Juice.BUILD_DATA;

			for (int i=1; i<n; i++) {
				ACell arg=args[i];
				ASet<ACell> set=RT.ensureSet(arg);
				if (set==null) return context.withCastError(i,args, Types.SET);
				long size=set.count();

				juice = Juice.addMul(juice, size, Juice.BUILD_PER_ELEMENT);
				if (!context.checkJuice(juice)) return context.withJuiceError();

				result=result.excludeAll(set);
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AList<ACell>> LIST = reg(new CoreFn<>(Symbols.LIST,9) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// Any arity is OK

			// Need to compute juice before building a potentially big list
			long juice = Juice.buildDataCost(args.length);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AList<ACell> result = List.create(args);
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AString> STR = reg(new CoreFn<>(Symbols.STR,10) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// TODO: pre-check juice? String rendering definitions?
			AString result = RT.str(args);
			if (result==null) return context.withCastError(Types.STRING);

			long juice = Juice.buildStringCost(result.count());
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<AString> PRINT = reg(new CoreFn<>(Symbols.PRINT,11) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			long limit=Juice.limitString(context); // calculate byte limit
			AString result = RT.print(args[0],limit);
			
			// Any CVM value should print, it must be a juice error if failed
			if (result==null) return context.withJuiceError();

			long juice = Juice.buildStringCost(result.count());
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<AString> SPLIT = reg(new CoreFn<>(Symbols.SPLIT,12) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AString str = RT.ensureString(args[0]);
			if (str==null) return context.withCastError(0,args, Types.STRING);
			
			CVMChar ch=RT.ensureChar(args[1]);
			if (ch==null) return context.withCastError(1,args, Types.CHARACTER);
			
			// Pre-check juice, but after we have validated args
			long strlen=str.count();
			long strcost=Juice.buildStringCost(strlen);
			if(!context.checkJuice(strcost)) return context.withJuiceError();
			
			AVector<AString> result=str.split(ch);

			long juice = strcost + Juice.buildDataCost(result.count());
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<AString> JOIN = reg(new CoreFn<>(Symbols.JOIN,13) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ASequence<AString> strs = RT.ensureSequence(args[0]);
			if (strs==null) return context.withCastError(0,args, Types.SEQUENCE);
			
			CVMChar ch=RT.ensureChar(args[1]);
			if (ch==null) return context.withCastError(1,args, Types.CHARACTER);

			// TODO: needs juice limit
			AString result=Strings.join(strs, ch);
			if (result==null) return context.withError(ErrorCodes.CAST,"Element in join is not a String.");

			long juice = Juice.buildStringCost(result.count());
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AString> NAME = reg(new CoreFn<>(Symbols.NAME,14) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Check can get as a String name
			ACell arg = args[0];
			AString result = RT.name(arg);
			if (result == null) return context.withCastError(0,args, Types.STRING);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Keyword> KEYWORD = reg(new CoreFn<>(Symbols.KEYWORD,15) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell arg=args[0];
			if (arg instanceof Keyword) return context.withResult(Juice.KEYWORD, arg);

			// Check argument is valid name
			AString name = RT.name(arg);
			if (name == null) return context.withCastError(0,args, Types.KEYWORD);

			// Check name converts to Keyword
			Keyword result = Keyword.create(name);
			if (result == null) return context.withArgumentError("Invalid Keyword name, must be between 1 and "+Constants.MAX_NAME_LENGTH+ " characters");

			return context.withResult(Juice.KEYWORD, result);
		}
	});

	public static final CoreFn<Symbol> SYMBOL = reg(new CoreFn<Symbol>(Symbols.SYMBOL,16) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n!=1) return context.withArityError(exactArityMessage(1,args.length));

			ACell maybeName=args[n-1];

			// Fast path for existing Symbol
			if (maybeName instanceof Symbol) {
				Symbol sym=(Symbol)maybeName;
				return context.withResult(Juice.SYMBOL, sym);
			}

			// Check argument is valid name for a Symbol
			AString name = RT.name(maybeName);
			if (name == null) return context.withCastError(0, args, Types.SYMBOL);

			Symbol sym = Symbol.create(name);
			if (sym == null) return context.withArgumentError("Invalid Symbol name, must be between 1 and " + Constants.MAX_NAME_LENGTH + " characters");

			long juice = Juice.SYMBOL;
			return context.withResult(juice, sym);
		}
	});

	public static final CoreFn<AOp<ACell>> COMPILE = reg(new CoreFn<>(Symbols.COMPILE,257) {

		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell form = (ACell) args[0];
			// note: compiler takes care of Juice for us
			return context.expandCompile(form);
		}

	});

	public static final CoreFn<ACell> EVAL = reg(new CoreFn<>(Symbols.EVAL,17) {
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell form = (ACell) args[0];
			AOp<?> op;
			if (form instanceof AOp) {
				op=(AOp<?>)form;
			} else {
				context=context.expandCompile(form);
				if (context.isExceptional()) return context;
				op=(AOp<?>)context.getResult();
			}
			Context rctx = context.exec(op);
			return rctx.consumeJuice(Juice.EVAL);
		}
	});

	public static final CoreFn<ACell> EVAL_AS = reg(new CoreFn<>(Symbols.EVAL_AS,18) {	
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.ensureAddress(args[0]);
			if (address==null) return context.withCastError(0,args, Types.ADDRESS);

			ACell form = (ACell) args[1];
			Context rctx = context.evalAs(address,form);
			return rctx.consumeJuice(Juice.EVAL);
		}
	});
	
	public static final CoreFn<ACell> QUERY_AS = reg(new CoreFn<>(Symbols.QUERY_AS,19) {	
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.ensureAddress(args[0]);
			if (address==null) return context.withCastError(0,args, Types.ADDRESS);

			ACell form = (ACell) args[1];
			Context rctx = context.queryAs(address,form);
			return rctx.consumeJuice(Juice.EVAL);
		}
	});

	public static final CoreFn<CVMLong> SCHEDULE_STAR = reg(new CoreFn<>(Symbols.SCHEDULE_STAR,20) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(this.exactArityMessage(3, n));

			// get timestamp target
			CVMLong tso = RT.ensureLong(args[0]);
			if (tso==null) return context.withCastError(0,args,Types.LONG);
			long scheduleTimestamp = tso.longValue();

			// get operation
			ACell opo = args[1];
			if (!(opo instanceof AOp)) return context.withCastError(1,args,Types.OP);
			AOp<?> op = (AOp<?>) opo;

			return context.schedule(scheduleTimestamp, op);
		}
	});

	public static final CoreFn<Syntax> SYNTAX = reg(new CoreFn<>(Symbols.SYNTAX,21) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n=args.length;
			if (n < 1) return context.withArityError(minArityMessage(1, args.length));
			if (n > 2) return context.withArityError(maxArityMessage(2, args.length));

			Syntax result;
			if (n==1) {
				result=Syntax.create((ACell)args[0]);
			} else {
				AHashMap<ACell,ACell> meta=RT.ensureHashMap(args[1]);
				if (meta==null) return context.withCastError(1,args, Types.MAP);
				result = Syntax.create((ACell) args[0],meta);
			}

			long juice = Juice.SYNTAX;

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> UNSYNTAX = reg(new CoreFn<>(Symbols.UNSYNTAX,22) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Unwrap Syntax. Cannot fail.
			ACell result = Syntax.unwrap(args[0]);

			// Return unwrapped value with juice
			long juice = Juice.SYNTAX;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AHashMap<ACell,ACell>> META = reg(new CoreFn<>(Symbols.META,23) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
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

	public static final CoreFn<CVMBool> SYNTAX_Q = reg(new CorePred(Symbols.SYNTAX_Q,24) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Syntax;
		}
	});

	public static final CoreFn<ACell> EXPAND = reg(new CoreFn<>(Symbols.EXPAND,258) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if ((n<1)||(n>3)) {
				return context.withArityError(name() + " requires a form argument, optional expander and optional continuation expander (arity 1, 2 or 2)");
			}

			AFn<ACell> expander;
			if (n >= 2) {
				// use provided expander
				ACell exArg = args[1];
				expander=RT.ensureFunction(exArg);
				if (expander==null) return context.withCastError(1,args, Types.FUNCTION);
			} else {
				expander=Compiler.INITIAL_EXPANDER;
			}

			AFn<ACell> cont=expander; // use passed expander by default
			if (n >= 3) {
				// use provided continuation expander
				ACell contArg = args[2];
				cont=RT.ensureFunction(contArg);
				if (cont==null) return context.withCastError(2,args, Types.FUNCTION);
			}

			ACell form = args[0];
			Context rctx = context.expand(expander,form, cont);
			return rctx;
		}
	});

	public static final AFn<ACell> INITIAL_EXPANDER = reg(Compiler.INITIAL_EXPANDER);

	public static final CoreFn<CVMBool> CALLABLE_Q = reg(new CoreFn<>(Symbols.CALLABLE_Q,30) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length==1) {
				Address addr = RT.callableAddress(args[0]);
				return context.withResult(Juice.LOOKUP,CVMBool.create(addr!=null));
			}
			
			if (args.length != 2) return context.withArityError(rangeArityMessage(1,2, args.length));

			// Note we check the symbol first, to catch potential CAST errors
			Symbol sym = RT.ensureSymbol(args[1]);
			if (sym == null) return context.withCastError(1,args, Types.SYMBOL);

			// Get callable address target
			ACell a0=args[0];
			Address addr = RT.callableAddress(a0);
			if (addr == null) {
				return context.withResult(Juice.LOOKUP,CVMBool.FALSE);
			}

			AccountStatus as = context.getState().getAccount(addr);
			if (as == null) return context.withResult(Juice.LOOKUP, CVMBool.FALSE);

			AFn<?> fn = as.getCallableFunction(sym);
			CVMBool result = CVMBool.create(fn!=null);

			return context.withResult(Juice.LOOKUP, result);
		}
	});

	public static final CoreFn<Address> DEPLOY = reg(new CoreFn<>(Symbols.DEPLOY,387) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length<1) return context.withArityError(minArityMessage(1, args.length));

			return context.deploy(args);
		}
	});


	public static final CoreFn<CVMLong> ACCEPT = reg(new CoreFn<>(Symbols.ACCEPT,385) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// Arity 1
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// must cast to Long
			CVMLong amount = RT.ensureLong(args[0]);
			if (amount == null) return context.withCastError(0,args, Types.LONG);

			return context.acceptFunds(amount.longValue());
		}
	});

	public static final CoreFn<ACell> CALL_STAR = reg(new CoreFn<>(Symbols.CALL_STAR,386) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length < 3) return context.withArityError(minArityMessage(1, args.length));

			// consume juice first?
			Context ctx = context.consumeJuice(Juice.CALL_OP);
			if (ctx.isExceptional()) return ctx;

			ACell target = args[0];

			CVMLong sendAmount = RT.ensureLong(args[1]);
			if (sendAmount == null) return ctx.withCastError(1,args, Types.LONG);

			Symbol sym = RT.ensureSymbol(args[2]);
			if (sym == null) return ctx.withCastError(2,args, Types.SYMBOL);

			// prepare contract call arguments
			int arity = args.length - 3;
			ACell[] callArgs = Arrays.copyOfRange(args, 3, 3 + arity);

			return ctx.actorCall(target, sendAmount.longValue(), sym, callArgs);
		}
	});

	public static final CoreFn<Hash> LOG = reg(new CoreFn<>(Symbols.LOG,384) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
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

	public static final CoreFn<ACell> UNDEF_STAR = reg(new CoreFn<>(Symbols.UNDEF_STAR,31) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			Symbol sym=RT.ensureSymbol(args[0]);
			if (sym == null) return context.withArgumentError("Invalid Symbol name for undef: " + Utils.toString(args[0]));

			Context ctx=(Context) context.undefine(sym);

			// return nil
			return ctx.withResult(Juice.DEF, null);

		}
	});


	public static final CoreFn<ACell> LOOKUP = reg(new CoreFn<>(Symbols.LOOKUP,32) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n=args.length;
			if ((n<1)||(n>2)) return context.withArityError(rangeArityMessage(1,2, args.length));

			// get Address to perform lookup
			Address address=(n==1)?context.getAddress():RT.ensureAddress(args[0]);
			if (address==null) return context.withCastError(0,args, Types.ADDRESS);

			// ensure argument converts to a Symbol correctly.
			ACell symArg=args[n-1];
			Symbol sym = RT.ensureSymbol(symArg);
			if (sym == null) return context.withCastError(n-1,args,Types.SYMBOL);

			MapEntry<Symbol, ACell> me = context.lookupDynamicEntry(address,sym);

			long juice = Juice.LOOKUP;
			ACell result = (me == null) ? null : me.getValue();
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Syntax> LOOKUP_META = reg(new CoreFn<>(Symbols.LOOKUP_META,33) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n=args.length;
			if ((n<1)||(n>2)) return context.withArityError(rangeArityMessage(1,2, args.length));

			// get Address to perform lookup
			Address address=null;
			if (n>1) {
				address=RT.ensureAddress(args[0]);
				if (address==null) return context.withCastError(0,args, Types.ADDRESS);
			}

			// ensure argument converts to a Symbol correctly.
			ACell symArg=args[n-1];
			Symbol sym = RT.ensureSymbol(symArg);
			if (sym == null) return context.withCastError(n-1,args,Types.SYMBOL);

			AHashMap<ACell, ACell> result = context.lookupMeta(address,sym);

			long juice = Juice.LOOKUP;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<Address> ADDRESS = reg(new CoreFn<>(Symbols.ADDRESS,34) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell o = args[0];
			Address address = RT.castAddress(o);
			if (address == null) {
				if (o instanceof ABlob) return context.withArgumentError("Blob not convertible a valid Address");
				if (o instanceof AInteger) return context.withArgumentError("Integer value is not a valid Address");
				return context.withCastError(0,args, Types.ADDRESS);
			}
			long juice = Juice.ADDRESS;

			return context.withResult(juice, address);
		}
	});

	public static final CoreFn<ABlob> BLOB = reg(new CoreFn<>(Symbols.BLOB,35) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// TODO: probably need to pre-cost this?
			ABlob blob = RT.castBlob(args[0]);
			if (blob == null) return context.withCastError(0,args, Types.BLOB);

			long juice = Juice.buildBlobCost(blob.count());

			return context.withResult(juice, blob);
		}
	});

	public static final CoreFn<AccountStatus> ACCOUNT = reg(new CoreFn<>(Symbols.ACCOUNT,36) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a0 = args[0];
			Address address = RT.ensureAddress(a0);
			if (address == null) return context.withCastError(0,args, Types.ADDRESS);

			// Note: returns null if the argument is not an address
			AccountStatus as = context.getAccountStatus(address);

			return context.withResult(Juice.SIMPLE_FN, as);
		}
	});

	public static final CoreFn<CVMLong> BALANCE = reg(new CoreFn<>(Symbols.BALANCE,37) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Address address = RT.ensureAddress(args[0]);
			if (address == null) return context.withCastError(0,args, Types.ADDRESS);

			AccountStatus as = context.getAccountStatus(address);
			CVMLong balance = (as != null) ? CVMLong.create(as.getBalance()) : null;

			return context.withResult(Juice.BALANCE, balance);
		}
	});

	public static final CoreFn<CVMLong> TRANSFER = reg(new CoreFn<>(Symbols.TRANSFER,38) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.ensureAddress(args[0]);
			if (address == null) return context.withCastError(0,args, Types.ADDRESS);

			CVMLong amount = RT.ensureLong(args[1]);
			if (amount == null) return context.withCastError(1,args, Types.LONG);

			return context.transfer(address, amount.longValue()).consumeJuice(Juice.TRANSFER);

		}
	});

	public static final CoreFn<CVMLong> SET_MEMORY = reg(new CoreFn<>(Symbols.SET_MEMORY,39) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			CVMLong amount = RT.ensureLong(args[0]);
			if (amount == null) return context.withCastError(0,args, Types.LONG);

			return context.setMemory(amount.longValue()).consumeJuice(Juice.TRANSFER);
		}
	});

	public static final CoreFn<CVMLong> TRANSFER_MEMORY = reg(new CoreFn<>(Symbols.TRANSFER_MEMORY,40) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			Address address = RT.ensureAddress(args[0]);
			if (address == null) return context.withCastError(0,args, Types.ADDRESS);

			CVMLong amount = RT.ensureLong(args[1]);
			if (amount == null) return context.withCastError(1,args, Types.LONG);

			return context.transferMemoryAllowance(address, amount).consumeJuice(Juice.TRANSFER);
		}
	});

	public static final CoreFn<CVMLong> STAKE = reg(new CoreFn<>(Symbols.STAKE,64) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ABlob b=RT.ensureBlob(args[0]);
			if (b == null) return context.withCastError(0,args, Types.BLOB);
			AccountKey accountKey = AccountKey.create(b);
			if (accountKey==null) return context.withArgumentError("Account Key for stake must be 32 bytes");

			CVMLong amount = RT.ensureLong(args[1]);
			if (amount == null) return context.withCastError(1,args, Types.LONG);

			return context.setDelegatedStake(accountKey, amount.longValue()).consumeJuice(Juice.TRANSFER);

		}
	});

	public static final CoreFn<CVMLong> CREATE_PEER = reg(new CoreFn<>(Symbols.CREATE_PEER,65) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AccountKey accountKey = RT.ensureAccountKey(args[0]);
			if (accountKey == null) return context.withCastError(0,args, Types.BLOB);

			CVMLong amount = RT.ensureLong(args[1]);
			if (amount == null) return context.withCastError(1,args, Types.LONG);

			return context.createPeer(accountKey, amount.longValue()).consumeJuice(Juice.PEER_UPDATE);
		}
	});


	public static final CoreFn<CVMLong> SET_PEER_DATA = reg(new CoreFn<>(Symbols.SET_PEER_DATA,66) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(1, args.length));

			AccountKey peerKey=RT.ensureAccountKey(args[0]);
			if (peerKey == null) return context.withCastError(0,args, Types.BLOB);
			
			AHashMap<ACell, ACell> data = RT.ensureHashMap(args[1]);
			if (data == null) return context.withCastError(1,args, Types.MAP);
			
			long juice=Juice.PEER_UPDATE;
			context=context.consumeJuice(juice);
			if (context.isExceptional()) return context;

			return context.setPeerData(peerKey,data);
		}
	});
	
	public static final CoreFn<CVMLong> SET_PEER_STAKE = reg(new CoreFn<>(Symbols.SET_PEER_STAKE,67) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
			
			AccountKey peerKey=RT.ensureAccountKey(args[0]);
			if (peerKey == null) return context.withCastError(0,args, Types.BLOB);
			
			CVMLong newStake = RT.ensureLong(args[1]);
			if (newStake == null) return context.withCastError(1,args, Types.LONG);
			long targetStake=newStake.longValue();
			
			context=context.consumeJuice(Juice.PEER_UPDATE);
			if (context.isExceptional()) return context;
			
			return context.setPeerStake(peerKey,targetStake);
		}
	});


	public static final CoreFn<AMap<?, ?>> HASHMAP = reg(new CoreFn<>(Symbols.HASH_MAP,80) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int len = args.length;
			// specialised arity check since we need even length
			if (Utils.isOdd(len)) return context.withArityError(name() + " requires an even number of arguments");

			long juice = Juice.BUILD_DATA + len * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, Maps.create(args));
		}
	});


	public static final CoreFn<Index> INDEX = reg(new CoreFn<>(Symbols.INDEX,81) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int len = args.length;
			// specialised arity check since we need even length
			if (Utils.isOdd(len)) return context.withArityError(name() + " requires an even number of arguments");

			long juice = Juice.BUILD_DATA + len * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			Index<ABlob,ACell> r=Index.none();
			int n=len/2;
			for (int i=0; i<n; i++) {
				int ix=i*2;
				ACell k=args[ix];
				ACell v=args[ix+1];
				r=r.assoc(k, v);
				if (r==null) return context.withArgumentError("Cannot have a key of Type "+RT.getType(k) +" in Index"); // must be bad key type
			}

			return context.withResult(juice, r);
		}
	});

	public static final CoreFn<ASet<?>> HASHSET = reg(new CoreFn<>(Symbols.HASH_SET,82) {
		@Override
		public Context invoke(Context context, ACell[] args) {
			// any arity is OK

			long juice = Juice.BUILD_DATA + (args.length * Juice.BUILD_PER_ELEMENT);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			return context.withResult(juice, Sets.of(args));
		}
	});

	public static final CoreFn<AVector<ACell>> KEYS = reg(new CoreFn<>(Symbols.KEYS,83) {
		@SuppressWarnings("unchecked")
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			if (!(a instanceof AMap)) return context.withCastError(0,args, Types.MAP);

			AMap<ACell, ACell> m = (AMap<ACell,ACell>) a;
			long juice = Juice.BUILD_DATA + m.count() * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<ACell> keys = RT.keys(m);

			return context.withResult(juice, keys);
		}
	});

	public static final CoreFn<AVector<ACell>> VALUES = reg(new CoreFn<>(Symbols.VALUES,84) {
		@SuppressWarnings("unchecked")
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			if (!(a instanceof AMap)) return context.withCastError(0,args, Types.MAP);

			AMap<ACell, ACell> m = (AMap<ACell, ACell>) a;
			long juice = Juice.BUILD_DATA + m.count() * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			AVector<ACell> keys = RT.values(m);

			return context.withResult(juice, keys);
		}
	});

	public static final CoreFn<ADataStructure<ACell>> ASSOC = reg(new CoreFn<>(Symbols.ASSOC,85) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n < 1) return context.withArityError(minArityMessage(1, n));

			if (!Utils.isOdd(n)) return context.withArityError(name() + " requires key/value pairs as successive args");

			long juice = Juice.BUILD_DATA + (n - 1) * Juice.BUILD_PER_ELEMENT;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ACell o = args[0];

			// convert to associative data structure. nil-> empty map
			ADataStructure<?> result = RT.ensureAssociative(o);

			// values that are non-null but not a data structure are a cast error
			if ((o != null) && (result == null)) return context.withCastError(0,args, Types.DATA_STRUCTURE);

			// assoc additional elements. Must produce a valid non-null data structure after
			// each assoc
			for (int i = 1; i < n; i += 2) {
				ACell key=args[i];
				result = RT.assoc(result, key, args[i + 1]);
				if (result == null) {
					return context.withError(ErrorCodes.ARGUMENT, "Cannot assoc value - invalid (key type "+RT.getType(key)+" is invalid or out of bounds)");
				}
			}

			return context.withResult(juice, (ACell) result);
		}
	});

	public static final CoreFn<ACell> ASSOC_IN = reg(new CoreFn<>(Symbols.ASSOC_IN,86) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 3) return context.withArityError(exactArityMessage(3, args.length));

			ASequence<ACell> ixs = RT.ensureSequence(args[1]);
			if (ixs == null) return context.withCastError(1,args, Types.SEQUENCE);

			int n = ixs.size();
			long juice = (Juice.GET+Juice.ASSOC) * (1L + n);
			ACell data = args[0];
			ACell value= args[2];
			// simply substitute value if key sequence is empty
			if (n==0) return context.withResult(juice, value);

			ADataStructure[] ass=new ADataStructure[n];
			ACell[] ks=new ACell[n];
			for (int i = 0; i < n; i++) {
				ADataStructure struct = RT.ensureAssociative(data);  // nil-> empty map
				if (struct == null) return context.withCastError(data,Types.DATA_STRUCTURE); // TODO: Associative type?
				ass[i]=struct;
				ACell k=ixs.get(i);
				ks[i]=k;
				data=struct.get(k);
			}

			for (int i = n-1; i >=0; i--) {
				ADataStructure<?> struct=ass[i];
				ACell k=ks[i];
				value=RT.assoc(struct, k, value);
				if (value==null) {
					// assoc failed, so key or value type must be invlid
					return context.withError(ErrorCodes.ARGUMENT,"Invalid key of type "+RT.getType(k)+" or value of type "+RT.getType(value)+" for " +name());
				}
			}
			return context.withResult(juice, value);
		}
	});

	public static final CoreFn<ACell> GET_HOLDING = reg(new CoreFn<>(Symbols.GET_HOLDING,96) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));

			Address address=RT.ensureAddress(args[0]);
			if (address == null) return context.withCastError(args[0], Types.ADDRESS);

			AccountStatus as=context.getAccountStatus(address);
			if (as==null) return context.withResult(Juice.LOOKUP, null);
			
			Index<Address,ACell> holdings=as.getHoldings();

			// we get the target accounts holdings for the currently executing account
			ACell result=holdings.get(context.getAddress());

			return context.withResult(Juice.LOOKUP, result);
		}
	});

	public static final CoreFn<ACell> SET_HOLDING = reg(new CoreFn<>(Symbols.SET_HOLDING,97) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=2) return context.withArityError(exactArityMessage(2, n));

			Address address=RT.ensureAddress(args[0]);
			if (address == null) return context.withCastError(args[0], Types.ADDRESS);

			// result is specified by second arg
			ACell result= args[1];

			// we set the target account holdings for the currently executing account
			// might return NOBODY if account does not exist
			context=(Context) context.setHolding(address,result);
			if (context.isExceptional()) return (Context) context;

			return context.withResult(Juice.ASSOC, result);
		}
	});

	public static final CoreFn<ACell> SET_CONTROLLER = reg(new CoreFn<>(Symbols.SET_CONTROLLER,98) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));

			// Get requested controller. Must be a valid address or null
			ACell arg=args[0];
			ACell controller=null;
			if (arg!=null) {
				Address controlAddress=RT.callableAddress(arg);
				if (controlAddress == null) return context.withError(ErrorCodes.CAST,name()+" requires an Address or scoped Actor");
				if (!context.getState().hasAccount(controlAddress)) {
					 return context.withError(ErrorCodes.NOBODY, name()+" requires an address for an existing account");
				}
				controller=arg; // we have now validated arg is OK as controller
			}

			context=(Context) context.setController(controller);
			if (context.isExceptional()) return (Context) context;

			return context.withResult(Juice.ASSOC, controller);
		}
	});
	
	public static final CoreFn<ACell> SET_PARENT = reg(new CoreFn<>(Symbols.SET_PARENT,99) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));

			// Get requested controller. Must be a valid Address or null
			ACell arg=args[0];
			Address parent=null;
			if (arg!=null) {
				Address parentAddress=RT.ensureAddress(arg);
				if (parentAddress == null) return context.withError(ErrorCodes.CAST,name()+" requires an Address or nil");
				if (!context.getState().hasAccount(parentAddress)) {
					 return context.withError(ErrorCodes.NOBODY, name()+" requires an address for an existing account");
				}
				if (parentAddress.equals(context.getAddress())) {
					 return context.withError(ErrorCodes.ARGUMENT, "Can't set parent of account to itself!");
				}
				parent=parentAddress; // we have now validated arg is OK as parent
			}

			context=(Context) context.setParent(parent);
			if (context.isExceptional()) return (Context) context;

			return context.withResult(Juice.ASSOC, parent);
		}
	});

	public static final CoreFn<AccountKey> SET_KEY = reg(new CoreFn<>(Symbols.SET_KEY,100) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n !=1) return context.withArityError(exactArityMessage(1, n));

			ACell arg=args[0];
			
			AccountKey publicKey=null;
			
			if (arg!=null) {
				// Ensure we have a Blob argument
				ABlob b=RT.ensureBlob(arg);
				if (b == null) return context.withCastError(arg, Types.BLOB);

				// Check an account key is being used as argument. nil is permitted
				publicKey=AccountKey.create(b);
				if (publicKey == null) return context.withArgumentError("Invalid key length");
			}
			
			context=(Context) context.setAccountKey(publicKey);
			if (context.isExceptional()) return context;

			return context.withResult(Juice.ASSOC, publicKey);
		}
	});


	public static final CoreFn<ACell> GET = reg(new CoreFn<>(Symbols.GET,112) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if ((n < 2) || (n > 3)) {
				return context.withArityError(name() + " requires exactly 2 or 3 arguments");
			}

			ACell result;
			ACell coll = args[0];
			if (coll == null) {
				// Treat nil as empty collection with no keys
				result = (n == 3) ? (ACell)args[2] : null;
			} else if (n == 2) {
				ADataStructure<?> gettable = RT.ensureDataStructure(coll);
				if (gettable == null) return context.withCastError(coll, Types.DATA_STRUCTURE);
				result = gettable.get(args[1]);
			} else {
				ADataStructure<?> gettable = RT.ensureDataStructure(coll);
				if (gettable == null) return context.withCastError(coll, Types.DATA_STRUCTURE);
				result = gettable.get(args[1], args[2]);
			}
			long juice = Juice.GET;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> GET_IN = reg(new CoreFn<>(Symbols.GET_IN,113) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if ((n < 2) || (n > 3)) {
				return context.withArityError(name() + " requires exactly 2 or 3 arguments");
			}

			ASequence<ACell> ixs = RT.ensureSequence(args[1]);
			if (ixs == null) return context.withCastError(args[1], Types.SEQUENCE);

			ACell notFound=(n<3)?null:args[2];

			int il = ixs.size();
			long juice = Juice.GET * (1L + il);
			ACell result = (ACell) args[0];
			for (int i = 0; i < il; i++) {
				if (result == null) {
					result=notFound;
					break; // gets in nil produce not-found
				}
				ADataStructure<?> gettable = RT.ensureDataStructure(result);
				if (gettable == null) return context.withCastError(result, Types.DATA_STRUCTURE);

				ACell k=ixs.get(i);
				if (gettable.containsKey(k)) {
					result = gettable.get(k);
				} else {
					return context.withResult(juice, notFound);
				}

			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMBool> CONTAINS_KEY_Q = reg(new CoreFn<>(Symbols.CONTAINS_KEY_Q,114) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(exactArityMessage(2, n));

			CVMBool result;
			ACell coll = args[0];
			if (coll == null) {
				result = CVMBool.FALSE; // treat nil as empty collection
			} else {
				ADataStructure<?> gettable = RT.ensureDataStructure(args[0]);
				if (gettable == null) return context.withCastError(args[0], Types.DATA_STRUCTURE);
				result = CVMBool.of(gettable.containsKey((ACell) args[1]));
			}

			long juice = Juice.GET;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMBool> SUBSET_Q = reg(new CoreFn<>(Symbols.SUBSET_Q,115) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n != 2) return context.withArityError(exactArityMessage(2, n));

			ASet<ACell> s0=RT.ensureSet(args[0]);
			if (s0==null) return context.withCastError(args[0], Types.SET);

			long juice = Juice.SIMPLE_FN+Juice.SET_COMPARE_PER_ELEMENT*s0.count();
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ASet<ACell> s1=RT.ensureSet(args[1]);
			if (s1==null) return context.withCastError(args[1], Types.SET);

			CVMBool result=CVMBool.of(s0.isSubset(s1));
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AMap<?, ?>> DISSOC = reg(new CoreFn<>(Symbols.DISSOC,116) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			AMap<ACell, ACell> result = RT.ensureMap(args[0]);
			if (result == null) return context.withCastError(args[0], Types.MAP);

			for (int i = 1; i < n; i++) {
				result = result.dissoc((ACell) args[i]);
			}
			long juice = Juice.BUILD_DATA + (n - 1) * Juice.BUILD_PER_ELEMENT;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ADataStructure<ACell>> CONJ = reg(new CoreFn<>(Symbols.CONJ,117) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int numAdditions = args.length - 1;
			if (args.length <= 0) return context.withArityError(name() + " requires a data structure as first argument");

			// compute juice up front
			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * numAdditions;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ADataStructure<ACell> result = RT.castDataStructure(args[0]);
			if (result == null) return context.withCastError(0,args, Types.DATA_STRUCTURE);

			for (int i = 0; i < numAdditions; i++) {
				int argIndex=i+1;
				ACell val = (ACell) args[argIndex];
				result = result.conj(val);
				if (result == null) return context.withError(ErrorCodes.ARGUMENT,"Failure to 'conj' argument at position "+argIndex+" (with Type "+RT.getType(val)+"). Probably not a legal value for this data structure?"); // must be a failed map conj?
			}
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ASet<ACell>> DISJ = reg(new CoreFn<>(Symbols.DISJ,118) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			// compute juice up front
			int numAdditions = args.length - 1;
			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * numAdditions;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ASet<ACell> result = RT.ensureSet(args[0]);
			if (result == null) return context.withCastError(0,args, Types.SET);


			for (int i = 0; i < numAdditions; i++) {
				int argIndex=i+1;
				ACell val = args[argIndex];
				result = result.exclude(val);
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AList<ACell>> CONS = reg(new CoreFn<>(Symbols.CONS,119) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (args.length < 2) return context.withArityError(minArityMessage(2, args.length));

			long juice = Juice.BUILD_DATA + Juice.BUILD_PER_ELEMENT * (n - 1);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			// get sequence from last argument
			int lastIndex=n-1;
			ASequence<?> seq = RT.sequence(args[lastIndex]);
			if (seq == null) return context.withCastError(lastIndex,args, Types.SEQUENCE);

			AList<ACell> list = RT.cons((ACell) args[n - 2], seq);

			for (int i = n - 3; i >= 0; i--) {
				list = RT.cons((ACell)args[i], list);
			}
			return context.withResult(juice, list);
		}
	});

	public static final CoreFn<ACell> FIRST = reg(new CoreFn<>(Symbols.FIRST,120) {
		// note we could define this as (nth coll 0) but this is more efficient

		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell maybeColl = args[0];
			Long n= RT.count(maybeColl);
			if (n == null) return context.withCastError(0,args, Types.SEQUENCE);
			if (n<1) return context.withBoundsError(0);
			ACell result = RT.nth(maybeColl,0);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> SECOND = reg(new CoreFn<>(Symbols.SECOND,121) {
		// note we could define this as (nth coll 1) but this is more efficient

		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell maybeColl = (ACell) args[0];
			Long n= RT.count(maybeColl);
			if (n == null) return context.withCastError(0,args, Types.SEQUENCE);
			if (n<2) return context.withBoundsError(1);
			ACell result = RT.nth(maybeColl,1);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> LAST = reg(new CoreFn<>(Symbols.LAST,122) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];

			Long n = RT.count(a);
			if (n == null) return context.withCastError(0,args, Types.SEQUENCE);
			if (n<=0) return context.withBoundsError(-1);

			ACell result = RT.nth(a,n-1);

			long juice = Juice.SIMPLE_FN;
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMBool> EQUALS = reg(new CoreFn<>(Symbols.EQUALS,123) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {

			// all arities OK, all args OK
			CVMBool result = CVMBool.of(RT.allEqual(args));
			return context.withResult(Juice.EQUALS, result);
		}
	});

	public static final CoreFn<CVMBool> EQ = reg(new CoreFn<>(Symbols.EQ,124) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// all arities OK, but need to watch for non-numeric arguments
			CVMBool result = RT.eq(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});
	
	public static final CoreFn<CVMBool> NE = reg(new CoreFn<>(Symbols.NE,125) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// all arities OK, but need to watch for non-numeric arguments
			CVMBool equal = RT.eq(args);
			if (equal == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, equal.not());
		}
	});

	public static final CoreFn<CVMBool> GE = reg(new CoreFn<>(Symbols.GE,126) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// all arities OK
			CVMBool result = RT.ge(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<CVMBool> GT = reg(new CoreFn<>(Symbols.GT,127) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// all arities OK

			CVMBool result = RT.gt(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<CVMBool> LE = reg(new CoreFn<>(Symbols.LE,128) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// all arities OK

			CVMBool result = RT.le(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<CVMBool> LT = reg(new CoreFn<>(Symbols.LT,129) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// all arities OK

			CVMBool result = RT.lt(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});
	
	public static final CoreFn<CVMBool> MIN = reg(new CoreFn<>(Symbols.MIN,130) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			ACell result = RT.min(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});
	
	public static final CoreFn<CVMBool> MAX = reg(new CoreFn<>(Symbols.MAX,131) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			ACell result = RT.max(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);

			return context.withResult(Juice.NUMERIC_COMPARE, result);
		}
	});

	public static final CoreFn<CVMLong> INC = reg(new CoreFn<>(Symbols.INC,132) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			AInteger result = RT.ensureInteger(a);
			if (result == null) return context.withCastError(0,args, Types.LONG);
			result=result.inc();
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMLong> DEC = reg(new CoreFn<>(Symbols.DEC,133) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			AInteger result = RT.ensureInteger(a);
			if (result == null) return context.withCastError(0,args, Types.LONG);
			result=result.dec();
			
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMBool> BOOLEAN = reg(new CoreFn<>(Symbols.BOOLEAN,134) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			// Boolean cast always works for any value
			CVMBool result = (RT.bool(args[0])) ? CVMBool.TRUE : CVMBool.FALSE;

			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<CVMBool> BOOLEAN_Q = reg(new CorePred(Symbols.BOOLEAN_Q,135) {
		@Override
		public boolean test(ACell val) {
			return RT.isBoolean(val);
		}
	});

	public static final CoreFn<ABlob> ENCODING = reg(new CoreFn<>(Symbols.ENCODING,136) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			Blob encoding=Format.encodedBlob(a);
			long juice=Juice.buildBlobCost(encoding.count());
			if (!context.checkJuice(juice)) return context.withJuiceError();
			
			ABlob result=encoding.getCanonical();
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMLong> LONG = reg(new CoreFn<>(Symbols.LONG,137) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMLong result = RT.castLong(a);
			if (result == null) return context.withCastError(0, args,Types.LONG);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<AInteger> INT = reg(new CoreFn<>(Symbols.INT,138) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			AInteger result = RT.castInteger(a);
			if (result == null) return context.withCastError(0, args,Types.INTEGER);
			// TODO: bigint construction cost?
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> DOUBLE = reg(new CoreFn<>(Symbols.DOUBLE,139) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMDouble result = RT.castDouble(a);
			if (result == null) return context.withCastError(0, args,Types.DOUBLE);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMChar> CHAR = reg(new CoreFn<>(Symbols.CHAR,140) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMChar result;
			if (a instanceof CVMChar) {
				result= (CVMChar) a;
			} else if (a instanceof ABlobLike) {
				ABlobLike b=RT.ensureBlobLike(a);
				result=CVMChar.fromUTF8(b);
				if (result == null) 
					return context.withArgumentError("Not a valid UTF-8 character");
			} else {
				AInteger cp = RT.ensureInteger(a);
				if (cp == null)
					return context.withCastError(0,args, Types.CHARACTER);
				if (!cp.isLong()) return context.withArgumentError("Invalid code point: "+cp);
				result=CVMChar.create(cp.longValue());
				if (result == null) 
					return context.withArgumentError("Invalid code point: "+cp);
			}
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMLong> BYTE = reg(new CoreFn<>(Symbols.BYTE,141) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell a = args[0];
			CVMLong result = RT.castByte(a);
			if (result == null) return context.withCastError(0,args, Types.LONG);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> PLUS = reg(new CoreFn<>(Symbols.PLUS,160) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// All arities OK
			long cost=Juice.precostNumericLinear(args);
			if (cost<0) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);
			if (cost>0) {
				context=context.consumeJuice(cost);
				if (context.isExceptional()) return context; // not not exceptional, might be something else
			}
			
			ANumeric result = RT.plus(args);
			if (result==null) return context.withError(Errors.INVALID_NUMERIC);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> MINUS = reg(new CoreFn<>(Symbols.MINUS,161) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));
			
			long cost=Juice.precostNumericLinear(args);
			if (cost<0) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);
			if (cost>0) {
				context=context.consumeJuice(cost);
				if (context.isExceptional()) return context; // not not exceptional, might be something else
			}
			ANumeric result = RT.minus(args);
			if (result==null) return context.withError(Errors.INVALID_NUMERIC);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> TIMES = reg(new CoreFn<>(Symbols.TIMES,162) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			// All arities OK
			long cost=Juice.precostNumericLinear(args);
			if (cost<0) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);
			if (cost>0) {
				context=context.consumeJuice(cost);
				if (context.isExceptional()) return context; // not not exceptional, might be something else
			}

			ANumeric result = RT.multiply(args);
			if (result == null) return context.withError(Errors.INVALID_NUMERIC);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> DIVIDE = reg(new CoreFn<>(Symbols.DIVIDE,163) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length < 1) return context.withArityError(minArityMessage(1, args.length));

			CVMDouble result = RT.divide(args);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> FLOOR = reg(new CoreFn<>(Symbols.FLOOR,164) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMDouble result = RT.floor(args[0]);
			if (result == null) return context.withCastError(0,args, Types.NUMBER);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});


	public static final CoreFn<CVMDouble> CEIL = reg(new CoreFn<>(Symbols.CEIL,165) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMDouble result = RT.ceil(args[0]);
			if (result == null) return context.withCastError(0,args, Types.NUMBER);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});


	public static final CoreFn<CVMDouble> SQRT = reg(new CoreFn<>(Symbols.SQRT,166) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			CVMDouble result = RT.sqrt(args[0]);
			if (result == null) return context.withCastError(0,args, Types.NUMBER);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<APrimitive> ABS = reg(new CoreFn<>(Symbols.ABS,167) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			APrimitive result = RT.abs(args[0]);
			if (result == null) return context.withCastError(RT.findNonNumeric(args),args, Types.NUMBER);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMLong> SIGNUM = reg(new CoreFn<>(Symbols.SIGNUM,168) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));
			ACell result = RT.signum(args[0]);
			if (result == null) return context.withCastError(args[0], Types.NUMBER);
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<AInteger> MOD = reg(new CoreFn<>(Symbols.MOD,169) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AInteger la=RT.ensureInteger(args[0]);
			AInteger lb=RT.ensureInteger(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(Types.INTEGER);
			if (lb.isZero()) return context.withArgumentError("Divsion by zero in "+name());

			AInteger result=la.mod(lb);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<AInteger> DIV = reg(new CoreFn<>(Symbols.DIV,170) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AInteger la=RT.ensureInteger(args[0]);
			AInteger lb=RT.ensureInteger(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(Types.INTEGER);
			if (lb.isZero()) return context.withArgumentError("Divsion by zero in "+name());

			AInteger result=la.div(lb);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<AInteger> REM = reg(new CoreFn<>(Symbols.REM,171) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AInteger la=RT.ensureInteger(args[0]);
			AInteger lb=RT.ensureInteger(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(Types.INTEGER);
			if (lb.isZero()) return context.withArgumentError("Divsion by zero in "+name());

			AInteger result=la.rem(lb);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<AInteger> QUOT = reg(new CoreFn<>(Symbols.QUOT,172) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			AInteger la=RT.ensureInteger(args[0]);
			AInteger lb=RT.ensureInteger(args[1]);
			if ((lb==null)||(la==null)) return context.withCastError(Types.INTEGER);
			if (lb.isZero()) return context.withArgumentError("Divsion by zero in "+name());

			AInteger result=la.quot(lb);
	
			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	
	/**
	 * Expander used for expansion of `quote` forms.
	 * 
	 * Should work on both raw forms and syntax objects.
	 * 
	 * Follows the "Expansion-Passing Style" approach of Dybvig, Friedman, and Haynes
	 */
	public static final CoreFn<ACell> QUOTE = reg(new CoreFn<ACell>(Symbols.QUOTE,0) {
		@Override
		public Context invoke(Context context,ACell[] args ) {
			if (args.length!=2) return context.withArityError(exactArityMessage(2, args.length));
			ACell x = args[0];
		
			return context.withResult(Juice.EXPAND_CONSTANT,x);
		}
	});


	public static final CoreFn<CVMDouble> POW = reg(new CoreFn<>(Symbols.POW,173) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			CVMDouble result = RT.pow(args);
			if (result==null) return context.withCastError(Types.DOUBLE);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<CVMDouble> EXP = reg(new CoreFn<>(Symbols.EXP,174) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			CVMDouble result = RT.exp(args[0]);
			if (result==null) return context.withCastError(0,Types.DOUBLE);

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
// TODO: probably want this?
//	
//	public static final CoreFn<CVMDouble> EXPT = reg(new CoreFn<>(Symbols.EXPT,500) {
//		
//		@Override
//		public  Context invoke(Context context, ACell[] args) {
//			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));
//
//			ANumeric base=RT.ensureNumber(args[0]);
//			if (base==null) return context.withCastError(0,Types.DOUBLE);
//
//			ANumeric power=RT.ensureNumber(args[1]);
//			if (power==null) return context.withCastError(1,Types.DOUBLE);
//
//			ANumeric result;
//			
//			if ((base instanceof AInteger )&&(power instanceof AInteger)) {
//				AInteger a=(AInteger)base;
//				AInteger b=(AInteger)power;
//				long juice=Juice.addMul(Juice.ARITHMETIC,Juice.costNumeric(a),Juice.costNumeric(b));
//				if (!context.checkJuice(juice)) return context.withJuiceError();
//				result = a.toPower(b);
//				
//				return context.withResult(juice, result);
//			} else {
//				result=CVMDouble.create(Math.pow(base.doubleValue(), power.doubleValue()));
//				return context.withResult(Juice.ARITHMETIC, result);
//			}
//		}
//	});

	public static final CoreFn<CVMBool> NOT = reg(new CoreFn<>(Symbols.NOT,175) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			CVMBool result = CVMBool.of(!RT.bool(args[0]));
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});
	
	public static final CoreFn<CVMLong> BIT_AND = reg(new CoreFn<>(Symbols.BIT_AND,176) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			CVMLong a = RT.ensureLong(args[0]);
			if (a==null) return context.withCastError(0,args,Types.LONG);

			CVMLong b = RT.ensureLong(args[1]);
			if (b==null) return context.withCastError(1,args,Types.LONG);
			
			CVMLong result=CVMLong.create(a.longValue()&b.longValue());

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> BIT_XOR = reg(new CoreFn<>(Symbols.BIT_XOR,177) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			CVMLong a = RT.ensureLong(args[0]);
			if (a==null) return context.withCastError(0,args,Types.LONG);

			CVMLong b = RT.ensureLong(args[1]);
			if (b==null) return context.withCastError(1,args,Types.LONG);
			
			CVMLong result=CVMLong.create(a.longValue()^b.longValue());

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> BIT_OR = reg(new CoreFn<>(Symbols.BIT_OR,178) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			CVMLong a = RT.ensureLong(args[0]);
			if (a==null) return context.withCastError(0,args,Types.LONG);

			CVMLong b = RT.ensureLong(args[1]);
			if (b==null) return context.withCastError(1,args,Types.LONG);
			
			CVMLong result=CVMLong.create(a.longValue()|b.longValue());

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});
	
	public static final CoreFn<CVMLong> BIT_NOT = reg(new CoreFn<>(Symbols.BIT_NOT,179) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			CVMLong a = RT.ensureLong(args[0]);
			if (a==null) return context.withCastError(0,args,Types.LONG);
			
			CVMLong result=CVMLong.create(~a.longValue());

			return context.withResult(Juice.ARITHMETIC, result);
		}
	});

	public static final CoreFn<Hash> HASH = reg(new CoreFn<>(Symbols.HASH,180) {
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ABlob blob=RT.ensureBlob(args[0]);
			if (blob==null) return context.withCastError(0,args, Types.BLOB);
			long juice=Juice.HASH+blob.count()*Juice.HASH_PER_BYTE;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			Hash result = blob.getContentHash();
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<Hash> KECCAK256 = reg(new CoreFn<>(Symbols.KECCAK256,181) {
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ABlob blob=RT.ensureBlob(args[0]);
			if (blob==null) return context.withCastError(0,args, Types.BLOB);
			long juice=Juice.HASH+blob.count()*Juice.HASH_PER_BYTE;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			Hash result = blob.computeHash(Hashing.getKeccak256Digest());
			return context.withResult(juice, result);
		}
	});
	
	public static final CoreFn<Hash> SHA256 = reg(new CoreFn<>(Symbols.SHA256,182) {
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ABlob blob=RT.ensureBlob(args[0]);
			if (blob==null) return context.withCastError(0,args, Types.BLOB);
			long juice=Juice.HASH+blob.count()*Juice.HASH_PER_BYTE;
			if (!context.checkJuice(juice)) return context.withJuiceError();

			Hash result = blob.computeHash(Hashing.getSHA256Digest());
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<CVMLong> COUNT = reg(new CoreFn<>(Symbols.COUNT,183) {	
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			Long result = RT.count(args[0]);
			if (result == null) return context.withCastError(0,args, Types.DATA_STRUCTURE);

			return context.withResult(Juice.SIMPLE_FN, CVMLong.create(result));
		}
	});

	public static final CoreFn<ACell> EMPTY = reg(new CoreFn<>(Symbols.EMPTY,192) {
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ACell o = args[0];

			// emptying nil is still nil
			if (o == null) return context.withResult(Juice.SIMPLE_FN, null);

			ACountable<?> coll = RT.ensureCountable(o);
			if (coll == null) return context.withCastError(0,args, Types.DATA_STRUCTURE);

			// This might be nil, if the countable type doesn't support an empty instance
			ACell result = coll.empty();
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<ACell> NTH = reg(new CoreFn<>(Symbols.NTH,193) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// Arity 2
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			// First argument must be a countable data structure
			ACell arg = (ACell) args[0];
			Long n = RT.count(arg);
			if (n == null) return context.withCastError(arg, Types.SEQUENCE);

			// Second argument should be an Integer index within long range
			AInteger iarg=RT.ensureInteger(args[1]);
			if (iarg==null) return context.withCastError(1,args, Types.INTEGER);
			
			if (!iarg.isLong()) return context.withError(ErrorCodes.BOUNDS,"Excessively large index");

			long i=iarg.longValue();

			// BOUNDS error if access is out of bounds
			if ((i < 0) || (i >= n)) return context.withBoundsError(i);

			// We know the object is a countable collection, so safe to use 'nth'
			ACell result = RT.nth(arg, i);

			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});

	public static final CoreFn<ASequence<ACell>> NEXT = reg(new CoreFn<>(Symbols.NEXT,194) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			ASequence<ACell> seq = RT.sequence(args[0]);
			if (seq == null) return context.withCastError(0,args, Types.SEQUENCE);

			ASequence<ACell> result = seq.next();
			// TODO: probably needs to cost a lot?
			return context.withResult(Juice.SIMPLE_FN, result);
		}
	});
	
	public static final CoreFn<ASequence<ACell>> SLICE= reg(new CoreFn<>(Symbols.SLICE,195) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int alen=args.length;
			if (alen < 2) return context.withArityError(minArityMessage(2, args.length));
			if (alen > 3) return context.withArityError(maxArityMessage(3, args.length));

			ACountable<ACell> counted = RT.ensureCountable(args[0]);
			if (counted == null) return context.withCastError(0,args, Types.COUNTABLE);
			long n=counted.count();

			long start=0;
			long end=n;

			{
				CVMLong l=RT.ensureLong(args[1]);
				if (l==null) return context.withCastError(1,args, Types.LONG);
				start=l.longValue();
				if (start<0) return context.withBoundsError(start);
			}
			if (alen>2) {
				CVMLong l=RT.ensureLong(args[2]);
				if (l==null) return context.withCastError(2,args, Types.LONG);
				end=l.longValue();
				if (end>n) return context.withBoundsError(end);
			}
			if (end<start) return context.withError(ErrorCodes.BOUNDS,"End before start");
			
			long juice=Juice.costBuildStructure(counted,end-start);
			if (!context.checkJuice(juice)) return context.withJuiceError();
			
			ACountable<?> result = counted.slice(start,end);
			// TODO: probably needs to cost a lot more?
			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<?> RECUR = reg(new CoreFn<>(Symbols.RECUR,196) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			// any arity OK?

			AExceptional result = RecurValue.wrap(args);

			return context.withException(Juice.RECUR, result);
		}
	});

	public static final CoreFn<?> TAILCALL_STAR = reg(new CoreFn<>(Symbols.TAILCALL_STAR,197) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n=args.length;
			if (n < 1) return context.withArityError(this.minArityMessage(1, n));

			AFn f=RT.ensureFunction(args[0]);
			if (f==null) return context.withCastError(0, args, Types.FUNCTION);

			ACell[] tailArgs=Arrays.copyOfRange(args, 1, args.length);
			AExceptional result = TailcallValue.wrap(f,tailArgs);

			return context.withException(Juice.RECUR, result);
		}
	});

	public static final CoreFn<?> ROLLBACK = reg(new CoreFn<>(Symbols.ROLLBACK,208) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = RollbackValue.wrap((ACell)args[0]);

			return context.withException(Juice.RETURN, result);
		}
	});

	public static final CoreFn<?> HALT = reg(new CoreFn<>(Symbols.HALT,209) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n = args.length;
			if (n > 1) return context.withArityError(this.maxArityMessage(1, n));

			AExceptional result = HaltValue.wrap((n > 0) ? (ACell)args[0] : null);

			return context.withException(Juice.RETURN, result);
		}
	});

	public static final CoreFn<?> RETURN = reg(new CoreFn<>(Symbols.RETURN,210) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = ReturnValue.wrap((ACell)args[0]);
			return context.withException(Juice.RETURN, result);
		}
	});

	public static final CoreFn<CVMBool> FAIL = reg(new CoreFn<>(Symbols.FAIL,211) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int alen = args.length;
			if (alen > 2) return context.withArityError(maxArityMessage(2, alen));

			// default to :ASSERT if no error code provided. Error code cannot be nil.
			ACell code = (alen == 2) ? (ACell)args[0] : ErrorCodes.ASSERT;
			if (code==null) return context.withError(ErrorCodes.ARGUMENT,"Error code cannot be nil");

			// get message, or nil if not provided
			ACell message = (alen >0) ? (ACell)args[alen-1] : null;
			ErrorValue error = ErrorValue.createRaw(code, message);

			return context.withError(error);
		}
	});

	public static final CoreFn<?> APPLY = reg(new CoreFn<>(Symbols.APPLY,198) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int alen = args.length;
			if (alen < 2) return context.withArityError(minArityMessage(2, alen));

			final AFn<ACell> fn = RT.castFunction(args[0]);
			if (fn==null ) return context.withCastError(0,args, Types.FUNCTION);

			int lastIndex=alen-1;
			ACell lastArg = args[lastIndex];
			ASequence<ACell> coll = RT.ensureSequence(lastArg);
			if (coll == null) return context.withCastError(lastIndex,args, Types.SEQUENCE);

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

			Context rctx = context.invoke(fn, applyArgs);
			return rctx.consumeJuice(Juice.APPLY);
		}
	});

	public static final CoreFn<ADataStructure<ACell>> INTO = reg(new CoreFn<>(Symbols.INTO,224) {

		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 2) return context.withArityError(exactArityMessage(2, args.length));

			ACell a0 = args[0];
			ADataStructure<ACell> result = RT.ensureDataStructure(a0);
			if ((a0 != null) && (result == null)) return context.withCastError(0,args, Types.DATA_STRUCTURE);

			long juice = Juice.BUILD_DATA;
			ACell a1 = args[1];
			if (a0 == null) {
				// First argument is null. Just keep second arg as complete data structure
				result = RT.ensureDataStructure(a1);
				if ((a1 != null) && (result == null)) return context.withCastError(a1, Types.DATA_STRUCTURE);
			} else {
				Long n=RT.count(a1);
				if (n == null) return context.withCastError(a1, Types.DATA_STRUCTURE);

				// check juice before running potentially expansive computation
				juice += Juice.BUILD_PER_ELEMENT * n;
				if (!context.checkJuice(juice)) return context.withJuiceError();

				ASequence<ACell> seq = RT.sequence(a1);
				if (seq == null) return context.withCastError(a1, Types.DATA_STRUCTURE);

				result = result.conjAll(seq);
				if (result == null) return context.withError(ErrorCodes.ARGUMENT,"Invalid element type for 'into'");
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<AHashMap<ACell,ACell>> MERGE = reg(new CoreFn<>(Symbols.MERGE,225) {

		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			int n=args.length;
			if (n==0) return context.withResult(Juice.BUILD_DATA,Maps.empty());

			// TODO: handle indexes?

			ACell arg0=args[0];
			AMap<ACell,ACell> result=RT.ensureMap(arg0);
			if (result == null) return context.withCastError(arg0, Types.MAP);

			long juice=Juice.BUILD_DATA;
			for (int i=1; i<n; i++) {
				ACell argi=args[i];
				AMap<ACell,ACell> argMap=RT.ensureMap(argi);
				if (argMap == null) return context.withCastError(argi, Types.MAP);

				long size=argMap.count();
				juice=Juice.addMul(juice,size,Juice.BUILD_PER_ELEMENT);

				if (!context.checkJuice(juice)) return context.withJuiceError();

				result=result.merge(argMap);
				if (result == null) return context.withError(ErrorCodes.CAST,"Unable to merge");
			}

			return context.withResult(juice, result);
		}

	});

	public static final CoreFn<ADataStructure<?>> MAP = reg(new CoreFn<>(Symbols.MAP,226) {
		
		@Override
		public Context invoke(Context context, ACell[] args) {
			if (args.length < 2) return context.withArityError(minArityMessage(2, args.length));

			// check and cast first argument to a function
			ACell fnArg = args[0];
			AFn<?> f = RT.castFunction(fnArg);
			if (f == null) return context.withCastError(fnArg, Types.FUNCTION);

			// remaining arguments determine function arity to use
			int fnArity = args.length - 1;
			ADataStructure<?>[] seqs = new ADataStructure[fnArity];

			int length = Integer.MAX_VALUE;
			for (int i = 0; i < fnArity; i++) {
				ACell maybeSeq = args[1 + i];
				ADataStructure<ACell> seq = RT.ensureDataStructure(maybeSeq);
				seqs[i] = seq;
				if (seq == null) {
					if (maybeSeq!=null) return context.withCastError(maybeSeq, Types.SEQUENCE);
					// We bail out with empty result
					ADataStructure<?> result=seqs[0];
					if (result!=null) result=result.empty();
					return context.withResult(Juice.MAP,result);
				}
				length = Math.min(length, seq.size());
			}

			final long juice = Juice.addMul(Juice.MAP, Juice.BUILD_DATA , length);
			if (!context.checkJuice(juice)) return context.withJuiceError();

			ADataStructure<?> result = seqs[0].empty();
			boolean reverse=result instanceof AList;
			for (int i = 0; i < length; i++) {
				ACell[] xs = new ACell[fnArity]; // note we need unique instances, because should be effectively immutable. VectorArray needs this...
				long srcIndex=reverse?(length-1-i):i;
				for (int j = 0; j < fnArity; j++) {
					xs[j] = seqs[j].get(srcIndex);
				}
				context = (Context) context.invoke(f, xs);
				if (context.isExceptional()) return context;
				ACell r = context.getResult();
				result=result.conj(r);
				if (result==null) return context.withError(ErrorCodes.ARGUMENT,"Invalid element type for "+seqs[0].getType());
			}

			return context.withResult(juice, result);
		}
	});

	public static final CoreFn<ACell> REDUCE = reg(new CoreFn<>(Symbols.REDUCE,227) {
		
		@Override
		public  Context invoke(Context ctx, ACell[] args) {
			int ac=args.length;
			if ((ac<2)||(ac > 3)) return ctx.withArityError(exactArityMessage(3, ac));

			// check and cast first argument to a function
			ACell fnArg = args[0];
			AFn<?> fn = RT.castFunction(fnArg);
			if (fn == null) return ctx.withCastError(0,args, Types.FUNCTION);


			// last arg must be a data structure
			ACell maybeSeq = (ACell) args[ac-1];
			ADataStructure<ACell> seq = (maybeSeq==null)?Vectors.empty():RT.ensureDataStructure(maybeSeq);
			if (seq == null) return ctx.withCastError(ac-1,args, Types.SEQUENCE);
			long n = seq.count();

			ACell result; // Initial value, can be anything
			long start=0; // first element for reduction
			if (ac==3) {
				result=args[1];
			} else {
				// 2 arg form of reduce must apply function directly to 0 or 1 elements
				int initial=(int)Math.min(2,n); // number of initial arguments to consume
				if (initial==0) {
					return reduceResult(ctx.invoke(fn, Cells.EMPTY_ARRAY));
				} else if (initial==1) {
					return reduceResult(ctx.invoke(fn, new ACell[] {seq.get(0)}));
				}
				result=seq.get(0);
				start = 1;
			}

			// Need to reduce over remaining elements
			ACell[] xs = new ACell[2]; // accumulator, next element
			for (long i = start; i < n; i++) {
				xs[0] = result;
				xs[1] = seq.get(i);
				ctx = ctx.invoke(fn, xs);
				if (ctx.isExceptional()) {
					return reduceResult(ctx);
				} else {
					result=ctx.getResult();
				}
			}

			return ctx.withResult(Juice.REDUCE, result);
		}
	});

	// Helper function for reduce
	private static final Context reduceResult(Context ctx) {
		Object ex=ctx.getValue(); // might be an ACell or Exception. We need to check for a Reduced result only
	 	if (ex instanceof ReducedValue) {
	 		ctx=ctx.withResult(((ReducedValue)ex).getValue());
	 	}
	 	return ctx.consumeJuice(Juice.REDUCE); // bail out with exception
	}

	public static final CoreFn<ACell> REDUCED = reg(new CoreFn<>(Symbols.REDUCED,228) {
		
		@Override
		public  Context invoke(Context context, ACell[] args) {
			if (args.length != 1) return context.withArityError(exactArityMessage(1, args.length));

			AExceptional result = ReducedValue.wrap((ACell) args[0]);
			return context.withException(Juice.RETURN, result);
		}
	});

	// =====================================================================================================
	// Predicates

	public static final CoreFn<CVMBool> NIL_Q = reg(new CorePred(Symbols.NIL_Q,240) {
		@Override
		public boolean test(ACell val) {
			return val == null;
		}
	});

	public static final CoreFn<CVMBool> VECTOR_Q = reg(new CorePred(Symbols.VECTOR_Q,241) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AVector;
		}
	});

	public static final CoreFn<CVMBool> LIST_Q = reg(new CorePred(Symbols.LIST_Q,242) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AList;
		}
	});

	public static final CoreFn<CVMBool> SET_Q = reg(new CorePred(Symbols.SET_Q,243) {
		@Override
		public boolean test(ACell val) {
			return val instanceof ASet;
		}
	});

	public static final CoreFn<CVMBool> MAP_Q = reg(new CorePred(Symbols.MAP_Q,244) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AMap;
		}
	});

	public static final CoreFn<CVMBool> COLL_Q = reg(new CorePred(Symbols.COLL_Q,245) {
		@Override
		public boolean test(ACell val) {
			return val instanceof ADataStructure;
		}
	});
	
	public static final CoreFn<CVMBool> COUNTABLE_Q = reg(new CorePred(Symbols.COUNTABLE_Q,256) {
		@Override
		public boolean test(ACell val) {
			return RT.isCountable(val);
		}
	});

	public static final CoreFn<CVMBool> EMPTY_Q = reg(new CorePred(Symbols.EMPTY_Q,246) {
		@Override
		public boolean test(ACell val) {
			// consider null as an empty object
			// like with clojure
			if (val == null) return true;

			return (val instanceof ACountable) && ((ACountable<?>) val).isEmpty();
		}
	});

	public static final CoreFn<CVMBool> SYMBOL_Q = reg(new CorePred(Symbols.SYMBOL_Q,247) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Symbol;
		}
	});

	public static final CoreFn<CVMBool> KEYWORD_Q = reg(new CorePred(Symbols.KEYWORD_Q,248) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Keyword;
		}
	});

	public static final CoreFn<CVMBool> BLOB_Q = reg(new CorePred(Symbols.BLOB_Q,249) {
		@Override
		public boolean test(ACell val) {
			if (!(val instanceof ABlob)) return false;
			return true;
		}
	});

	public static final CoreFn<CVMBool> ADDRESS_Q = reg(new CorePred(Symbols.ADDRESS_Q,250) {
		@Override
		public boolean test(ACell val) {
			return val instanceof Address;
		}
	});

	public static final CoreFn<CVMBool> LONG_Q = reg(new CorePred(Symbols.LONG_Q,251) {
		@Override
		public boolean test(ACell val) {
			if (val instanceof AInteger) {
				return ((AInteger)val).ensureLong()!=null;
			}
			return false;
		}
	});
	
	public static final CoreFn<CVMBool> INT_Q = reg(new CorePred(Symbols.INT_Q,252) {
		@Override
		public boolean test(ACell val) {
			if (val instanceof AInteger) {
				return true;
			}
			return false;
		}
	});
	
	public static final CoreFn<CVMBool> DOUBLE_Q = reg(new CorePred(Symbols.DOUBLE_Q,253) {
		@Override
		public boolean test(ACell val) {
			return val instanceof CVMDouble;
		}
	});

	public static final CoreFn<CVMBool> STR_Q = reg(new CorePred(Symbols.STR_Q,254) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AString;
		}
	});

	public static final CoreFn<CVMBool> NUMBER_Q = reg(new CorePred(Symbols.NUMBER_Q,255) {
		@Override
		public boolean test(ACell val) {
			return RT.isNumber(val);
		}
	});

	public static final CoreFn<CVMBool> NAN_Q = reg(new CorePred(Symbols.NAN_Q,270) {
		@Override
		public boolean test(ACell val) {
			return RT.isNaN(val);
		}
	});

	public static final CoreFn<CVMBool> FN_Q = reg(new CorePred(Symbols.FN_Q,271) {
		@Override
		public boolean test(ACell val) {
			return val instanceof AFn;
		}
	});

	public static final CoreFn<CVMBool> ZERO_Q = reg(new CorePred(Symbols.ZERO_Q,272) {
		@Override
		public boolean test(ACell val) {
			if (!RT.isNumber(val)) return false;
			ANumeric n = RT.ensureNumber(val);
			if (n==null) return false;
			return n.isZero();
		}
	});



	// =====================================================================================================
	// Core environment generation

	static Symbol symbolFor(ACell o) {
		if (o instanceof CoreFn) return ((CoreFn<?>) o).getSymbol();
		throw new Error("Cant get symbol for object of type " + o.getClass());
	}

	private static AHashMap<Symbol, ACell> register(AHashMap<Symbol, ACell> env, ACell o) {
		Symbol sym = symbolFor(o);
		if (o instanceof ICoreDef) {
			registerCode((ICoreDef)o);
		}
		assert (!env.containsKey(sym)) : "Duplicate core declaration: " + sym;
		return env.assoc(sym, o);
	}
	
	static void registerCode(ICoreDef o) {
		int code=o.getCoreCode();
		ACell there=CODE_MAP[code];
		if (there!=null) {
			throw new Error("Code duplicte ("+code+"): "+o+" clashes with "+there);
		}
		CODE_MAP[code]=(ACell) o;
	}

	/**
	 * Bootstrap procedure to load the core.cvx library
	 *
	 * @param env Initial environment map
	 * @return Loaded environment map
	 * @throws IOException
	 */
	private static Context registerCoreCode(AHashMap<Symbol, ACell> env) throws IOException {

		// We use a fake state to build the initial environment with core address.
		State state=State.EMPTY;
		for (int i=0; i<=CORE_ADDRESS.longValue(); i++) {
			state = state.putAccount(Address.create(i), AccountStatus.createActor());
		}
		Context ctx = Context.create(state, CORE_ADDRESS);

		// Map in forms from env.
		for (Map.Entry<Symbol,ACell> me : env.entrySet()) {
			ctx=ctx.define(me.getKey(), me.getValue());
		}

		ACell form = null;

		// Compile and execute forms in turn. Later definitions can use earlier macros!
		AList<ACell> forms = Reader.readAll(Utils.readResourceAsString("/convex/core/core.cvx"));
		for (ACell f : forms) {
			form = f;
			ctx = ctx.expandCompile(form);
			if (ctx.isExceptional()) {
				throw new Error("Error compiling core code form: " + form + "\nException : " + ctx.getExceptional());
			}
			AOp<?> op = (AOp<?>)ctx.getResult();
			ctx = ctx.exec(op);
			// System.out.println("Core compilation juice: "+ctx.getJuice());
			if (ctx.isExceptional()) {
				throw new Error("Error executing form: "+ form+ "\n\nException : "+ ctx.getExceptional().toString());
			}
			
			// Testing for core output
			// System.out.println("Core: "+ctx.getResult());
		}

		return ctx;
	}
	
	private static AMap<Symbol, AHashMap<ACell, ACell>> METAS;

 	@SuppressWarnings("unchecked")
	private static Context applyDocumentation(Context ctx) throws IOException {
 		for (Map.Entry<Symbol, AHashMap<ACell, ACell>> entry : METAS.entrySet()) {
 			try {
 				Symbol sym = entry.getKey();
 				AHashMap<ACell, ACell> meta = entry.getValue();
 				MapEntry<Symbol, ACell> definedEntry = ctx.getEnvironment().getEntry(sym);
 
 				if (definedEntry == null) {
 					// No existing value, might be a special.
 					AHashMap<Keyword, ACell> doc = (AHashMap<Keyword, ACell>) meta.get(Keywords.DOC_META);
 					if (doc == null) {
 						// No docs.
 						System.err.println("CORE WARNING: Missing :doc tag in metadata for: " + sym);
 						continue;
 					} else {
 						if (meta.get(Keywords.SPECIAL_META) == CVMBool.TRUE) {
 							ACell val=sym;
 							
 							// convert *special* symbols into Ops
 							Special spec=Special.forSymbol(sym);
 							if (spec!=null) val=spec;
 							
 							// Create a fake entry for special symbols.
 							ctx=ctx.define(sym, val);
 							definedEntry = MapEntry.create(sym, val);
 						} else {
 							System.err.println("CORE WARNING: Documentation for non-existent core symbol: " + sym);
 							continue;
 						}
 					}
 				}
 				
 				// Intrinsic core defs are additionally marked as static
 				ACell value=definedEntry.getValue();
 				if (value instanceof ICoreDef) {
 					meta=meta.assoc(Keywords.STATIC, CVMBool.TRUE);
 				}
 
 				ctx = ctx.defineWithSyntax(Syntax.create(sym, meta), value);
 			} catch (IllegalArgumentException ex) {
 				throw new Error("Error applying documentation:  " + entry, ex);
 			}
 		}
 
 		return ctx;
 	}
 	
 	/**
 	 * Read a Core definition from an encoding
 	 * @param b Blob containing encoding
 	 * @param pos Position to read Core code function
 	 * @return Singleton cell representing the Core value
 	 * @throws BadFormatException In case of encoding error
 	 */
	public static ACell read(Blob b, int pos) throws BadFormatException {
		long code=Format.readVLCCount(b, pos+1);
		if (code <0 || code>=CODE_MAP.length) throw new BadFormatException("Core code out of range: "+code);
		
		ACell o = CODE_MAP[(int)code];
		if (o == null) throw new BadFormatException("Core code definition not found: " + code);
		return o;
	}
	
	/**
	 * Main function to build core and print key details
	 * @param args Command line arguments, if any
	 */
	public static void main(String... args) {
		
	}

	static {
		// Set up `convex.core` environment
		AHashMap<Symbol, ACell> coreEnv = Maps.empty();
		AHashMap<Symbol, AHashMap<ACell,ACell>> coreMeta = Maps.empty();

		try {
			METAS=Reader.read(Utils.readResourceAsString("/convex/core/metadata.cvx"));
			
			// Register all objects from registered runtime
			for (ACell o : tempReg) {
				coreEnv = register(coreEnv, o);
			}

			Context ctx = registerCoreCode(coreEnv);
			ctx=applyDocumentation(ctx);

			coreEnv = ctx.getEnvironment();
			coreMeta = ctx.getMetadata();

			METADATA = coreMeta;
			ENVIRONMENT = coreEnv;
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("IO Error initialising core!",e);
		}
	}

}
