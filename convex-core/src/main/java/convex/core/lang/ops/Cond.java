package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.RT;

/**
 * Op representing a conditional expression.
 * 
 * Child ops: 
 * 1. Should be condition / result pairs (with an optional single default result).
 * 2. Are executed in sequence until the first condition succeeds
 * 3. Are only executed if required, i.e. cond operates as a "short-circuiting" conditional.
 *
 * @param <T> Result type of Op
 */
public class Cond<T extends ACell> extends AMultiOp<T> {

	protected Cond(AVector<AOp<ACell>> ops) {
		super(ops);
	}

	/**
	 * Create a Cond operation with the given nested operations
	 * 
	 * @param <T> Return type of Cond
	 * @param ops Ops to execute conditionally
	 * @return Cond instance
	 */
	public static <T extends ACell> Cond<T> create(AOp<?>... ops) {
		ASequence<AOp<ACell>> refOps=Vectors.create(ops);
		return create(refOps);
	}
	
	@Override
	protected Cond<T> recreate(ASequence<AOp<ACell>> newOps) {
		if (ops==newOps) return this;
		return new Cond<T>(newOps.toVector());
	}
	
	public static <T extends ACell> Cond<T> create(ASequence<AOp<ACell>> ops) {
		return new Cond<T>(ops.toVector());
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I extends ACell> Context<T> execute(Context<I> context) {
		int n=ops.size();
		Context<?> ctx=context.consumeJuice(Juice.COND_OP);
		if (ctx.isExceptional()) return (Context<T>) ctx;
		
		for (int i=0; i<(n-1); i+=2) {
			AOp<ACell> testOp=ops.get(i);
			ctx=ctx.execute(testOp);
			
			// bail out from exceptional result in test
			if (ctx.isExceptional()) return (Context<T>) ctx;
			
			ACell test=ctx.getResult();
			if (RT.bool(test)) {
				return (Context<T>) ctx.execute(ops.get(i+1));
			}
		}
		if ((n&1)==0) {
			// no default value, return null
			return ctx.withResult((T)null);
		} else {
			// default value
			return (Context<T>) ctx.execute(ops.get(n-1));
		}
	}
	
	@Override
	public void print(StringBuilder sb)  {
		sb.append("(cond");
		int len=ops.size();
		for (int i=0; i<len; i++) {
			sb.append(' ');
			ops.get(i).print(sb);
		}
		sb.append(')');
	}

	@Override
	public byte opCode() {
		return Ops.COND;
	}

	public static <T extends ACell> Cond<T> read(ByteBuffer b) throws BadFormatException {
		AVector<AOp<ACell>> ops=Format.read(b);
		return create(ops);
	}

	@Override
	public Cond<T> updateRefs(IRefFunction func)  {
		ASequence<AOp<ACell>> newOps= ops.updateRefs(func);
		return recreate(newOps);
	}


}
