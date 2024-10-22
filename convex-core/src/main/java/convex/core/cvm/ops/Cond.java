package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Vectors;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
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

	@Override
	public Context execute(Context context) {
		int n=ops.size();
		Context ctx=context.consumeJuice(Juice.COND_OP);
		if (ctx.isExceptional()) return (Context) ctx;
		
		for (int i=0; i<(n-1); i+=2) {
			AOp<ACell> testOp=ops.get(i);
			ctx=ctx.execute(testOp);
			
			// bail out from exceptional result in test
			if (ctx.isExceptional()) return (Context) ctx;
			
			ACell test=ctx.getResult();
			if (RT.bool(test)) {
				return ctx.execute(ops.get(i+1));
			}
		}
		if ((n&1)==0) {
			// no default value, return null
			return ctx.withResult((T)null);
		} else {
			// default value
			return ctx.execute(ops.get(n-1));
		}
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit)  {
		sb.append("(cond");
		int len=ops.size();
		for (int i=0; i<len; i++) {
			sb.append(' ');
			if (!ops.get(i).print(sb,limit)) return false;
		}
		sb.append(')');
		return sb.check(limit);
	}

	@Override
	public byte opCode() {
		return Ops.COND;
	}


	/**
	 * Decodes a Cond op from a Blob encoding.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Cond<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		AVector<AOp<ACell>> ops = Format.read(b,epos);
		epos+=Format.getEncodingLength(ops);
		
		Cond<T> result=create(ops);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	public Cond<T> updateRefs(IRefFunction func)  {
		ASequence<AOp<ACell>> newOps= ops.updateRefs(func);
		return recreate(newOps);
	}


}
