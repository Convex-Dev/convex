package convex.core.lang.impl;

import convex.core.cvm.AFn;
import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Ops;
import convex.core.cvm.Symbols;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.prim.ByteFlag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;

/**
 * Value class representing a instantiated closure / lambda function.
 * 
 * Includes the following information:
 * <ol>
 * <li>0 = >Byte flag representing a simple function closure (0xB0) 
 * <li>1 = Parameters of the function, as a vector of Syntax objects</li>
 * <li>2 = captured lexical bindings at time of creation. (can be null for empty)</li>
 * <li>3 = Body of the function, as a compiled operation</li>
 * </ol>
 * 
 * @param <T> Return type of function
 */
public class Fn<T extends ACell> extends AClosure<T> {
	
	private static final ByteFlag CODE=new ByteFlag(CVMTag.FN_NORMAL);
	
	private Long variadic=null;
	
	private Fn(AVector<ACell> data) {
		super(data);
	}
	
	public static <T extends ACell, I> Fn<T> create(AVector<ACell> params, AOp<T> body) {
		AVector<ACell> data=Vectors.create(CODE,params,null,body);
		return new Fn<T>(data);
	}
	
	public static <T extends ACell, I> Fn<T> create(AVector<ACell> data) throws BadFormatException {
		if (data.count()!=4) throw new BadFormatException("Invalid function data length");
		if (!data.getRef(0).isEmbedded()) throw new BadFormatException("Non-embedded Fn type");
		return new Fn<T>(data);
	}
	
	@Override
	protected AFn<T> recreate(AVector<ACell> newData) {
		return new Fn<>(newData);
	}


	
	@SuppressWarnings("unchecked")
	@Override
	public <F extends AClosure<T>> F withEnvironment(AVector<ACell> env) {
		if (this.getLexicalEnvironment()==env) return (F) this;
		return (F) new Fn<T>(data.assoc(2, env));
	}
	
	public AVector<ACell> getLexicalEnvironment() {
		AVector<ACell> env= RT.ensureVector(data.get(2));
		if (env==null) {
			env=EMPTY_BINDINGS;
		}
		return env;
	}

	@Override
	public boolean hasArity(int n) {
		long var=checkVariadic();
		long pc=getParams().count();
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
		AVector<ACell> params=getParams();
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
		context = context.withLocalBindings(getLexicalEnvironment());

		Context boundContext = context.updateBindings(getParams(), args);
		if (boundContext.isExceptional()) return boundContext.withLocalBindings(savedBindings);

		Context ctx = boundContext.execute(getBody());

		// return with restored bindings
		return ctx.withLocalBindings(savedBindings);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}


	/**
	 * Decodes a function instance from a Blob encoding.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> AClosure<T> read(Blob b, int pos) throws BadFormatException {
		
		AVector<ACell> data = Vectors.read(b,pos);
		long n=data.count();
		if (n==0) throw new BadFormatException("Empty record in Fn");
		
		byte type=b.byteAtUnchecked(pos+1+Format.getVLQCountLength(n)); // byte after tag can indicate normal function type
		
		AClosure<T> result;
		switch (type) {
			case CVMTag.FN_NORMAL: result= Fn.create(data); break;
			default: result= MultiFn.create(data); break;
		}
		
		int epos=pos+data.getEncodingLength();
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
		AVector<ACell> params=getParams();
		long size = params.count();
		for (long i = 0; i < size; i++) {
			if (i > 0) sb.append(' ');
			if (!RT.print(sb,params.get(i),limit)) return false;
		}
		sb.append(']');

		sb.append(' ');
		return getBody().print(sb,limit);
	}

	/**
	 * Returns the declared param names for a function.
	 * 
	 * @return A binding vector describing the parameters for this function
	 */
	public AVector<ACell> getParams() {
		AVector<ACell> result=RT.ensureVector(data.get(1));
		return result;
	}

	public AOp<T> getBody() {
		AOp<T> result=Ops.ensureOp(data.get(3));
		return result;
	}

	@Override
	public ACell toCanonical() {
		return this;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> AClosure<T> ensureFunction(ACell a) {
		if (a instanceof AClosure) {
			return (AClosure<T>)a;
		}
		return null;
	}
}
