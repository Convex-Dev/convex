package convex.core.lang.impl;

import convex.core.cvm.AFn;
import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Abstract base wrapper class for data structure lookup functions.
 * 
 * Not a canonical object, essentially a wrapper for a data structure interpreted as a function
 * 
 * @param <T> Type of function return value
 */
public abstract class ADataFn<T extends ACell> extends AFn<T> {

	@Override
	public int estimatedEncodingSize() {
		return getCanonical().estimatedEncodingSize();
	}

	@Override
	public AFn<T> updateRefs(IRefFunction func) {
		return RT.castFunction(getCanonical().updateRefs(func));
	}

	@Override
	public boolean hasArity(int n) {
		return (n==1)||(n==2);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// nothing to do?
	}

	@Override
	public int encode(byte[] bs, int pos) {
		return getCanonical().encode(bs, pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return getCanonical().encodeRaw(bs, pos);
	}

	@Override
	public boolean isCanonical() {
		return false;
	}
	
	@Override
	public abstract ACell toCanonical();
	
	@Override
	public byte getTag() {
		return getCanonical().getTag();
	}
}
