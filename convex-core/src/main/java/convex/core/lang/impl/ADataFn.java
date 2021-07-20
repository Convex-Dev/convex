package convex.core.lang.impl;

import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.Context;

/**
 * Abstract base class for data structure lookup functions.
 * 
 * Not a canonical object, can't exist as CVM value.
 * 
 * @param <T> Type of function return value
 */
public abstract class ADataFn<T extends ACell> extends AFn<T> {

	@Override
	public int estimatedEncodingSize() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Context<T> invoke(Context<ACell> context, ACell[] args) {
		throw new UnsupportedOperationException();
	}

	@Override
	public AFn<T> updateRefs(IRefFunction func) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean hasArity(int n) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		throw new UnsupportedOperationException();
	}

	@Override
	public int encode(byte[] bs, int pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCanonical() {
		return false;
	}
	
	@Override
	public ACell toCanonical() {
		throw new UnsupportedOperationException("Can't make canonical!");
	}
	
	@Override
	public boolean isCVMValue() {
		return false;
	}
	
	@Override
	public byte getTag() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getRefCount() {
		throw new UnsupportedOperationException();
	}
}
