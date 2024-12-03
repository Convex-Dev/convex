package convex.core.lang.impl;

import convex.core.cvm.AFn;
import convex.core.cvm.CVMTag;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;

/**
 * Abstract base class for functions that can close over a lexical environment.
 * 
 * Encoded as CAD3 Dense Record
 *
 * @param <T> Return type of function
 */
public abstract class AClosure<T extends ACell> extends AFn<T> {
	
	protected static final AVector<ACell> EMPTY_BINDINGS=Vectors.empty();

	/**
	 * Lexical environment saved for this closure
	 */
	protected final AVector<ACell> data;
	

	protected AClosure(AVector<ACell> data) {
		this.data=data;
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return data.encodeRaw(bs, pos);
	}

	public final byte getTag() {
		return CVMTag.FN;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return data.estimatedEncodingSize();
	}
	
	@Override
	public int getRefCount() {
		return data.getRefCount();
	}
	
	@Override
	public Ref<ACell> getRef(int i) {
		return data.getRef(i);
	}
	
	@Override
	public AFn<T> updateRefs(IRefFunction func) {
		AVector<ACell> newData=data.updateRefs(func);
		if (data==newData) return this;
		return recreate(newData);
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		// TODO Anything to check?
	}
	
	protected abstract AFn<T> recreate(AVector<ACell> newData);

	/**
	 * Produces an copy of this closure with the specified environment
	 * 
	 * @param env New lexical environment to use for this closure
	 * @return Closure updated with new lexical environment
	 */
	public abstract <F extends AClosure<T>> F withEnvironment(AVector<ACell> env);
	
	/**
	 * Print the "internal" representation of a closure e.g. "[x] 1", excluding the 'fn' symbol.
	 * @param sb StringBuilder to print to
	 * @param limit Limit of BlobBuilder size
	 * @return True if printed successfully within limit, false otherwise
	 */
	public abstract boolean printInternal(BlobBuilder sb, long limit);

}
