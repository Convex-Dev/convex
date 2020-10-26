package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;

/**
 * Op for executing a sequence of child operations in order
 *
 * "Design is to take things apart in such a way that they can be put back
 * together" 
 * - Rich Hickey
 *
 * @param <T>
 */
public class Do<T> extends AMultiOp<T> {

	protected Do(AVector<AOp<?>> ops) {
		super(ops);
	}

	public static <T> Do<T> create(AOp<?>... ops) {
		return new Do<T>(Vectors.create(ops));
	}

	@Override
	protected Do<T> recreate(ASequence<AOp<?>> newOps) {
		if (ops == newOps) return this;
		return new Do<T>(newOps.toVector());
	}

	public static <T> Do<T> create(ASequence<AOp<?>> ops) {
		return new Do<T>(ops.toVector());
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I> Context<T> execute(Context<I> context) {
		int n = ops.size();
		if (n == 0) return context.withResult(Juice.DO, (T) null); // need cast to avoid bindings overload

		Context<T> ctx = (Context<T>) context.consumeJuice(Juice.DO);
		// execute each operation in turn
		// TODO: early return
		for (int i = 0; i < n; i++) {
			AOp<?> op = ops.get(i);
			ctx = (Context<T>) ctx.execute(op);

			if (ctx.isExceptional()) break;

		}
		return ctx;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("(do");
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			sb.append(' ');
			ops.get(i).ednString(sb);
		}
		sb.append(')');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("(do");
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			sb.append(' ');
			ops.get(i).print(sb);
		}
		sb.append(')');
	}

	@Override
	public byte opCode() {
		return Ops.DO;
	}

	public static <T> Do<T> read(ByteBuffer b) throws BadFormatException {
		AVector<AOp<?>> ops = Format.read(b);
		return create(ops);
	}
}
