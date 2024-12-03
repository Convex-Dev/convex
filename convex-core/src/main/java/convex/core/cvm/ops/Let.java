package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.exception.RecurValue;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;

/**
 * Op for executing a body after lexically binding one or more symbols.
 * 
 * Can represent (let [..] ..) and (loop [..] ..).
 * 
 * Loop version can act as a target for (recur ...) expressions.
 *
 * @param <T> Result type of Op
 */
public class Let<T extends ACell> extends ACodedOp<T,AVector<ACell>,AVector<AOp<ACell>>> {

	protected final boolean isLoop;

	protected Let(byte tag,Ref<AVector<ACell>> syms, Ref<AVector<AOp<ACell>>> ops) {
		super(tag,syms,ops);
		this.isLoop = tag==CVMTag.OP_LOOP;
	}

	public static <T extends ACell> Let<T> create(AVector<ACell> syms, AVector<AOp<ACell>> ops, boolean isLoop) {
		return new Let<T>(isLoop?CVMTag.OP_LOOP:CVMTag.OP_LET, syms.getRef(),ops.getRef());
	}

	protected Let<T> rebuild(Ref<AVector<ACell>> newSymbols, Ref<AVector<AOp<ACell>>> newOps) {
		if ((code == newSymbols) && (value == newOps)) return this;
		return new Let<T>(tag,newSymbols,newOps);
	}

	@Override
	public Context execute(final Context context) {
		Context ctx = context.consumeJuice(Juice.LET);
		if (ctx.isExceptional()) return ctx;

		AVector<ACell> symbols=code.getValue();
		int bindingCount=symbols.size();
		AVector<AOp<ACell>> ops=value.getValue();
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
			// other exceptional we can just let slip
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
		AVector<AOp<ACell>> ops=value.getValue();
		int end = ops.size();
		int bindingCount=code.getValue().size(); // number of binding symbols

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
		AVector<AOp<ACell>> ops=value.getValue();
		AVector<ACell> symbols=code.getValue();
		int bindingCount=symbols.size();
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
	
	/**
	 * Read a Let Op from a Blob encoding
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @param isLoop Indicates if the Op should be considered a loop target	 
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Let<T> read(Blob b, int pos, boolean isLoop) throws BadFormatException {
		int epos=pos+1; // skip tag 

		byte tag=isLoop?CVMTag.OP_LOOP:CVMTag.OP_LET;
		
		Ref<AVector<ACell>> syms = Format.readRef(b,epos);
		epos+=syms.getEncodingLength();
		
		Ref<AVector<AOp<ACell>>> ops = Format.readRef(b,epos);
		epos+=ops.getEncodingLength();
		
		Let<T> result= new Let<>(tag,syms,ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
}
