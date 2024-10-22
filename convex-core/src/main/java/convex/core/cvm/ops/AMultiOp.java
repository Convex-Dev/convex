package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.data.AVector;
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

	protected AMultiOp(AVector<AOp<ACell>> ops) {
		// TODO: need to think about bounds on number of child ops?
		this.ops = ops;
	}

	/**
	 * Recreates this object with an updated list of child Ops.
	 * 
	 * @param newOps
	 * @return
	 */
	protected abstract AMultiOp<T> recreate(ASequence<AOp<ACell>> newOps);

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, ops);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 10+ops.estimatedEncodingSize();
	}

	@Override
	public AMultiOp<T> updateRefs(IRefFunction func) {
		ASequence<AOp<ACell>> newOps = ops.updateRefs(func);
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
	public void validateCell() throws InvalidDataException {
		ops.validateCell();
	}
}
