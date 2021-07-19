package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
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
 * @param <T>
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

	@SuppressWarnings("unchecked")
	@Override
	public <I extends ACell> Context<T> execute(Context<I> context) {
		// execute first op to obtain function value
		AOp<?> fnOp=ops.get(0);
		Context<T> ctx = (Context<T>) context.execute(fnOp);
		if (ctx.isExceptional()) return ctx;

		ACell rf = ctx.getResult();
		AFn<T> fn = RT.castFunction(rf);
		if (fn == null) return context.withCastError(0, Types.FUNCTION);

		int arity = ops.size() - 1;
		ACell[] args = new ACell[arity];
		for (int i = 0; i < arity; i++) {
			// Compute the op for each argument in order
			AOp<?> argOp=ops.get(i + 1);
			ctx = (Context<T>) ctx.execute(argOp);
			if (ctx.isExceptional()) return ctx;

			args[i] = ctx.getResult();
		}

		ctx = ctx.invoke(fn, args);
		return (Context<T>) ctx;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append('(');
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			if (i > 0) sb.append(' ');
			ops.get(i).print(sb);
		}
		sb.append(')');
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
}
