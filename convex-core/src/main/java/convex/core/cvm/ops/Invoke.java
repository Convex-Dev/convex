package convex.core.cvm.ops;

import convex.core.cvm.AFn;
import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Vectors;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;

/**
 * Op representing the invocation of a function.
 * 
 * The first child Op identifies the function to be called, the remaining ops
 * are arguments.
 *
 * @param <T> Result type of Op
 */
public class Invoke<T extends ACell> extends AFlatMultiOp<T> {

	protected Invoke(AVector<AOp<ACell>> ops) {
		super(CVMTag.OP_INVOKE,ops);
	}

	public static <T extends ACell> Invoke<T> create(ASequence<AOp<ACell>> ops) {
		AVector<AOp<ACell>> vops = ops.toVector();
		return new Invoke<T>(vops);
	}

	public static <T extends ACell> Invoke<T> create(AOp<?>... ops) {
		return create(Vectors.create(ops));
	}
	
	/**
	 *  Build an invoke using the given values. Slow, for testing purposes
	 * @param <T>
	 * @param vals
	 * @return
	 */
	public static <T extends ACell> Invoke<T> build(Object... vals) {
		int n=vals.length;
		AOp<?>[] ops=new AOp[n];
		for (int i=0; i<n; i++) {
			ACell v=RT.cvm(vals[i]);
			ops[i]=(v instanceof AOp)?(AOp<?>) v:Constant.of(v);
		}
		return create(ops);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell, A extends AOp<ACell>, F extends AOp<ACell>> Invoke<T> create(F f, ASequence<A> args) {
		ASequence<AOp<ACell>> nargs = (ASequence<AOp<ACell>>) args;
		ASequence<AOp<ACell>> ops = nargs.cons(f);

		return create(ops);
	}

	@Override
	protected Invoke<T> recreate(AVector<AOp<ACell>> newOps) {
		if (ops == newOps) return this;
		return create(newOps);
	}

	public static <T extends ACell> Invoke<T> create(String string, AOp<?>... args) {
		return create(Lookup.create(string), Vectors.create(args));
	}

	@Override
	public Context execute(Context context) {
		// execute first op to obtain function value
		AOp<?> fnOp=ops.get(0);
		Context ctx = context.execute(fnOp);
		if (ctx.isExceptional()) return ctx;

		ACell rf = ctx.getResult();
		AFn<T> fn = RT.castFunction(rf);
		if (fn == null) {
			Context rctx=context.withCastError(rf, Types.FUNCTION);
			ctx.getError().addTrace("Trying to get function for expression: "+RT.print(this));
			return rctx;
		}

		int arity = ops.size() - 1;
		ACell[] args = new ACell[arity];
		for (int i = 0; i < arity; i++) {
			// Compute the op for each argument in order
			AOp<?> argOp=ops.get(i + 1);
			ctx = ctx.execute(argOp);
			if (ctx.isExceptional()) return ctx;

			args[i] = ctx.getResult();
		}

		ctx = ctx.invoke(fn, args);
		// Specific check for an error so we can add stack trace info
		if (ctx.isError()) {
			// getError()must be non-null at this point
			try {
			   ctx.getError().addTrace("In expression: "+RT.print(this));
			} catch (Exception e) {
			   ctx.getError().addTrace("TRACE FAILED");
			}
		}

		return ctx;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append('(');
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			if (i > 0) bb.append(' ');
			if (!ops.get(i).print(bb,limit)) return false;
		}
		bb.append(')');
		return bb.check(limit);
	}

	/**
	 * Read an Invoke Op from a Blob encoding
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static<T extends ACell> Invoke<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos;
		AVector<AOp<ACell>> ops = Vectors.read(b,epos);
		epos+=Cells.getEncodingLength(ops);
		
		Invoke<T> result=create(ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

}
