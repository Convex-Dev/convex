package convex.core.lang;

import java.util.Map;

import convex.core.ErrorCodes;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Keyword;
import convex.core.data.List;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Set;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.exceptions.TODOException;
import convex.core.lang.expanders.AExpander;
import convex.core.lang.expanders.CoreExpander;
import convex.core.lang.impl.MultiFn;
import convex.core.lang.ops.Cond;
import convex.core.lang.ops.Constant;
import convex.core.lang.ops.Def;
import convex.core.lang.ops.Do;
import convex.core.lang.ops.Invoke;
import convex.core.lang.ops.Lambda;
import convex.core.lang.ops.Let;
import convex.core.lang.ops.Lookup;
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
		AExpander ex = INITIAL_EXPANDER;
		
		// use initial expander both as current and continuation expander
		// call expand via context to get correct depth and exception handling
		final Context<Syntax> ctx = context.expand(form, ex,ex);
		
		if (ctx.isExceptional()) return (Context<AOp<T>>) (Object) ctx;

		// compile phase
		Syntax expandedForm = ctx.getResult();
		return compile(expandedForm, ctx);
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
	static <T extends ACell> Context<AOp<T>> compile(Syntax expandedForm, Context<?> context) {
		ACell form = expandedForm.getValue();
		if (form==null) return compileConstant(context,null);
		return compileCell(form, context);
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
	static <T extends ACell> Context<AVector<AOp<T>>> compileAll(ASequence<Syntax> forms, Context<?> context) {
		if (forms == null) return context.withResult(Vectors.empty()); // consider null as empty list
		int n = forms.size();
		AVector<AOp<T>> obs = Vectors.empty();
		for (int i = 0; i < n; i++) {
			Syntax form = forms.get(i);
			context = context.compile(form);
			if (context.isExceptional()) return (Context<AVector<AOp<T>>>) context;
			obs = obs.conj((AOp<T>) context.getResult());
		}
		return context.withResult(obs);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileCell(ACell form, Context<?> context) {
		if (!form.isCanonical()) {
			return context.withCompileError("Form not canonical: " + form.getClass());
		}

		if (form instanceof AList) return compileList((AList<Syntax>) form, context);
		if (form instanceof AVector) return compileVector((AVector<Syntax>) form, context);
		if (form instanceof AMap) return compileMap((AMap<Syntax, Syntax>) form, context);
		if (form instanceof ASet) return compileSet((ASet<Syntax>) form, context);

		if ((form instanceof Keyword) || (form instanceof ABlob)) {
			return compileConstant(context, form);
		}

		if (form instanceof Symbol) {
			return compileSymbolLookup((Symbol) form, context);
		}
		
		if (form instanceof AOp) {
			// already compiled, just return as constant
			return context.withResult(Juice.COMPILE_CONSTANT, (T)form);
		}

		// return as a constant literal
		return compileConstant(context,form);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileSymbolLookup(Symbol sym, Context<?> context) {
		// Get address of compilation environment to use for lookup resolution.
		Address address=context.getAddress();
		
		Lookup<T> lookUp=Lookup.create(address,sym);
		return (Context<T>) context.withResult(Juice.COMPILE_LOOKUP, lookUp);
	}

	private static <R extends ACell, T extends AOp<R>> Context<T> compileMap(AMap<Syntax, Syntax> form, Context<?> context) {
		int n = form.size();
		Object[] vs = new Object[1 + n * 2];
		vs[0] = Syntax.create(Symbols.HASH_MAP);
		for (int i = 0; i < n; i++) {
			MapEntry<Syntax, Syntax> me = form.entryAt(i);
			vs[1 + i * 2] = me.getKey();
			vs[1 + i * 2 + 1] = me.getValue();
		}
		return compileList(Lists.of(vs), context);
	}

	private static <R extends ACell, T extends AOp<R>> Context<T> compileSet(ASet<Syntax> form, Context<?> context) {
		AVector<Syntax> vs = Vectors.empty();
		for (Syntax o : form) {
			vs = vs.conj(o);
		}
		vs = vs.conj(Syntax.create(Symbols.HASH_SET));
		return compileList(List.reverse(vs), context);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileVector(AVector<Syntax> vec, Context<?> context) {
		int n = vec.size();
		if (n == 0) return (Context<T>) context.withResult(Juice.COMPILE_CONSTANT, Constant.EMPTY_VECTOR);

		context = context.compileAll(vec);
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
	 * @param form Quoted form Syntax Object
	 * @return Context with complied op as result
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> Context<AOp<T>> compileQuoted(Context<?> context, Syntax aForm) {
		ACell form = aForm.getValue();
		if (form instanceof ASequence) {
			ASequence<Syntax> seq = (ASequence<Syntax>) form;
			int n = seq.size();
			if (n == 0) return compileConstant(context, seq);

			if (isListStarting(Symbols.UNQUOTE, form)) {
				if (n != 2) return context.withArityError("unquote requires 1 argument");
				Context<AOp<T>> opContext = expandCompile(seq.get(1), context);
				return opContext;
			}

			// compile quoted elements
			context = compileAllQuoted(context, seq);
			ASequence<AOp<ACell>> rSeq = (ASequence<AOp<ACell>>) context.getResult();

			ACell fn = (seq instanceof AList) ? Core.LIST : Core.VECTOR;
			Invoke<T> inv = Invoke.create( Constant.create(fn), rSeq);
			return context.withResult(Juice.COMPILE_NODE, inv);
		} else if (form instanceof AMap) {
			AMap<Syntax, Syntax> map = (AMap<Syntax, Syntax>) form;
			AVector<Syntax> rSeq = Vectors.empty();
			for (Map.Entry<Syntax, Syntax> me : map.entrySet()) {
				rSeq = rSeq.append(me.getKey());
				rSeq = rSeq.append(me.getValue());
			}

			// compile quoted elements
			context = compileAllQuoted(context, rSeq);
			ASequence<AOp<ACell>> cSeq = (ASequence<AOp<ACell>>) context.getResult();

			ACell fn = Core.HASHMAP;
			Invoke<T> inv = Invoke.create(Constant.create(fn), cSeq);
			return context.withResult(Juice.COMPILE_NODE, inv);
		} else {
			return compileConstant(context, form);
		}
	}

	/**
	 * Compiles a sequence of quoted forms
	 * 
	 * @param <T>
	 * @param context
	 * @param form
	 * @return Context with complied sequence of ops as result
	 */
	@SuppressWarnings("unchecked")
	private static <T extends ACell> Context<ASequence<AOp<T>>> compileAllQuoted(Context<?> context, ASequence<Syntax> forms) {
		int n = forms.size();
		// create a list of ops producing each sub-element
		ASequence<AOp<T>> rSeq = Vectors.empty();
		for (int i = 0; i < n; i++) {
			Syntax subSyntax = forms.get(i);
			ACell subForm = subSyntax.getValue();
			if (isListStarting(Symbols.UNQUOTE_SPLICING, subForm)) {
				AList<ACell> subList = (AList<ACell>) subForm;
				int sn = subList.size();
				if (sn != 2) return context.withArityError("unquote-splicing requires 1 argument");
				// unquote-splicing looks like it needs flatmap
				throw new TODOException();
			} else {
				Context<AOp<T>> rctx= compileQuoted(context, subSyntax);
				rSeq = (ASequence<AOp<T>>) (rSeq.conj(rctx.getResult()));
			}
		}
		return context.withResult(rSeq);
	}

	/**
	 * Returns true if the form is a List starting with Syntax Object equal to the
	 * the specified element
	 * 
	 * @param element
	 * @param form
	 * @return True if form is a list starting with a Syntax Object wrapping the
	 *         specified element, false otherwise.
	 */
	@SuppressWarnings("unchecked")
	private static boolean isListStarting(Symbol element, Object form) {
		if (!(form instanceof AList)) return false;
		AList<Syntax> list = (AList<Syntax>) form;
		if (list.count() == 0) return false;
		Object firstElement=list.get(0);
		return Utils.equals(element, Syntax.unwrap(firstElement));
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileList(AList<Syntax> list, Context<?> context) {
		int n = list.size();
		if (n == 0) return (Context<T>) context.withResult(Juice.COMPILE_CONSTANT, Constant.EMPTY_LIST);

		// first entry in list should be syntax
		Object first = list.get(0);
		if (!(first instanceof Syntax)) {
			throw new Error("Expected Syntax in first position of: " + list);
		}

		ACell head = ((Syntax) first).getValue();

		if (head instanceof Symbol) {
			Symbol sym = (Symbol) head;

			// special form symbols
			if (sym.equals(Symbols.QUOTE) || sym.equals(Symbols.SYNTAX_QUOTE)) {
				if (list.size() != 2) return context.withCompileError(Symbols.QUOTE + " expects one argument.");
				return (Context<T>) compileQuoted(context, list.get(1));
			}

			if (sym.equals(Symbols.UNQUOTE)) {
				// execute the unquoted code to get a form to compile
				if (list.size() != 2) return context.withCompileError(Symbols.QUOTE + " expects one argument.");
				context = context.expandCompile(list.get(1));
				AOp<T> quotedOp = (AOp<T>) context.getResult();

				Context<T> rctx = context.execute(quotedOp);
				if (rctx.isExceptional()) return (Context<T>) rctx;

				Syntax resultForm = Syntax.create(rctx.getResult());
				// need to expand and compile here, since we just created a raw form
				return (Context<T>) expandCompile(resultForm, context);
			}

			if (sym.equals(Symbols.DO)) {
				context = context.compileAll(list.next());
				if (context.isExceptional()) return (Context<T>) context;
				Do<R> op = Do.create((AVector<AOp<ACell>>) context.getResult());
				return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
			}

			if (sym.equals(Symbols.LET)) return compileLet(list, context, false);
			if (sym.equals(Symbols.LOOP)) return compileLet(list, context, true);

			if (sym.equals(Symbols.COND)) {
				context = context.compileAll(list.next());
				if (context.isExceptional()) return (Context<T>) context;
				Cond<R> op = Cond.create((AVector<AOp<ACell>>) (context.getResult()));
				return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
			}
			if (sym.equals(Symbols.DEF)) return compileDef(list, context);
			if (sym.equals(Symbols.FN)) return compileFn(list, context);
		}
		
		// must be a regular function call
		context = context.compileAll(list);
		if (context.isExceptional()) return (Context<T>) context;
		Invoke<R> op = Invoke.create((AVector<AOp<ACell>>) context.getResult());

		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileLet(ASequence<Syntax> list, Context<?> context,
			boolean isLoop) {
		// list = (let [...] a b c ...)
		Syntax bindingSyntax = list.get(1);
		Object bo = bindingSyntax.getValue();

		if (!(bo instanceof AVector))
			return context.withCompileError(list.get(0) + " requires a vector of binding forms but got: " + bo);
		AVector<Syntax> bv = (AVector<Syntax>) bo;
		int bn = bv.size();
		if ((bn & 1) != 0) return context.withCompileError(
				list.get(0) + " requires a binding vector with an even number of forms but got: " + bn);

		AVector<Syntax> bindingForms = Vectors.empty();
		AVector<AOp<ACell>> ops = Vectors.empty();

		for (int i = 0; i < bn; i += 2) {
			Syntax bf = bv.get(i);
			bindingForms = bindingForms.conj(bf);
			context = context.expandCompile(bv.get(i + 1));
			if (context.isExceptional()) return (Context<T>) context;
			AOp<ACell> op = (AOp<ACell>) context.getResult();
			ops = ops.conj(op);
		}
		int exs = list.size() - 2; // expressions in let after binding vector
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
	 * Compiles a lambda function form "(fn [...] ...)" to create a Lambda op.
	 * 
	 * @param <R>
	 * @param <T>
	 * @param list
	 * @param context
	 * @return Context with compiled op as result.
	 */
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileFn(AList<Syntax> list, Context<?> context) {
		// list.get(0) is presumably fn
		int n = list.size();
		if (n < 2) return context.withArityError("fn requires parameter vector and body in form: " + list);

		// check if we have a vector, in which case we have a single function definition
		Object firstObject = list.get(1).getValue();
		if (firstObject instanceof AVector) {
			AVector<Syntax> paramsVector=(AVector<Syntax>) firstObject;
			AList<Syntax> bodyList=list.drop(2); 
			return compileFnInstance(paramsVector,bodyList,context);
		}
		
		return compileMultiFn(list.drop(1),context);
	}
	
	@SuppressWarnings({ "unchecked"})
	private static <R extends ACell, T extends AOp<R>> Context<T> compileMultiFn(AList<Syntax> list, Context<?> context) {
		AVector<AFn<R>> fns=Vectors.empty();
		
		int num=list.size();
		for (int i=0; i<num; i++) {
			Object o=list.get(i).getValue();
			if (!(o instanceof AList)) {
				return context.withError(ErrorCodes.COMPILE,"multi-function requires instances of form: ([args] ...)");
			}
			
			context= compileFnInstance((AList<Syntax>) o,context);
			if (context.isExceptional()) return (Context<T>) context;
			
			AFn<R> compiledFn=((Lambda<R>) context.getResult()).getFunction();
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
	private static <R extends ACell, T extends AOp<R>> Context<T> compileFnInstance(AList<Syntax> list, Context<?> context) {
		int n = list.size();
		if (n < 1) return context.withArityError("fn requires parameter vector and body in form: " + list);

		Object firstObject = list.get(0).getValue();
		if (firstObject instanceof AVector) {
			AVector<Syntax> paramsVector=(AVector<Syntax>) firstObject;
			AList<Syntax> bodyList=list.drop(1); 
			return compileFnInstance(paramsVector,bodyList,context);
		}
			
		return context.withError(ErrorCodes.COMPILE,
				"fn instance requires a vector of parameters but got form: " + list);
	}
		
	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileFnInstance(AVector<Syntax> paramsVector, AList<Syntax> bodyList,Context<?> context) {
		context = context.compileAll(bodyList);
		if (context.isExceptional()) return (Context<T>) context;

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
		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}

	@SuppressWarnings("unchecked")
	private static <R extends ACell, T extends AOp<R>> Context<T> compileDef(AList<Syntax> list, Context<?> context) {
		int n = list.size();
		if (n != 3) return context.withCompileError("def requires a symbol and an expression, but got: " + list);

		Syntax symbolSyntax = list.get(1);

		{// check we are actually defining a symbol
			Object so = symbolSyntax.getValue();
			if (!(so instanceof Symbol)) return context.withCompileError("def requires a symbol as first argument but got: " + so);
		}
		
		Syntax expSyntax=list.get(2);
		context = context.expandCompile(expSyntax);
		if (context.isExceptional()) return (Context<T>) context;
		
		// merge in metadata from expression. TODO: do we need to expand this first?
		symbolSyntax=symbolSyntax.mergeMeta(expSyntax.getMeta());
		
		Def<R> op = Def.create(symbolSyntax, (AOp<R>) context.getResult());
		return (Context<T>) context.withResult(Juice.COMPILE_NODE, op);
	}
	
	

	
	/**
	 * Initial expander used for expansion of forms prior to compilation.
	 * 
	 * Should work on both raw forms and syntax objects.
	 * 
	 * Follows the "Expansion-Passing Style" approach of Dybvig, Friedman, and Haynes
	 */
	public static final CoreExpander INITIAL_EXPANDER =new CoreExpander(Symbols.STAR_INITIAL_EXPANDER) {
		@SuppressWarnings("unchecked")
		@Override
		public Context<Syntax> expand(ACell x, AExpander cont, Context<?> context) {
			// Ensure x is wrapped as Syntax Object. This will preserve metadata if present.
			Syntax formSyntax=Syntax.create(x);
			Object form = formSyntax.getValue();

			// Return the Syntax Object immediately for symbols, keywords, literals etc.
			// Remember to preserve metadata on symbols in particular!
			if (!(form instanceof ADataStructure)) {
				// TODO: handle symbol macros?
				
				return context.withResult(Juice.EXPAND_CONSTANT, formSyntax);
			}

			// First check for sequences. This covers most cases.
			if (form instanceof ASequence) {
				
				// first check for List
				if (form instanceof AList) {
					AList<ACell> listForm = (AList<ACell>) form;
					int n = listForm.size();
					// consider length 0 lists as constant
					if (n == 0) return context.withResult(Juice.EXPAND_CONSTANT, formSyntax);
	
					// we need to check if the form itself starts with an expander
					Object first = Syntax.unwrap(listForm.get(0));
	
					// check for macro / expander in initial position.
					if (first instanceof Symbol) {
						Symbol sym = (Symbol) first;
						
						// TODO: handle quote
						// if (sym.equals(Symbols.QUOTE)) {
						//	if (listForm.size() != 2) return context.withCompileError(Symbols.QUOTE + " expects one argument.");
						//	Syntax syn=Syntax.create(listForm.get(1));
						//	Syntax quoteSyn=Syntax.create(Lists.of(Syntax.create(Symbols.QUOTE),syn));
						//	return context.withResult(Juice.EXPAND_CONSTANT,quoteSyn);
						// }
						
						MapEntry<Symbol, Syntax> me = context.lookupDynamicEntry(sym);
						if (me != null) {
							// TODO: examine syntax object for expander details?
							Object v = me.getValue().getValue();
							if (v instanceof AExpander) {
								// expand form using specified expander and continuation expander
								AExpander expander = (AExpander) v;
								context = context.expand(formSyntax, expander, cont); // (exp x cont)
								return (Context<Syntax>) context;
							}
						}
					}
				}

				// need to recursively expand collection elements
				// OK for vectors and lists
				ASequence<ACell> seq = (ASequence<ACell>) form;
				if (seq.isEmpty()) return context.withResult(Juice.EXPAND_CONSTANT, formSyntax);
				Context<Syntax>[] ct = new Context[] { context };
				ASequence<Syntax> updated;

				updated = seq.map(elem -> {
					Context<Syntax> ctx = ct[0];
					if (ctx.isExceptional()) return null;

					// Expand like: (cont x cont)
					ctx = ctx.expand(elem, cont, cont);

					if (ctx.isExceptional()) {
						ct[0] = ctx;
						return null;
					}
					Syntax newElement = ctx.getResult();
					ct[0] = ctx;
					return newElement;
				});
				context = ct[0];
				if (context.isExceptional()) return (Context<Syntax>) context;
				return context.withResult(Juice.EXPAND_SEQUENCE, Syntax.create(updated).withMeta(formSyntax.getMeta()));
			}

			if (form instanceof ASet) {
				Context<Syntax> ctx = (Context<Syntax>) context;
				Set<Syntax> updated = Sets.empty();
				for (ACell elem : ((ASet<ACell>) form)) {
					ctx = ctx.expand(elem, cont, cont);
					if (ctx.isExceptional()) return ctx;

					Syntax newElement = ctx.getResult();
					updated = updated.conj(newElement);
				}
				return ctx.withResult(Juice.EXPAND_SEQUENCE, Syntax.create(updated).withMeta(formSyntax.getMeta()));
			}

			if (form instanceof AMap) {
				Context<Syntax> ctx = (Context<Syntax>) context;
				AMap<Syntax, Syntax> updated = Maps.empty();
				for (Map.Entry<ACell, ACell> me : ((AMap<ACell, ACell>) form).entrySet()) {
					// get new key
					ctx = ctx.expand(me.getKey(), cont, cont);
					if (ctx.isExceptional()) return ctx;

					Syntax newKey = ctx.getResult();

					// get new value
					ctx = ctx.expand(me.getValue(), cont, cont);
					if (ctx.isExceptional()) return ctx;
					Syntax newValue = ctx.getResult();

					updated = updated.assoc(newKey, newValue);
				}
				return ctx.withResult(Juice.EXPAND_SEQUENCE, Syntax.create(updated).withMeta(formSyntax.getMeta()));
			}
			
			// If it's an Op, leave unchanged. Effectively a constant from expander POV.
			if (form instanceof AOp) {
				return context.withResult(Juice.EXPAND_CONSTANT, formSyntax);
			}

			throw new TODOException("Don't know how to expand: " + Utils.getClass(form));
		}
	};
}
