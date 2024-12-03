package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.prim.ByteFlag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.Fn;
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
public class Lambda<T extends ACell> extends ACodedOp<T,ACell,AClosure<T>> {
	private static final Ref<ACell> OPCODE = new ByteFlag(CVMTag.OPCODE_LAMBDA).getRef();
	
	protected Lambda(Ref<AClosure<T>> newFunction) {
		super(CVMTag.OP_CODED,OPCODE,newFunction);
	}
	
	public static <T extends ACell> Lambda<T> create(AVector<ACell> params, AOp<T> body) {
		return new Lambda<T>(Fn.create(params,body).getRef());
	}

	public static <T extends ACell> Lambda<T> create(AClosure<T> fn) {
		return new Lambda<T>(fn.getRef());
	}

	@Override
	public Context execute(Context context) {
		AClosure<T> fn= value.getValue().withEnvironment(context.getLocalBindings());
		return context.withResult(Juice.LAMBDA,fn);
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit)  {
		return value.getValue().print(sb,limit);
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
		
		ACell fn=value.getValue();
		if (!(fn instanceof AClosure)) {
			throw new InvalidDataException("Lambda child must be a closure but got: "+Utils.getClassName(fn),this);
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// nothing to do?
	}

	public AClosure<T> getFunction() {
		return value.getValue();
	}

	@Override
	protected AOp<T> rebuild(Ref<ACell> newCode, Ref<AClosure<T>> newValue) {
		if (newValue==value) return this;
		return new Lambda<T>(newValue);
	}
}
