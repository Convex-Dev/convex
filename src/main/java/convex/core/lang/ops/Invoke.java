package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AFn;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.IFn;
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
public class Invoke<T> extends AMultiOp<T> {

	protected Invoke(AVector<AOp<?>> ops) {
		super(ops);
	}

	public static <T> Invoke<T> create(ASequence<AOp<?>> ops) {
		AVector<AOp<?>> vops = ops.toVector();
		return new Invoke<T>(vops);
	}

	public static <T> Invoke<T> create(AOp<?>... ops) {
		return create(Vectors.create(ops));
	}

	@SuppressWarnings("unchecked")
	public static <T, A extends AOp<?>, F extends AOp<AFn<?>>> Invoke<T> create(F f, ASequence<A> args) {
		ASequence<AOp<?>> nargs = (ASequence<AOp<?>>) args;
		ASequence<AOp<?>> ops = nargs.cons(f);

		return create(ops);
	}

	@Override
	protected Invoke<T> recreate(ASequence<AOp<?>> newOps) {
		if (ops == newOps) return this;
		return create(newOps);
	}

	@Override
	public AOp<T> specialise(AMap<Symbol, Object> binds) {
		AVector<AOp<?>> newOps = ops.map(op -> {
			return op.specialise(binds);
		});
		if (ops == newOps) return this;
		return recreate(newOps);
	}

	public static <T> Invoke<T> create(String string, ASequence<AOp<?>> args) {
		return create(Lookup.create(string), args);
	}

	public static <T> Invoke<T> create(String string, AOp<?>... args) {
		return create(Lookup.create(string), Vectors.create(args));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I> Context<T> execute(Context<I> context) {
		// execute first op to obtain function value
		AOp<?> fnOp=ops.get(0);
		Context<T> ctx = (Context<T>) context.execute(fnOp);
		if (ctx.isExceptional()) return ctx;

		Object rf = ctx.getResult();
		IFn<T> fn = RT.function(rf);
		if (fn == null) return context.withCastError(rf, IFn.class);

		int arity = ops.size() - 1;
		Object[] args = new Object[arity];
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
	public void ednString(StringBuilder sb) {
		sb.append('(');
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			if (i > 0) sb.append(' ');
			ops.get(i).ednString(sb);
		}
		sb.append(')');
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

	public static <T> Invoke<T> read(ByteBuffer bb) throws BadFormatException {
		AVector<AOp<?>> ops = Format.read(bb);
		if (ops == null) throw new BadFormatException("Can't read an Invoke with no ops");

		return create(ops);
	}
}
