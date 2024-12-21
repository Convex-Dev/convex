package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract base class for Ops with multiple nested operations
 * 
 * MultiOps may selectively evaluate sub-expressions.
 * 
 * @param <T> Type of function return
 */
public abstract class AMultiOp<T extends ACell> extends AOp<T> {
	protected final AVector<AOp<ACell>> ops;
	protected final byte tag;
	
	protected AMultiOp(byte tag,AVector<AOp<ACell>> ops) {
		this.tag=tag;
		this.ops = ops;
	}

	/**
	 * Recreates this object with an updated list of child Ops.
	 * 
	 * @param newOps
	 * @return
	 */
	protected abstract AMultiOp<T> recreate(AVector<AOp<ACell>> newOps);

	@Override
	public int encodeAfterOpcode(byte[] bs, int pos) {
		pos = Format.write(bs,pos, ops);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 10+ops.estimatedEncodingSize();
	}

	@Override
	public AMultiOp<T> updateRefs(IRefFunction func) {
		AVector<AOp<ACell>> newOps = ops.updateRefs(func);
		return recreate(newOps);
	}

	@Override
	public int getRefCount() {
		return ops.getRefCount();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return (Ref<R>) ops.getRef(i);
	}
	
	@Override
	public void validateStructure() throws InvalidDataException {
		super.validateStructure();
		long n=ops.count();
		for (long i=0; i<n; i++) {
			AOp<ACell> op =Ops.ensureOp(ops.get(i));
			if (op==null) {
				throw new InvalidDataException("Not an op in position "+i, this);
			}
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		Cells.validateCell(ops);
	}
}
