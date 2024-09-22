package convex.core.lang.ops;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.exception.AExceptional;

/**
 * Op for executing a sequence of child operations until one succeeds
 *
 * @param <T> Result type of Try Op
 */
public class Try<T extends ACell> extends AMultiOp<T> {

	public static final Try<?> EMPTY = Try.create();

	protected Try(AVector<AOp<ACell>> ops) {
		super(ops);
	}

	public static <T extends ACell> Try<T> create(AOp<?>... ops) {
		return new Try<T>(Vectors.create(ops));
	}

	@Override
	protected Try<T> recreate(ASequence<AOp<ACell>> newOps) {
		if (ops == newOps) return this;
		return new Try<T>(newOps.toVector());
	}

	public static <T extends ACell> Try<T> create(ASequence<AOp<ACell>> ops) {
		return new Try<T>(ops.toVector());
	}

	@Override
	public Context execute(Context context) {
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
		bb.append("(try");
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			bb.append(' ');
			if (!ops.get(i).print(bb,limit)) return false;
		}
		bb.append(')');
		return bb.check(limit);
	}

	@Override
	public byte opCode() {
		return Ops.DO;
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

		AVector<AOp<ACell>> ops = Format.read(b,epos);
		epos+=Format.getEncodingLength(ops);
		
		Try<T> result=create(ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
}
