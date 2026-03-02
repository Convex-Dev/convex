package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.ByteFlag;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;

/**
 * Op for executing a sequence of child operations in order in query mode (no state changes)
 *
 * "Design is to take things apart in such a way that they can be put back
 * together" 
 * - Rich Hickey
 *
 * @param <T> Result type of Op
 */
public class Query<T extends ACell> extends ACodedOp<T,ACell,AVector<AOp<ACell>>> {

	private static final Ref<ACell> CODE=new ByteFlag(CVMTag.OPCODE_QUERY).getRef();

	protected Query(Ref<ACell> code,Ref<AVector<AOp<ACell>>> ops) {
		super(CVMTag.OP_CODED,code,ops);
	}

	/**
	 * Creates a Query op from decoded refs.
	 * @param <T> Result type
	 * @param code Code ref (opcode ByteFlag)
	 * @param value Value ref (ops vector)
	 * @return Query instance
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Query<T> createFromRefs(Ref<ACell> code, Ref<ACell> value) {
		return new Query<>(code, (Ref<AVector<AOp<ACell>>>)(Ref<?>)value);
	}

	protected Query(Ref<AVector<AOp<ACell>>> ops) {
		this(CODE,ops);
	}

	public static <T extends ACell> Query<T> create(AOp<?>... ops) {
		return new Query<T>(Vectors.create(ops).getRef());
	}

	@Override
	protected Query<T> rebuild(Ref<ACell> newCode,Ref<AVector<AOp<ACell>>> newOps) {
		if ((code == newCode)&&(value==newOps)) return this;
		return new Query<T>(newCode,newOps);
	}

	public static <T extends ACell> Query<T> create(ASequence<AOp<ACell>> ops) {
		return new Query<T>(ops.toVector().getRef());
	}

	@Override
	public Context execute(Context context) {
		State savedState=context.getState();
		AVector<AOp<ACell>> ops=value.getValue();
		
		int n = ops.size();
		if (n == 0) return context.withResult(Juice.QUERY,  null); // need cast to avoid bindings overload

		Context ctx = context.consumeJuice(Juice.QUERY);
		if (ctx.isExceptional()) return ctx;
		
		AVector<ACell> savedBindings=context.getLocalBindings();

		// execute each operation in turn
		for (int i = 0; i < n; i++) {
			AOp<?> op = Ops.castOp(ops.get(i));
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
		AVector<AOp<ACell>> ops=value.getValue();
		bb.append("(query");
		int len = ops.size();
		for (int i = 0; i < len; i++) {
			bb.append(' ');
			if (!RT.print(bb,ops.get(i),limit)) return false;
		}
		bb.append(')');
		return bb.check(limit);
	}

}
