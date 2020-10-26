package convex.core.lang.ops;

import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;

/**
 * Abstract base class for Ops with multiple nested operations
 * 
 * MultiOps may selectively evaluate sub-expressions.
 *
 */
public abstract class AMultiOp<T> extends AOp<T> {
	protected final AVector<AOp<?>> ops;

	protected AMultiOp(AVector<AOp<?>> ops) {
		// TODO: need to think about bounds on number of child ops?
		this.ops = ops;
	}

	/**
	 * Recreates this object with an updated list of child Ops.
	 * 
	 * @param newOps
	 * @return
	 */
	protected abstract AMultiOp<T> recreate(ASequence<AOp<?>> newOps);

	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, ops);
		return pos;
	}

	@Override
	public AMultiOp<T> updateRefs(IRefFunction func) {
		ASequence<AOp<?>> newOps = ops.updateRefs(func);
		return recreate(newOps);
	}

	@Override
	public int getRefCount() {
		return ops.getRefCount();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		return (Ref<R>) ops.getRef(i);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		ops.validateCell();
	}
}
