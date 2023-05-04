package convex.core.lang;

import java.util.Map;

import convex.core.Constants;
import convex.core.ErrorCodes;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.List;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.Types;
import convex.core.lang.Context.CompilerState;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.CoreFn;
import convex.core.lang.impl.MultiFn;
import convex.core.lang.ops.Cond;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Def;
import convex.core.lang.ops.Do;
import convex.core.lang.ops.Invoke;
import convex.core.lang.ops.Lambda;
import convex.core.lang.ops.Let;
import convex.core.lang.ops.Local;
import convex.core.lang.ops.Lookup;
import convex.core.lang.ops.Query;
import convex.core.lang.ops.Special;
import convex.core.util.Utils;

/**
 * Compiler class responsible for transforming forms (code as data) into an
 * Op tree for execution.
 * 
 * Phases in complete evaluation: 
 * <ol>
 * <li>Expansion (form -> AST)</li>
 * <li>Compile (AST -> op)</li> 
 * <li>Execute (op -> result)</li>
 * </ol>
 * 
 * Expanded form follows certain rules: - No remaining macros / expanders in
 * leading list positions
 * 
 * TODO: consider including typechecking in expansion phase as per:
 * http://www.ccs.neu.edu/home/stchang/pubs/ckg-popl2017.pdf
 *
 * "A language that doesn't affect the way you think about programming is not
 * worth knowing." ― Alan Perlis
 */
public class Compiler {

	/**
	 * Expands and compiles a form. Equivalent to running expansion followed by
	 * compile. Should not be used directly, intended for use via
	 * Context.expandCompile(...)
	 * 
	 * @param <T>
	 * @param form A form, either raw or wrapped in a Syntax Object
	 * @param context Compilation context
	 * @return Context with compiled op as result
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static <T extends ACell> Context<AOp<T>> expandCompile(ACell form, Context context) {
		// expand phase starts with initial expander
		AFn<ACell> ex = INITIAL_EXPANDER;
		
		// Use initial expander both as current and continuation expander
		// call expand via context to get correct depth and exception handling
		final Context<ACell> ctx = context.invoke(ex, form,ex);
		
		if (ctx.isExceptional()) return (Context<AOp<T>>) (Object) ctx;
		ACell c=ctx.getResult();
		
		return ctx.compile(c);
	}

	/**
	 * Compiles a single form. Should not be used directly, intended for use via
	 * Context.compile(...)
	 * 
	 * Updates context with result, juice consumed
	 * 
	 * @param <T> Type of op result
	 * @param expandedForm A fully expanded form expressed as a Syntax Object
	 * @param context
	 * @return Context with compiled Op as result
	 */
	@SuppressWarnings("unchecked")
	static <T extends ACell> Context<AOp<T>> compile(ACell form, Context<?> context) {
		if (form==null) return compileConstant(context,null);

		if (form instanceof AList) return compileList((AList<ACell>) form, context);

		if (form instanceof Syntax) return compileSyntax((Syntax) form, context);
		if (form instanceof AVector) return compileVector((AVector<ACell>) form, context);
		if (form instanceof AMap) return compileMap((AMap<ACell, ACell>) form, context);
		if (form instanceof ASet) return compileSet((ASet<ACell>) form, context);

		if ((form instanceof Keyword) || (form instanceof ABlob)) {
			return compileConstant(context, form);
		}

		if (form instanceof Symbol) {
			return compileSymbol((Symbol) form, context);
		}
		
		if (form instanceof AOp) {
			// already compiled, just return as constant
			return context.withResult(Juice.COMPILE_CONSTANT, (AOp<T>)form);
		}

		// return as a constant literal
		return compileConstant(context,form);
	}

	/**
	 * Compiles a sequence of forms, returning a vector of ops in the updated
	 * context. Equivalent to calling compile sequentially on each form.
	 * 
	 * @param <R>
	 * @param <T>
	 * @param forms
	 * @param context
	 * @return Context with Vector of compiled ops as result
	 */
	@SuppressWarnings("unchecked")
	static <T extends ACell> Context<AVector<AOp<T>>> compileAll(ASequence<ACell> forms, Context<?> context) {
		if (forms == null) return context.withResult(Vectors.empty()); // consider null as empty list
		int n = forms.size();
		AVector<AOp<T>> obs = Vectors.empty();
		for (int i = 0; i < n; i++) {
			ACell form = forms.get(i);
			context = context.compile(form);
			if (context.isExceptional()) return (Context<AVector<AOp<T>>>) context;
			obs = obs.conj((AOp<T>) context.getResult());
		}
		return context.withResult(obs);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileSyntax(Syntax s, Context<?> context) {
		context=compile(s.getValue(),context);
		return (Context<T>) context;
	}

	/**
	 * Compiles a Symbol as found in regular code
	 * @param <R> Return type from Symbol evaluation
	 * @param <T>
	 * @param sym Symbol
	 * @param context
	 * @return Context with compiled symbol lookup
	 */
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileSymbol(Symbol sym, Context<?> context) {
		// First check for lexically defined Symbols
		CompilerState cs=context.getCompilerState();
		
		// First check if we hit a local declaration within the current compile context
		if (cs!=null) {
			CVMLong position=cs.getPosition(sym);
			if (position!=null) {
				Local<R> op=Local.create(position.longValue());
				return (Context<T>) context.withResult(Juice.COMPILE_LOOKUP,op);
			}
		}
		
		// Next check for special values
		Special<T> maybeSpecial=Special.forSymbol(sym);
		if (maybeSpecial!=null) {
			return context.withResult(maybeSpecial);
		}
		
		// Regular symbol lookup in environment
		Address address=context.getAddress();
		return compileEnvSymbol(address,sym,context);
	}
	
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileEnvSymbol(Address address,Symbol sym, Context<?> context) {
		// Optional code for :static embedding
		if (Constants.OPT_STATIC) {
			// Get metadata for symbol.
			AHashMap<ACell, ACell> meta=context.lookupMeta(sym);
			
			// If static, embed value directly as constant
			if ((meta!=null)&&meta.get(Keywords.STATIC)==CVMBool.TRUE) {
				ACell value=context.lookupValue(sym);
				return (Context<T>) context.withResult(Juice.COMPILE_LOOKUP,Constant.create(value));
			}
		}
		
		// Finally revert to a lookup in the current address / environment
		Lookup<T> lookUp=Lookup.create(Constant.of(address),sym);
		return (Context<T>) context.withResult(Juice.COMPILE_LOOKUP, lookUp);
	}
	
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileSetBang(AList<ACell> list, Context<?> context) {
		if (list.count()!=3) return context.withArityError("set! requires two arguments, a symbol and an expression");

		ACell a1=list.get(1);
		if (!(a1 instanceof Symbol)) return context.withCompileError("set! requires a symbol as first argument");
		Symbol sym=(Symbol)a1;
		
		CompilerState cs=context.getCompilerState();
		CVMLong position=(cs==null)?null:context.getCompilerState().getPosition(sym);
		if (position==null) return context.withCompileError("Trying to set! an undeclared symbol: "+sym);
		
		context=context.compile(list.get(2));
		if (context.isExceptional()) return (Context<T>)context;
		AOp<R> exp=(AOp<R>) context.getResult();
		
		T op=(T) convex.core.lang.ops.Set.create(position.longValue(), exp);
		return context.withResult(Juice.COMPILE_NODE,op);
	}
	
	/**
	 * Compile a lookup of the form (lookup 'foo) or (lookup addr 'foo)
	 * @param list Lookup form
	 * @param context Compiler context
	 * @return Op performing Lookup
	 */
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileLookup(AList<ACell> list, Context<?> context) {
		long n=list.count();
		if ((n<2)||(n>3)) return context.withArityError("lookup requires one or two arguments: an optional expression specifying an account and a Symbol");

		AOp<Address> exp=null;
		if (n==3) {
			// second element of list should be an expression that evaluates to an Address
			context=context.compile(list.get(1));
			if (context.isExceptional()) return (Context<T>)context;
			exp=(AOp<Address>) context.getResult();
		}
		
		ACell a1=list.get(n-1);
		if (!(a1 instanceof Symbol)) return context.withCompileError("lookup requires a Symbol as last argument");
		Symbol sym=(Symbol)a1;
		
		T op=(T) Lookup.create(exp,sym);
		return context.withResult(Juice.COMPILE_NODE,op);
	}

	/**
	 * Compiles a map of the form {a b, c d}
	 * @param form Form as a Map
	 * @param context
	 * @return Op producing the given map
	 */
	private static <R extends ACell, T extends AOp<R>> Context<T> compileMap(AMap<ACell, ACell> form, Context<?> context) {
		int n = form.size();
		if (n==0) return compileConstant(context,form);
		
		ACell[] vs = new ACell[1 + n * 2];
		vs[0] = Symbols.HASH_MAP;
		for (int i = 0; i < n; i++) {
			MapEntry<ACell, ACell> me = form.entryAt(i);
			vs[1 + i * 2] = me.getKey();
			vs[1 + i * 2 + 1] = me.getValue();
		}
		return compileList(List.create(vs), context);
	}

	/**
	 * Compile a set literal of the form #{1 2} as (hash-set 1 2)
	 */
	private static <R extends ACell, T extends AOp<R>> Context<T> compileSet(ASet<ACell> form, Context<?> context) {
		if (form.isEmpty()) return compileConstant(context,Sets.empty());
		
		AVector<ACell> vs = Vectors.empty();
		for (ACell o : form) {
			vs = vs.conj(o);
		}
		vs = vs.conj(Symbols.HASH_SET);
		return compileList(List.reverse(vs), context);
	}

	// A vector literal needs to be compiled as (vector ....)
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileVector(AVector<ACell> vec, Context<?> context) {
		int n = vec.size();
		
		// Zero length vector fast path compiles to a constant
		if (n == 0) return (Context<T>) context.withResult(Juice.COMPILE_CONSTANT, Constant.EMPTY_VECTOR);

		context = context.compileAll(vec);
		if (context.isError()) return (Context<T>) context;
		AVector<AOp<ACell>> obs = (AVector<AOp<ACell>>) context.getResult();

		// return a 'vector' call - note function arg is a constant, we don't want to
		// lookup on the 'vector' symbol
		Constant<ACell> fn = Constant.create(Core.VECTOR);
		return (Context<T>) context.withResult(Juice.COMPILE_NODE, Invoke.create(fn, obs));
	}

	@SuppressWarnings("unchecked")
	private static <T extends ACell> Context<T> compileConstant(Context<?> context, ACell value) {
		return (Context<T>) context.withResult(Juice.COMPILE_CONSTANT, Constant.create(value));
	}

	/**
	 * Compiles a quoted form, returning an op that will produce a data structure
	 * after evaluation of all unquotes.
	 * 
	 * @param <T>
	 * @param context
	 * @param form Quoted form. May be a regular value or Syntax object.
	 * @return Context with complied op as result
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> Context<AOp<T>> compileQuasiQuoted(Context<?> context, ACell aForm, int depth) {
		ACell form;
		
		// Check if form is a Syntax Object and unwrap if necessary
		boolean isSyntax;
		if (aForm instanceof Syntax) {
			form= Syntax.unwrap(aForm);
			isSyntax=true;
		} else {
			form=aForm;
			isSyntax=false;
		}
		
		if (form instanceof ASequence) {
			ASequence<ACell> seq = (ASequence<ACell>) form;
			int n = seq.size();
			if (n == 0) {
				return compileConstant(context, aForm);
			} 

			if (isListStarting(Symbols.UNQUOTE, form)) {
				if (depth==1) {
					if (n != 2) return context.withArityError("unquote requires 1 argument");
					ACell unquoted=seq.get(1);
					//if (!(unquoted instanceof Syntax)) return context.withCompileError("unquote expects an expanded Syntax Object");
					Context<AOp<T>> opContext = expandCompile(unquoted, context);
					return opContext;
				} else {
					depth-=1;
				}
			} else if (isListStarting(Symbols.QUASIQUOTE, form)) {
				depth+=1;
			}

			// compile quoted elements
			context = compileAllQuasiQuoted(context, seq, depth);
			if (context.isExceptional()) return (Context<AOp<T>>) context;
			ASequence<AOp<ACell>> rSeq = (ASequence<AOp<ACell>>) context.getResult();

			ACell fn = (seq instanceof AList) ? Core.LIST : Core.VECTOR;
			AOp<T> inv = Invoke.create( Constant.create(fn), rSeq);
			if (isSyntax) {
				inv=wrapSyntaxBuilder(inv,(Syntax)aForm);
			}
			return context.withResult(Juice.COMPILE_NODE, inv);
		} else if (form instanceof AMap) {
			AMap<ACell, ACell> map = (AMap<ACell, ACell>) form;
			AVector<ACell> rSeq = Vectors.empty();
			for (Map.Entry<ACell, ACell> me : map.entrySet()) {
				rSeq = rSeq.append(me.getKey());
				rSeq = rSeq.append(me.getValue());
			}

			// compile quoted elements
			context = compileAllQuasiQuoted(context, rSeq,depth);
			if (context.isExceptional()) return (Context<AOp<T>>) context;
			ASequence<AOp<ACell>> cSeq = (ASequence<AOp<ACell>>) context.getResult();

			ACell fn = Core.HASHMAP;
			AOp<T> inv = Invoke.create(Constant.create(fn), cSeq);
			if (isSyntax) {
				inv=wrapSyntaxBuilder(inv,(Syntax)aForm);
			}

			return context.withResult(Juice.COMPILE_NODE, inv);
		} else if (form instanceof ASet) {
			ASet<ACell> set = (ASet<ACell>) form;
			AVector<ACell> rSeq = set.toVector();

			// compile quoted elements
			context = compileAllQuasiQuoted(context, rSeq,depth);
			if (context.isExceptional()) return (Context<AOp<T>>) context;
			ASequence<AOp<ACell>> cSeq = (ASequence<AOp<ACell>>) context.getResult();

			ACell fn = Core.HASHSET;
			AOp<T> inv = Invoke.create(Constant.create(fn), cSeq);
			if (isSyntax) {
				inv=wrapSyntaxBuilder(inv,(Syntax)aForm);
			}

			return context.withResult(Juice.COMPILE_NODE, inv);
		}else {
			return compileConstant(context, aForm);
		}
	}
	
	private static <T extends ACell> AOp<T> wrapSyntaxBuilder(AOp<T> op, Syntax source) {
		return Invoke.create(Constant.create(Core.SYNTAX), op, Constant.create(source.getMeta()));
	}

	/**
	 * Compiles a sequence of quoted forms
	 * 
	 * @param <T>
	 * @param context
	 * @param form
	 * @return Context with complied sequence of ops as result
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static <T extends ACell> Context<ASequence<AOp<T>>> compileAllQuasiQuoted(Context<?> context, ASequence<ACell> forms, int depth) {
		int n = forms.size();
		// create a list of ops producing each sub-element
		ASequence<AOp<T>> rSeq = Vectors.empty();
		for (int i = 0; i < n; i++) {
			ACell subSyntax = forms.get(i);
			ACell subForm = Syntax.unwrap(subSyntax);
			if (isListStarting(Symbols.UNQUOTE_SPLICING, subForm)) {
				AList<ACell> subList = (AList<ACell>) subForm;
				int sn = subList.size();
				if (sn != 2) return context.withArityError("unquote-splicing requires 1 argument");
				// unquote-splicing looks like it needs flatmap
				return context.withError(ErrorCodes.TODO,"unquote-splicing not yet supported");
			} else {
				Context<AOp<T>> rctx= compileQuasiQuoted(context, subSyntax,depth);
				if (rctx.isExceptional()) return (Context)rctx;
				rSeq = (ASequence) (rSeq.conj(rctx.getResult()));
			}
		}
		return context.withResult(rSeq);
	}

	/**
	 * Returns true if the form is a List starting with value equal to the
	 * the specified element
	 * 
	 * @param element
	 * @param form
	 * @return True if form is a list starting with a Syntax Object wrapping the
	 *         specified element, false otherwise.
	 */
	@SuppressWarnings("unchecked")
	private static boolean isListStarting(Symbol element, ACell form) {
		if (!(form instanceof AList)) return false;
		AList<ACell> list = (AList<ACell>) form;
		if (list.count() == 0) return false;
		ACell firstElement=list.get(0);
		return Utils.equals(element, Syntax.unwrap(firstElement));
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileList(AList<ACell> list, Context<?> context) {
		int n = list.size();
		if (n == 0) return (Context<T>) context.withResult(Juice.COMPILE_CONSTANT, Constant.EMPTY_LIST);

		// first entry in list should be syntax
		ACell first = list.get(0);
		ACell head = Syntax.unwrap(first);

		if (head instanceof Symbol) {
			Symbol sym = (Symbol) head;
			
			if (sym.equals(Symbols.DO)) {
				context = context.compileAll(list.next());
				if (context.isExceptional()) return (Context<T>) context;
				Do<R> op = Do.create((AVector<AOp<ACell>>) context.getResult());
				return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
			}
			
			if (sym.equals(Symbols.LET)) return compileLet(list, context, false);

			if (sym.equals(Symbols.COND)) {
				context = context.compileAll(list.next());
				if (context.isExceptional()) return (Context<T>) context;
				Cond<R> op = Cond.create((AVector<AOp<ACell>>) (context.getResult()));
				return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
			}
			
			if (sym.equals(Symbols.DEF)) return compileDef(list, context);
			if (sym.equals(Symbols.FN)) return compileFn(list, context);
			
			if (sym.equals(Symbols.QUOTE))  {
				if (list.size() != 2) return context.withCompileError(sym + " expects one argument.");
				return compileConstant(context,list.get(1));
			}
				
			if (sym.equals(Symbols.QUASIQUOTE)) {
				if (list.size() != 2) return context.withCompileError(sym + " expects one argument.");
				return (Context<T>) compileQuasiQuoted(context, list.get(1),1);
			}

			if (sym.equals(Symbols.UNQUOTE)) {
				// execute the unquoted code directly to get a form to compile
				if (list.size() != 2) return context.withCompileError(Symbols.UNQUOTE + " expects one argument.");
				context = context.expandCompile(list.get(1));
				if (context.isExceptional()) return (Context<T>) context;
				AOp<T> quotedOp = (AOp<T>) context.getResult();

				Context<T> rctx = context.execute(quotedOp);
				if (rctx.isExceptional()) return (Context<T>) rctx;

				Syntax resultForm = Syntax.create(rctx.getResult());
				// need to expand and compile here, since we just created a raw form
				return (Context<T>) expandCompile(resultForm, context);
			}

			if (sym.equals(Symbols.QUERY)) {
				context = context.compileAll(list.next());
				if (context.isExceptional()) return (Context<T>) context;
				Query<R> op = Query.create((AVector<AOp<ACell>>) context.getResult());
				return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
			}

			if (sym.equals(Symbols.LOOP)) return compileLet(list, context, true);
			if (sym.equals(Symbols.SET_BANG)) return compileSetBang(list, context);
			
			if (sym.equals(Symbols.LOOKUP)) return compileLookup(list, context);

		}
		
		// must be a regular function call
		context = context.compileAll(list);
		if (context.isExceptional()) return (Context<T>) context;
		Invoke<R> op = Invoke.create((AVector<AOp<ACell>>) context.getResult());

		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}


	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileLet(ASequence<ACell> list, Context<?> context,
			boolean isLoop) {
		// list = (let [...] a b c ...)
		int n=list.size();
		if (n<2) return context.withCompileError(list.get(0) + " requires a binding form vector at minimum");
				
		ACell bo = list.get(1);

		if (!(bo instanceof AVector))
			return context.withCompileError(list.get(0) + " requires a vector of binding forms but got: " + bo);
		AVector<ACell> bv = (AVector<ACell>) bo;
		int bn = bv.size();
		if ((bn & 1) != 0) return context.withCompileError(
				list.get(0) + " requires a binding vector with an even number of forms but got: " + bn);

		AVector<ACell> bindingForms = Vectors.empty();
		AVector<AOp<ACell>> ops = Vectors.empty();

		for (int i = 0; i < bn; i += 2) {		
			// Get corresponding op
			context = context.expandCompile(bv.get(i + 1));
			if (context.isExceptional()) return (Context<T>) context;
			AOp<ACell> op = (AOp<ACell>) context.getResult();
			ops = ops.conj(op);
			
			// Get a binding form. Note binding happens *after* op
			ACell bf = bv.get(i);
			context=compileBinding(bf,context);
			if (context.isExceptional()) return (Context<T>) context;
			bindingForms = bindingForms.conj(context.getResult());
		}
		int exs = n - 2; // expressions in let after binding vector
		for (int i = 2; i < 2 + exs; i++) {
			context = context.expandCompile(list.get(i));
			if (context.isExceptional()) return (Context<T>) context;
			AOp<ACell> op = (AOp<ACell>) context.getResult();
			ops = ops.conj(op);
		}
		AOp<R> op = Let.create(bindingForms, ops, isLoop);

		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}

	/**
	 * Compiles a binding form. Updates the current CompilerState. Should save compiler state if used
	 * @param bindingForm
	 * @param context
	 * @return
	 */
	private static Context<ACell> compileBinding(ACell bindingForm,Context<?> context) {
		CompilerState cs=context.getCompilerState();
		if (cs==null) cs=CompilerState.EMPTY;
		
		cs=updateBinding(bindingForm,cs);
		if (cs==null) return context.withCompileError("Bad binding form");
		
		context=context.withCompilerState(cs);
		return context.withResult(bindingForm);
	}
	
	@SuppressWarnings("unchecked")
	private static CompilerState updateBinding(ACell bindingForm,CompilerState cs) {
		if (bindingForm instanceof Symbol) {
			Symbol sym=(Symbol)bindingForm;
			if (!sym.equals(Symbols.UNDERSCORE)) {
				cs=cs.define(sym, null); // TODO: metadata?
			}
		} else if (bindingForm instanceof AVector) {
			AVector<ACell> v=(AVector<ACell>)bindingForm;
			boolean foundAmpersand=false;
			long vcount=v.count(); // count of binding form symbols (may include & etc.)
			for (long i=0; i<vcount; i++) {
				ACell bf=v.get(i);
				if (Symbols.AMPERSAND.equals(bf)) {
					if (foundAmpersand) return null; // double ampersand
					
					// skip to next element for binding
					if (i>=(vcount-1)) return null; // trailing ampersand
					foundAmpersand=true;
					bf=v.get(i+1);
					i++;
				} 
				cs=updateBinding(bf,cs);
				if (cs==null) return null;
			}
		} else {
			cs=null;
		}
		return cs;
	}

	/**
	 * Compiles a lambda function form "(fn [...] ...)" to create a Lambda op.
	 * 
	 * @param <R>
	 * @param <T>
	 * @param list
	 * @param context
	 * @return Context with compiled op as result.
	 */
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileFn(AList<ACell> list, Context<?> context) {
		// list.get(0) is presumably fn
		int n = list.size();
		if (n < 2) return context.withArityError("fn requires parameter vector and body in form: " + list);

		// check if we have a vector, in which case we have a single function definition
		ACell firstObject = Syntax.unwrap(list.get(1));
		if (firstObject instanceof AVector) {
			AVector<ACell> paramsVector=(AVector<ACell>) firstObject;
			AList<ACell> bodyList=list.drop(2); 
			return compileFnInstance(paramsVector,bodyList,context);
		}
		
		return compileMultiFn(list.drop(1),context);
	}
	
	@SuppressWarnings({ "unchecked"})
	private static <R extends ACell, T extends AOp<R>> Context<T> compileMultiFn(AList<ACell> list, Context<?> context) {
		AVector<AClosure<R>> fns=Vectors.empty();
		
		int num=list.size();
		for (int i=0; i<num; i++) {
			ACell o=Syntax.unwrap(list.get(i));
			if (!(o instanceof AList)) {
				return context.withError(ErrorCodes.COMPILE,"multi-function requires instances of form: ([args] ...) but got "+list);
			}
			
			context= compileFnInstance((AList<ACell>) o,context);
			if (context.isExceptional()) return (Context<T>) context;
			
			AClosure<R> compiledFn=((Lambda<R>) context.getResult()).getFunction();
			fns=fns.conj(compiledFn);
		}
			
		MultiFn<R> mf=MultiFn.create(fns);
		Lambda<R> op = Lambda.create(mf);
		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}
	
	/**
	 * Compiles a function instance function form "([...] ...)" to create a Lambda op.
	 * 
	 * @param <R> 
	 * @param <T>
	 * @param list
	 * @param context
	 * @return Context with compiled op as result.
	 */
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileFnInstance(AList<ACell> list, Context<?> context) {
		int n = list.size();
		if (n < 1) return context.withArityError("fn requires parameter vector and body in form: " + list);

		ACell firstObject = Syntax.unwrap(list.get(0));
		if (firstObject instanceof AVector) {
			AVector<ACell> paramsVector=(AVector<ACell>) firstObject;
			AList<ACell> bodyList=list.drop(1); 
			return compileFnInstance(paramsVector,bodyList,context);
		}
			
		return context.withError(ErrorCodes.COMPILE,
				"fn instance requires a vector of parameters but got form: " + list);
	}
		
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileFnInstance(AVector<ACell> paramsVector, AList<ACell> bodyList,Context<?> context) {
		// need to save compiler state, since we are compiling bindings
		CompilerState savedCompilerState=context.getCompilerState();
		
		context=compileBinding(paramsVector,context);
		if (context.isExceptional()) return context.withCompilerState(savedCompilerState); // restore before return
		paramsVector=(AVector<ACell>) context.getResult();
		
		context = context.compileAll(bodyList);
		if (context.isExceptional()) return context.withCompilerState(savedCompilerState); // restore before return

		int n=bodyList.size();
		AOp<T> body;
		if (n == 0) {
			// no body, so function just returns nil
			body = Constant.nil();
		} else if (n == 1) {
			// one body element, so just unwrap from list
			body = ((ASequence<AOp<T>>) context.getResult()).get(0);
		} else {
			// wrap multiple expressions in implicit do
			body = Do.create(((ASequence<AOp<ACell>>) context.getResult()));
		}

		Lambda<T> op = Lambda.create(paramsVector, body);
		context=context.withCompilerState(savedCompilerState);
		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileDef(AList<ACell> list, Context<?> context) {
		int n = list.size();
		if (n != 3) return context.withCompileError("def requires a symbol and an expression, but got: " + list);

		ACell symArg=list.get(1);

		{// check we are actually defining a symbol
			ACell sym = Syntax.unwrapAll(symArg);
			if (!(sym instanceof Symbol)) return context.withCompileError("def requires a Symbol as first argument but got: " + RT.getType(sym));
		}
		
		ACell exp=list.get(2);
		
		// move metadata from expression. TODO: do we need to expand this first?
		if (exp instanceof Syntax) {
			symArg=Syntax.create(symArg).mergeMeta(((Syntax)exp).getMeta());
			exp=Syntax.unwrap(exp);
		}
		
		context = context.compile(exp);
		if (context.isExceptional()) return (Context<T>) context;

		Def<R> op = Def.create(symArg, (AOp<R>) context.getResult());
		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}
	
	

	
	/**
	 * Initial expander used for expansion of forms prior to compilation.
	 * 
	 * Should work on both raw forms and syntax objects.
	 * 
	 * Follows the "Expansion-Passing Style" approach of Dybvig, Friedman, and Haynes
	 */
	public static final AFn<ACell> INITIAL_EXPANDER =new CoreFn<ACell>(Symbols.STAR_INITIAL_EXPANDER) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<ACell> invoke(Context<ACell> context,ACell[] args ) {
			if (args.length!=2) return context.withArityError(exactArityMessage(2, args.length));
			ACell x = args[0];
			AFn<ACell> cont=RT.ensureFunction(args[1]);
			if (cont==null) return context.withCastError(1, args,Types.FUNCTION);
			
			// If x is a Syntax object, need to compile the datum
			// TODO: check interactions with macros etc.
			if (x instanceof Syntax) {
				Syntax sx=(Syntax)x;
				ACell[] nargs=args.clone();
				nargs[0]=sx.getValue();
				context=context.invoke(this, nargs);
				if (context.isExceptional()) return context;
				ACell expanded=context.getResult();
				Syntax result=Syntax.mergeMeta(expanded,sx);
				return context.withResult(Juice.EXPAND_CONSTANT, result);
			}
			
			ACell form = x;
	
			// First check for sequences. This covers most cases.
			if (form instanceof ASequence) {
				
				// first check for List containing an expander
				if (form instanceof AList) {
					AList<ACell> listForm = (AList<ACell>) form;
					int n = listForm.size();
					// consider length 0 lists as constant
					if (n == 0) return context.withResult(Juice.EXPAND_CONSTANT, x);
	
					// we need to check if the form itself starts with an expander
					ACell first = Syntax.unwrap(listForm.get(0));
	
					// check for macro / expander in initial position.
					// Note that 'quote' is handled by this, via QUOTE_EXPANDER
					AFn<ACell> expander = context.lookupExpander(first);
					if (expander!=null) {
						return context.expand(expander,x, cont); // (exp x cont)
					}
				}

				// need to recursively expand collection elements
				// OK for vectors and lists
				ASequence<ACell> seq = (ASequence<ACell>) form;
				if (seq.isEmpty()) return context.withResult(Juice.EXPAND_CONSTANT, x);
				
				long n=seq.count();
				for (long i=0; i<n; i++) {
					ACell elem=seq.get(i);
				
					// Expand like: (cont x cont)
					context = context.expand(cont,elem, cont);
					if (context.isExceptional()) return context;

					ACell newElement = context.getResult();
					if (newElement!=elem) seq=seq.assoc(i, newElement);
				};
				Context<ACell> rctx = context;
				return rctx.withResult(Juice.EXPAND_SEQUENCE, seq);
			}

			if (form instanceof ASet) {
				@SuppressWarnings("rawtypes")
				Context<ACell> ctx =  (Context)context;
				ASet<ACell> updated = Sets.empty();
				for (ACell elem : ((ASet<ACell>) form)) {
					ctx = ctx.expand(cont, elem, cont);
					if (ctx.isExceptional()) return ctx;

					ACell newElement = ctx.getResult();
					updated = updated.conj(newElement);
				}
				return ctx.withResult(Juice.EXPAND_SEQUENCE, updated);
			}

			if (form instanceof AMap) {
				@SuppressWarnings("rawtypes")
				Context<ACell> ctx =  (Context)context;
				AMap<ACell, ACell> updated = Maps.empty();
				for (Map.Entry<ACell, ACell> me : ((AMap<ACell, ACell>) form).entrySet()) {
					// get new key
					ctx = ctx.expand(cont,me.getKey(), cont);
					if (ctx.isExceptional()) return ctx;

					ACell newKey = ctx.getResult();

					// get new value
					ctx = ctx.expand(cont,me.getValue(), cont);
					if (ctx.isExceptional()) return ctx;
					ACell newValue = ctx.getResult();

					updated = updated.assoc(newKey, newValue);
				}
				return ctx.withResult(Juice.EXPAND_SEQUENCE, updated);
			}

			// Return the Syntax Object directly for anything else
			// Remember to preserve metadata on symbols in particular!
			return context.withResult(Juice.EXPAND_CONSTANT, x);
		}
	};
	
	/**
	 * Expander used for expansion of `quote` forms.
	 * 
	 * Should work on both raw forms and syntax objects.
	 * 
	 * Follows the "Expansion-Passing Style" approach of Dybvig, Friedman, and Haynes
	 */
	public static final AFn<ACell> QUOTE_EXPANDER =new CoreFn<ACell>(Symbols.QUOTE) {
		@Override
		public Context<ACell> invoke(Context<ACell> context,ACell[] args ) {
			if (args.length!=2) return context.withArityError(exactArityMessage(2, args.length));
			ACell x = args[0];
		
			return context.withResult(Juice.EXPAND_CONSTANT,x);
		}
	};
	
	/**
	 * Expander used for expansion of `quasiquote` forms.
	 * 
	 * Should work on both raw forms and syntax objects.
	 * 
	 * Follows the "Expansion-Passing Style" approach of Dybvig, Friedman, and Haynes
	 */
	public static final AFn<ACell> QUASIQUOTE_EXPANDER =new CoreFn<ACell>(Symbols.QUASIQUOTE) {
		@Override
		public Context<ACell> invoke(Context<ACell> context,ACell[] args ) {
			if (args.length!=2) return context.withArityError(exactArityMessage(2, args.length));
			ACell x = args[0];
		
			return context.withResult(Juice.EXPAND_CONSTANT,x);
		}
	};
	

}
