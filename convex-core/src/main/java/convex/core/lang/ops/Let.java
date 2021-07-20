package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.impl.RecurValue;
import convex.core.util.Utils;

/**
 * Op for executing a body after lexically binding one or more symbols.
 * 
 * Can represent (let [..] ..) and (loop [..] ..).
 * 
 * Loop version can act as a target for (recur ...) expressions.
 *
 * @param <T>
 */
public class Let<T extends ACell> extends AMultiOp<T> {

	/**
	 * Vector of binding forms. Can be destructuring forms
	 */
	protected final AVector<ACell> symbols;

	protected final int bindingCount;
	protected final boolean isLoop;

	protected Let(AVector<ACell> syms, AVector<AOp<ACell>> ops, boolean isLoop) {
		super(ops);
		symbols = syms;
		bindingCount = syms.size();
		this.isLoop = isLoop;
	}

	public static <T extends ACell> Let<T> create(AVector<ACell> syms, AVector<AOp<ACell>> ops, boolean isLoop) {
		return new Let<T>(syms, ops, isLoop);
	}

	@Override
	public Let<T> updateRefs(IRefFunction func) {
		ASequence<AOp<ACell>> newOps = ops.updateRefs(func);
		AVector<ACell> newSymbols = symbols.updateRefs(func);

		return recreate(newOps, newSymbols);
	}

	@Override
	public int getRefCount() {
		return super.getRefCount() + symbols.getRefCount();
	}

	@Override
	public final <R extends ACell> Ref<R> getRef(int i) {
		int n = super.getRefCount();
		if (i < n) return super.getRef(i);
		return symbols.getRef(i - n);
	}

	@Override
	protected Let<T> recreate(ASequence<AOp<ACell>> newOps) {
		return recreate(newOps, symbols);
	}

	protected Let<T> recreate(ASequence<AOp<ACell>> newOps, AVector<ACell> newSymbols) {
		if ((ops == newOps) && (symbols == newSymbols)) return this;
		return new Let<T>(newSymbols, newOps.toVector(), isLoop);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I extends ACell> Context<T> execute(final Context<I> context) {
		Context<?> ctx = context.consumeJuice(Juice.LET);
		if (ctx.isExceptional()) return (Context<T>) ctx;

		AVector<ACell> savedEnv = ctx.getLocalBindings();
		
		// execute each operation for bound values in turn
		for (int i = 0; i < bindingCount; i++) {
			AOp<?> op = ops.get(i);
			ctx = ctx.executeLocalBinding(symbols.get(i), op);
			if (ctx.isExceptional()) {
				// return if exception during initial binding. 
				// No chance to recur since we didn't enter loop body
				return ctx.withLocalBindings(savedEnv);
			}
		}

		ctx = executeBody(ctx);
		if (isLoop&&ctx.isExceptional()) {
			// check for recur if this Let form is a loop
			// other exceptionals we can just let slip
			Object o = ctx.getExceptional();
			while (o instanceof RecurValue) {
				RecurValue rv = (RecurValue) o;
				ACell[] newArgs = rv.getValues();
				if (newArgs.length != bindingCount) {
					// recur arity is wrong, need to break loop with exceptional result
					String message="Expected " + bindingCount + " value(s) for recur but got: " + newArgs.length;
					ctx = ctx.withArityError(message);
					break;
				}

				// restore old lexical environment, then add back new ones
				ctx=ctx.withLocalBindings(savedEnv);
				ctx = ctx.updateBindings(symbols, newArgs);
				if (ctx.isExceptional()) break;

				ctx = executeBody(ctx);
				o = ctx.getValue();
			}
		}
		// restore old lexical environment before returning
		return ctx.withLocalBindings(savedEnv);
	}

	public Context<?> executeBody(Context<?> ctx) {
		int end = ops.size();
		if (bindingCount == end) return ctx.withResult(null);
		for (int i = bindingCount; i < end; i++) {
			ctx = ctx.execute(ops.get(i));
			if (ctx.isExceptional()) {
				return ctx;
			}
		}
		return ctx;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append(isLoop ? "(loop [" : "(let [");
		int len = ops.size();
		for (int i = 0; i < bindingCount; i++) {
			if (i > 0) sb.append(' ');
			Utils.print(sb, symbols.get(i));
			sb.append(' ');
			ops.get(i).print(sb);
			sb.append(' ');
		}
		sb.append("] ");

		for (int i = bindingCount; i < len; i++) {
			sb.append(' ');
			ops.get(i).print(sb);
		}
		sb.append(')');
	}

	@Override
	public byte opCode() {
		return (isLoop)?Ops.LOOP:Ops.LET;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, symbols);
		return super.encodeRaw(bs,pos); // AMultiOp superclass writeRaw
	}
	
	@Override 
	public int estimatedEncodingSize() {
		return super.estimatedEncodingSize()+symbols.estimatedEncodingSize();
	}

	public static <T extends ACell> Let<T> read(ByteBuffer b, boolean isLoop) throws BadFormatException {
		AVector<ACell> syms = Format.read(b);
		AVector<AOp<?>> ops = Format.read(b);
		return create(syms, ops.toVector(),isLoop);
	}
}
