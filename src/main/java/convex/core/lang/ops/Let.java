package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
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
	protected final AVector<Syntax> symbols;

	protected final int bindingCount;
	protected final boolean isLoop;

	protected Let(AVector<Syntax> syms, AVector<AOp<ACell>> ops, boolean isLoop) {
		super(ops);
		symbols = syms;
		bindingCount = syms.size();
		this.isLoop = isLoop;
	}

	public static <T extends ACell> Let<T> create(AVector<Syntax> syms, AVector<AOp<ACell>> ops, boolean isLoop) {
		return new Let<T>(syms, ops, isLoop);
	}

	public static <T extends ACell> Let<T> createLet(AVector<Syntax> syms, AVector<AOp<ACell>> ops) {
		return new Let<T>(syms, ops, false);
	}

	public static <T extends ACell> Let<T> createLoop(AVector<Syntax> syms, AVector<AOp<ACell>> ops) {
		return new Let<T>(syms, ops, true);
	}

	@Override
	public Let<T> updateRefs(IRefFunction func) {
		ASequence<AOp<ACell>> newOps = ops.updateRefs(func);
		AVector<Syntax> newSymbols = symbols.updateRefs(func);

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

	protected Let<T> recreate(ASequence<AOp<ACell>> newOps, AVector<Syntax> newSymbols) {
		if ((ops == newOps) && (symbols == newSymbols)) return this;
		return new Let<T>(newSymbols, newOps.toVector(), isLoop);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I extends ACell> Context<T> execute(final Context<I> context) {
		Context<?> ctx = context.consumeJuice(Juice.LET);
		if (ctx.isExceptional()) return (Context<T>) ctx;

		AHashMap<Symbol, ACell> savedEnv = ctx.getLocalBindings();
		// execute each operation in turn
		// TODO: early return
		for (int i = 0; i < bindingCount; i++) {
			AOp<?> op = ops.get(i);
			ctx = ctx.executeLocalBinding(symbols.get(i), op);
			if (ctx.isExceptional()) {
				// return if exception during initial binding. No chance to recur.
				return ctx.withLocalBindings(savedEnv);
			}
		}

		ctx = executeBody(ctx);
		if (isLoop) {
			// check for recur if this Let form is a loop
			// other exceptionals we can just let slip
			Object o = ctx.getValue();
			while (o instanceof RecurValue) {
				RecurValue rv = (RecurValue) o;
				Object[] newArgs = rv.getValues();
				if (newArgs.length != bindingCount) {
					// recur arity is wrong, need to break loop with exceptional result
					ctx = ctx
							.withArityError("Expected " + bindingCount + " vales for recur but got: " + newArgs.length);
					break;
				}

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
	public void ednString(StringBuilder sb) {
		sb.append(isLoop ? "(loop [" : "(let [");
		int len = ops.size();
		for (int i = 0; i < bindingCount; i++) {
			if (i > 0) sb.append(' ');
			Utils.ednString(sb, symbols.get(i));
			sb.append(' ');
			ops.get(i).ednString(sb);
			sb.append(' ');
		}
		sb.append("] ");

		for (int i = bindingCount; i < len; i++) {
			sb.append(' ');
			ops.get(i).ednString(sb);
		}
		sb.append(')');
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
		return Ops.LET;
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

	public static <T extends ACell> Let<T> read(ByteBuffer b) throws BadFormatException {
		AVector<Syntax> syms = Format.read(b);
		AVector<AOp<?>> ops = Format.read(b);
		return createLet(syms, ops.toVector());
	}
}
