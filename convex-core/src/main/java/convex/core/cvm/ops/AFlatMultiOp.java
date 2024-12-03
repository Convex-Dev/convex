package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.util.ErrorMessages;

/**
 * Abstract base class for ops encoded as dense records of ops
 * @param <T>
 */
public abstract class AFlatMultiOp<T extends ACell> extends AMultiOp<T> {

	protected AFlatMultiOp(byte tag, AVector<AOp<ACell>> ops) {
		super(tag,ops);
	}

	@Override
	public byte getTag() {
		return tag;
	}
	
	@Override	
	public int encodeRaw(byte[] bs, int pos) {
		int epos=pos;
		epos=ops.encodeRaw(bs, epos);
		return epos;
	}
	
	@Override
	public int encodeAfterOpcode(byte[] bs, int pos) {
		throw new Error(ErrorMessages.UNREACHABLE);
	}
	
	@Override
	public final int getRefCount() {
		return ops.getRefCount();
	}
	

	@SuppressWarnings("unchecked")
	@Override
	public Ref<?> getRef(int i) {
		return ops.getRef(i);
	}

	@Override
	public final AFlatMultiOp<T> updateRefs(IRefFunction func) {
		AVector<AOp<ACell>> newOps=(AVector<AOp<ACell>>) ops.updateRefs(func);
		return recreate(newOps);
	}

	protected abstract AFlatMultiOp<T> recreate(AVector<AOp<ACell>> newOps);
}
