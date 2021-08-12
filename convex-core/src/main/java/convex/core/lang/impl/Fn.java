package convex.core.lang.impl;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Symbols;
import convex.core.util.Utils;

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
public class Fn<T extends ACell> extends AClosure<T> {

	// note: embedding these fields directly for efficiency rather than going by
	// Refs.

	private AVector<ACell> params;
	private AOp<T> body;
	
	private Long variadic=null;

	private Fn(AVector<ACell> params, AOp<T> body, AVector<ACell> lexicalEnv) {
		super(lexicalEnv);
		this.params = params;
		this.body = body;
	}
	
	public static <T extends ACell, I> Fn<T> create(AVector<ACell> params, AOp<T> body) {
		AVector<ACell> binds = Context.EMPTY_BINDINGS;
		return new Fn<T>(params, body, binds);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <F extends AClosure<T>> F withEnvironment(AVector<ACell> env) {
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
			ACell param=params.get(i);
			if (Symbols.AMPERSAND.equals(param)) {
				variadic=(long) (i+1);
				return variadic;
			}
		}
		variadic=-1L;
		return -1L;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Context<T> invoke(Context context, ACell[] args) {
		// update local bindings for the duration of this function call
		final AVector<ACell> savedBindings = context.getLocalBindings();

		// update to correct lexical environment, then bind function parameters
		context = context.withLocalBindings(lexicalEnv);

		Context<T> boundContext = context.updateBindings(params, args);
		if (boundContext.isExceptional()) return boundContext.withLocalBindings(savedBindings);

		Context<T> ctx = boundContext.execute(body);

		// return with restored bindings
		return ctx.withLocalBindings(savedBindings);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.FN;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = params.encode(bs,pos);
		pos = body.encode(bs,pos);
		pos = lexicalEnv.encode(bs,pos);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+params.estimatedEncodingSize()+body.estimatedEncodingSize()+lexicalEnv.estimatedEncodingSize();
	}

	public static <T extends ACell> Fn<T> read(ByteBuffer bb) throws BadFormatException {
		try {
			AVector<ACell> params = Format.read(bb);
			if (params==null) throw new BadFormatException("Null parameters to Fn");
			AOp<T> body = Format.read(bb);
			if (body==null) throw new BadFormatException("Null body in Fn");
			AVector<ACell> lexicalEnv = Format.read(bb);
			return new Fn<>(params, body, lexicalEnv);
		} catch (ClassCastException e) {
			throw new BadFormatException("Bad Fn format", e);
		}
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("(fn ");
		printInternal(sb);
		sb.append(')');
	}
	
	@Override
	public void printInternal(StringBuilder sb) {
		// Custom param printing, avoid printing Syntax metadata for now
		sb.append('[');
		long size = params.count();
		for (long i = 0; i < size; i++) {
			if (i > 0) sb.append(' ');
			Utils.print(sb,params.get(i));
		}
		sb.append(']');

		sb.append(' ');
		body.print(sb);
	}

	/**
	 * Returns the declared param names for a function.
	 * 
	 * @return A binding vector describing the parameters for this function
	 */
	public AVector<ACell> getParams() {
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
	public <R extends ACell> Ref<R> getRef(int i) {
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
		params = params.updateRefs(func);
		body = body.updateRefs(func);
		lexicalEnv = lexicalEnv.updateRefs(func);
		return this;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		params.validateCell();
		body.validateCell();
		lexicalEnv.validateCell();
	}

	@Override
	public ACell toCanonical() {
		return this;
	}


	


}
