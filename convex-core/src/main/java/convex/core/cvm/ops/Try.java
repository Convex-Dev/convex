package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.cvm.exception.AExceptional;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.ByteFlag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;

/**
 * Op for executing a sequence of child operations until one succeeds
 *
 * @param <T> Result type of Try Op
 */
public class Try<T extends ACell> extends ACodedOp<T,ACell,AVector<AOp<ACell>>> {

	private static final Ref<ACell> CODE=new ByteFlag(CVMTag.OPCODE_TRY).getRef();
	
	public static final Try<?> EMPTY = Try.create();

	protected Try(Ref<ACell> code,Ref<AVector<AOp<ACell>>> ops) {
		super(CVMTag.OP_CODED,code,ops);
	}
	
	protected Try(Ref<AVector<AOp<ACell>>> ops) {
		this(CODE,ops);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> Try<T> create(AOp<?>... ops) {
		if (ops.length==0) return (Try<T>) EMPTY;
		return new Try<T>(Vectors.create(ops).getRef());
	}

	@Override
	protected Try<T> rebuild(Ref<ACell> newCode,Ref<AVector<AOp<ACell>>> newOps) {
		if ((code == newCode)&&(value==newOps)) return this;
		return new Try<T>(newCode,newOps);
	}

	public static <T extends ACell> Try<T> create(ASequence<AOp<ACell>> ops) {
		return new Try<T>(ops.toVector().getRef());
	}

	@Override
	public Context execute(Context context) {
		AVector<AOp<ACell>> ops=value.getValue();
		int n = ops.size();
		if (n == 0) return context.withResult(Juice.TRY,  null);

		Context ctx = context;
		
		// execute each operation in turn
		for (int i = 0; i < n; i++) {
			ctx = context.consumeJuice(Juice.TRY);
			if (ctx.isExceptional()) return ctx;
			
			AOp<?> op = ops.get(i);
			Context fctx=ctx.fork();
			fctx=fctx.execute(op);
			if (fctx.isExceptional()) {
				AExceptional ex=fctx.getExceptional();
				if (!ex.isCatchable()) return fctx;
				
				// exit if at last expressions
				if (i+1>=n) return fctx;
				
				ctx=ctx.withResult(ex.getCode());
				ctx=ctx.withJuice(fctx.getJuiceUsed());
				continue;
			} else {
				return fctx;
			}
		}
		return ctx;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		AVector<AOp<ACell>> ops=value.getValue();
		bb.append("(try");
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			bb.append(' ');
			if (!ops.get(i).print(bb,limit)) return false;
		}
		bb.append(')');
		return bb.check(limit);
	}

	/**
	 * Decodes a Do op from a Blob encoding
	 * 
	 * @param <T> Return type of Do
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Try<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		Ref<AVector<AOp<ACell>>> ops = Format.readRef(b,epos);
		epos+=ops.getEncodingLength();
		
		Try<T> result=new Try<>(CODE,ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
}
