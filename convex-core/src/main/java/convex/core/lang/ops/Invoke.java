package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AFn;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Ops;
import convex.core.lang.RT;

/**
 * Op representing the invocation of a function.
 * 
 * The first child Op identifies the function to be called, the remaining ops
 * are arguments.
 *
 * @param <T> Result type of Op
 */
public class Invoke<T extends ACell> extends AMultiOp<T> {

	protected Invoke(AVector<AOp<ACell>> ops) {
		super(ops);
	}

	public static <T extends ACell> Invoke<T> create(ASequence<AOp<ACell>> ops) {
		AVector<AOp<ACell>> vops = ops.toVector();
		return new Invoke<T>(vops);
	}

	public static <T extends ACell> Invoke<T> create(AOp<?>... ops) {
		return create(Vectors.create(ops));
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell, A extends AOp<ACell>, F extends AOp<ACell>> Invoke<T> create(F f, ASequence<A> args) {
		ASequence<AOp<ACell>> nargs = (ASequence<AOp<ACell>>) args;
		ASequence<AOp<ACell>> ops = nargs.cons(f);

		return create(ops);
	}

	@Override
	protected Invoke<T> recreate(ASequence<AOp<ACell>> newOps) {
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
		if (fn == null) return context.withCastError(0, Types.FUNCTION);

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
		if (ctx.isError()) {
			// getError()must be non-null at this point
			ctx.getError().addTrace("In expression: "+RT.print(this));
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

	@Override
	public byte opCode() {
		return Ops.INVOKE;
	}

	public static <T extends ACell> Invoke<T> read(ByteBuffer bb) throws BadFormatException {
		AVector<AOp<ACell>> ops = Format.read(bb);
		if (ops == null) throw new BadFormatException("Can't read an Invoke with no ops");

		return create(ops);
	}
	
	public static<T extends ACell> Invoke<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+2; // skip tag and opcode
		AVector<AOp<ACell>> ops = Format.read(b,epos);
		epos+=Format.getEncodingLength(ops);
		
		Invoke<T> result=create(ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

}
