package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.ErrorMessages;

public abstract class ACodedOp<T extends ACell, C extends ACell, V extends ACell> extends AOp<T> {

	@Override
	public abstract Context execute(Context context);

	protected final byte tag;
	protected final Ref<C> code;
	protected final Ref<V> value;
	
	protected ACodedOp(byte tag, Ref<C> code, Ref<V> value) {
		this.tag=tag;
		this.code=code;
		this.value=value;
	}
	
	@Override
	public byte getTag() {
		return tag;
	}
	
	@Override	
	public int encodeRaw(byte[] bs, int pos) {
		int epos=pos;
		epos=code.encode(bs, epos);
		epos=value.encode(bs, epos);
		return epos;
	}
	
	@Override
	public int encodeAfterOpcode(byte[] bs, int pos) {
		throw new Error(ErrorMessages.UNREACHABLE);
	}
	
	@Override
	public final int getRefCount() {
		return 2;
	}
	
	@Override 
	public int estimatedEncodingSize() {
		return 1+code.estimatedEncodingSize()+value.estimatedEncodingSize();
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<?> getRef(int i) {
		switch(i) {
		case 0: return code;
		case 1: return value;
		}
		throw new IndexOutOfBoundsException(i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final AOp<T> updateRefs(IRefFunction func) {
		Ref<C> nc=func.apply(code);
		Ref<V> nv=func.apply(value);
		return rebuild(nc,nv);
	}

	protected abstract AOp<T> rebuild(Ref<C> newCode, Ref<V> newValue);

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to do
		
	}
}
