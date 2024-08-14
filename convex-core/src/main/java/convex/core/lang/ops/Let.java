package convex.core.lang.ops;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.RT;
import convex.core.lang.exception.RecurValue;

/**
 * Op for executing a body after lexically binding one or more symbols.
 * 
 * Can represent (let [..] ..) and (loop [..] ..).
 * 
 * Loop version can act as a target for (recur ...) expressions.
 *
 * @param <T> Result type of Op
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

	@Override
	public Context execute(final Context context) {
		Context ctx = context.consumeJuice(Juice.LET);
		if (ctx.isExceptional()) return ctx;

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

	public Context executeBody(Context ctx) {
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
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(isLoop ? "(loop [" : "(let [");
		int len = ops.size();
		for (int i = 0; i < bindingCount; i++) {
			if (i > 0) bb.append(' ');
			if (!RT.print(bb, symbols.get(i),limit)) return false;
			bb.append(' ');
			if (!ops.get(i).print(bb,limit)) return false;
			bb.append(' ');
		}
		bb.append("] ");

		for (int i = bindingCount; i < len; i++) {
			bb.append(' ');
			if (!ops.get(i).print(bb,limit)) return false;
		}
		bb.append(')');
		return bb.check(limit);
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

	/**
	 * Read a Let Op from a Blob encoding
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @param isLoop Indicates if the Op should be considered a loop target	 
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Let<T> read(Blob b, int pos, boolean isLoop) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		AVector<ACell> syms = Format.read(b,epos);
		epos+=Format.getEncodingLength(syms);
		AVector<AOp<ACell>> ops = Format.read(b,epos);
		epos+=Format.getEncodingLength(ops);
		
		Let<T> result= create(syms, ops.toVector(),isLoop);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
}
