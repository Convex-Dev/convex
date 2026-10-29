package convex.core.cvm.ops;

import convex.core.data.util.BlobBuilder;
import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.Cells;
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
	public int calcHeaderLength() {
		// tag only: code and value are refs
		return 1;
	}
	
	@Override
	public int encodeAfterOpcode(byte[] bs, int pos) {
		throw new Error(ErrorMessages.UNREACHABLE);
	}
	
	@Override
	public final int getRefCount() {
		return 2;
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
	public boolean equals(ACell o) {
		if (o==this) return true;
		if (o instanceof ACodedOp) {
			ACodedOp<T,C,V> op=(ACodedOp<T,C,V>) o;
			if (tag!=op.tag) return false;
			// Children compare through their refs: embedded by value, branch by hash
			return code.equals(op.code)&&value.equals(op.value);
		}
		return Cells.equalsEncoding(this, o); // another cell with an op tag
	}

	@SuppressWarnings("unchecked")
	@Override
	protected final AOp<T> rebuildRefs(IRefFunction func) {
		Ref<C> nc=func.apply(code);
		Ref<V> nv=func.apply(value);
		return rebuild(nc,nv);
	}

	/** Print malformed code without inspecting its child types. */
	protected boolean printRaw(BlobBuilder bb,long limit) {
		bb.append("#[");
		bb.append(getEncoding().toHexString());
		bb.append(']');
		return bb.check(limit);
	}

	protected abstract AOp<T> rebuild(Ref<C> newCode, Ref<V> newValue);

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to do
		
	}
}
