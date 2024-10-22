package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.Fn;
import convex.core.util.ErrorMessages;
import convex.core.util.Utils;

/**
 * Op responsible for creating a new function (closure).
 * 
 * Captures value of local variable bindings during execution.
 * 
 * Equivalent to (fn [...] ...)
 *
 * @param <T> Result type of Closure
 */
public class Lambda<T extends ACell> extends AOp<AClosure<T>> {
	
	private Ref<AClosure<T>> function;

	protected Lambda(Ref<AClosure<T>> newFunction) {
		this.function=newFunction;
	}
	
	public static <T extends ACell> Lambda<T> create(AVector<ACell> params, AOp<T> body) {
		return new Lambda<T>(Fn.create(params,body).getRef());
	}

	public static <T extends ACell> Lambda<T> create(AClosure<T> fn) {
		return new Lambda<T>(fn.getRef());
	}

	@Override
	public Context execute(Context context) {
		AClosure<T> fn= function.getValue().withEnvironment(context.getLocalBindings());
		return context.withResult(Juice.LAMBDA,fn);
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (i==0) return (Ref<R>) function;
		throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
	}

	@Override
	public Lambda<T> updateRefs(IRefFunction func)  {
		@SuppressWarnings("unchecked")
		Ref<AClosure<T>> newFunction=(Ref<AClosure<T>>) func.apply(function);
		if (function==newFunction) return this;
		return new Lambda<T>(newFunction);
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit)  {
		return function.getValue().print(sb,limit);
	}

	@Override
	public byte opCode() {
		return Ops.LAMBDA;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=function.encode(bs, pos);
		return pos;
	}
	
	public static <T extends ACell> Lambda<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		Ref<AClosure<T>> function= Format.readRef(b,epos);
		epos+=function.getEncodingLength();
		
		Lambda<T> result= new Lambda<T>(function);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
	
	@Override 
	public void validate() throws InvalidDataException {
		super.validate();
		
		ACell fn=function.getValue();
		if (!(fn instanceof AClosure)) {
			throw new InvalidDataException("Lambda child must be a closure but got: "+Utils.getClassName(fn),this);
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// nothing to do?
	}

	public AClosure<T> getFunction() {
		return function.getValue();
	}
}
