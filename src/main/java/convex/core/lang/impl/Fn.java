package convex.core.lang.impl;

import java.nio.ByteBuffer;

import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Symbols;

/**
 * Value class representing a instantiated closure / lambda function.
 * 
 * Includes the following information:
 * <ol>
 * <li>Parameters of the function, as a vector of Syntax objects</li>
 * <li>Body of the function, as a compiled operation</li>
 * <li>captured lexical bindings at time of creation.</li>
 * </ol>
 * 
 * @param <T> Return type of function
 */
public class Fn<T> extends AClosure<T> {

	// note: embedding these fields directly for efficiency rather than going by
	// Refs.

	private final AVector<Syntax> params;
	private final AOp<T> body;
	
	private Long variadic=null;

	private Fn(AVector<Syntax> params, AOp<T> body, AHashMap<Symbol, Object> lexicalEnv) {
		super(lexicalEnv);
		this.params = params;
		this.body = body;
	}

	public static <T, I> Fn<T> create(AVector<Syntax> params, AOp<T> body, Context<I> context) {
		AHashMap<Symbol, Object> binds = context.getLocalBindings();
		return new Fn<T>(params, body, binds);
	}
	
	public static <T, I> Fn<T> create(AVector<Syntax> params, AOp<T> body) {
		AHashMap<Symbol, Object> binds = Maps.empty();
		return new Fn<T>(params, body, binds);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <F extends AClosure<T>> F withEnvironment(AHashMap<Symbol, Object> env) {
		if (this.lexicalEnv==env) return (F) this;
		return (F) new Fn<T>(params, body, env);
	}
	
	@Override
	public boolean hasArity(int n) {
		long var=checkVariadic();
		long pc=params.count();
		if (var>=0) return n>=(pc-2); // n must be at least number of params excluding [& more]
		return (n==pc);
	}

	/**
	 * Checks if the function is variadic.
	 * 
	 * @return negative if non-variadic, index of variadic parameter if variadic
	 */
	private Long checkVariadic() {
		if (variadic!=null) return variadic;
		long pc=params.count();
		for (int i=0; i<pc-1; i++) {
			Syntax syn=params.get(i);
			if (Symbols.AMPERSAND.equals(syn.getValue())) {
				variadic=(long) (i+1);
				return variadic;
			}
		}
		variadic=-1L;
		return -1L;
	}

	@Override
	public <I> Context<T> invoke(Context<I> context, Object[] args) {
		// update local bindings for the duration of this function call
		final AHashMap<Symbol, Object> savedBindings = context.getLocalBindings();

		// update to correct lexical environment, then bind function parameters
		context = context.withLocalBindings(lexicalEnv);
		Context<?> boundContext = context.updateBindings(params, args);
		if (boundContext.isExceptional()) return boundContext.withLocalBindings(savedBindings);

		Context<T> ctx = boundContext.execute(body);

		// return with restored bindings
		return ctx.withLocalBindings(savedBindings);
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.FN;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = params.write(bs,pos);
		pos = body.write(bs,pos);
		pos = lexicalEnv.write(bs,pos);
		return pos;
	}

	public static <T> Fn<T> read(ByteBuffer bb) throws BadFormatException {
		try {
			AVector<Syntax> params = Format.read(bb);
			if (params==null) throw new BadFormatException("Null parameters to Fn");
			AOp<T> body = Format.read(bb);
			if (body==null) throw new BadFormatException("Null body in Fn");
			AHashMap<Symbol, Object> lexicalEnv = Format.read(bb);
			return new Fn<>(params, body, lexicalEnv);
		} catch (ClassCastException e) {
			throw new BadFormatException("Bad Fn format", e);
		}
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("(fn ");
		params.ednString(sb);
		sb.append(' ');
		body.ednString(sb);
		sb.append(')');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("(fn ");
		printParamBody(sb);
		sb.append(')');
	}
	
	public void printParamBody(StringBuilder sb) {
		params.print(sb);
		sb.append(' ');
		body.print(sb);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	/**
	 * Returns the declared param names for a function.
	 * 
	 * @return A binding vector describing the parameters for this function
	 */
	public AVector<Syntax> getParams() {
		return params;
	}

	public AOp<T> getBody() {
		return body;
	}

	@Override
	public int getRefCount() {
		return params.getRefCount() + body.getRefCount() + lexicalEnv.getRefCount();
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		int pc = params.getRefCount();
		if (i < pc) return params.getRef(i);
		i -= pc;
		int bc = body.getRefCount();
		if (i < bc) return body.getRef(i);
		i -= bc;
		return lexicalEnv.getRef(i);
	}

	@Override
	public Fn<T> updateRefs(IRefFunction func) {
		AVector<Syntax> newParams = params.updateRefs(func);
		AOp<T> newBody = body.updateRefs(func);
		AHashMap<Symbol, Object> newLexicalEnv = lexicalEnv.updateRefs(func);
		if ((params == newParams) && (body == newBody) && (lexicalEnv == newLexicalEnv)) return this;
		return new Fn<>(newParams, newBody, lexicalEnv);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		params.validateCell();
		body.validateCell();
		lexicalEnv.validateCell();
	}


}
