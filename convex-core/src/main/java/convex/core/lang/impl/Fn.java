package convex.core.lang.impl;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbols;
import convex.core.data.Tag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

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

	private final AVector<ACell> params;
	private final AOp<T> body;
	
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

	@Override
	public Context invoke(Context context, ACell[] args) {
		// update local bindings for the duration of this function call
		final AVector<ACell> savedBindings = context.getLocalBindings();

		// update to correct lexical environment, then bind function parameters
		context = context.withLocalBindings(lexicalEnv);

		Context boundContext = context.updateBindings(params, args);
		if (boundContext.isExceptional()) return boundContext.withLocalBindings(savedBindings);

		Context ctx = boundContext.execute(body);

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

	/**
	 * Decodes a function instance from a Blob encoding.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Fn<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		AVector<ACell> params = Format.read(b,epos);
		if (params==null) throw new BadFormatException("Null parameters to Fn");
		epos+=Format.getEncodingLength(params);
		
		AOp<T> body = Format.read(b,epos);
		if (body==null) throw new BadFormatException("Null body in Fn");
		epos+=Format.getEncodingLength(body);
		
		AVector<ACell> lexicalEnv = Format.read(b,epos);
		epos+=Format.getEncodingLength(lexicalEnv);

		
		Fn<T> result = new Fn<>(params, body, lexicalEnv);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}


	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("(fn ");
		printInternal(sb,limit);
		sb.append(')');
		return sb.check(limit);
	}
	
	@Override
	public boolean printInternal(BlobBuilder sb, long limit) {
		// Custom param printing, avoid printing Syntax metadata for now
		sb.append('[');
		long size = params.count();
		for (long i = 0; i < size; i++) {
			if (i > 0) sb.append(' ');
			if (!RT.print(sb,params.get(i),limit)) return false;
		}
		sb.append(']');

		sb.append(' ');
		return body.print(sb,limit);
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
		AVector<ACell> newParams = params.updateRefs(func);
		AOp<T> newBody = body.updateRefs(func);
		AVector<ACell> newLexicalEnv = lexicalEnv.updateRefs(func);
		if ((params == newParams) && (body == newBody) && (lexicalEnv == newLexicalEnv)) return this;
		return new Fn<>(newParams, newBody, newLexicalEnv);
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
