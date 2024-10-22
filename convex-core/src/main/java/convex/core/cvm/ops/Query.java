package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Vectors;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;

/**
 * Op for executing a sequence of child operations in order in query mode (no state changes)
 *
 * "Design is to take things apart in such a way that they can be put back
 * together" 
 * - Rich Hickey
 *
 * @param <T> Result type of Op
 */
public class Query<T extends ACell> extends AMultiOp<T> {

	protected Query(AVector<AOp<ACell>> ops) {
		super(ops);
	}

	public static <T extends ACell> Query<T> create(AOp<?>... ops) {
		return new Query<T>(Vectors.create(ops));
	}

	@Override
	protected Query<T> recreate(ASequence<AOp<ACell>> newOps) {
		if (ops == newOps) return this;
		return new Query<T>(newOps.toVector());
	}

	public static <T extends ACell> Query<T> create(ASequence<AOp<ACell>> ops) {
		return new Query<T>(ops.toVector());
	}

	@Override
	public Context execute(Context context) {
		State savedState=context.getState();
		
		int n = ops.size();
		if (n == 0) return context.withResult(Juice.QUERY,  null); // need cast to avoid bindings overload

		Context ctx = context.consumeJuice(Juice.QUERY);
		if (ctx.isExceptional()) return ctx;
		
		AVector<ACell> savedBindings=context.getLocalBindings();

		// execute each operation in turn
		// TODO: early return
		for (int i = 0; i < n; i++) {
			AOp<?> op = ops.get(i);
			ctx = ctx.execute(op);
			if (ctx.isExceptional()) break;

		}
		// restore state unconditionally.
		ctx=ctx.withState(savedState);
		ctx=ctx.withLocalBindings(savedBindings);
		return ctx;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append("(query");
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
		return Ops.QUERY;
	}

	/**
	 * Read a Query Op from a Blob encoding
	 * @param <T> Type of Query result
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Query<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		AVector<AOp<ACell>> ops = Format.read(b,epos);
		epos+=Format.getEncodingLength(ops);
		
		Query<T> result= create(ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
}
