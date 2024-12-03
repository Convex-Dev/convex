package convex.core.cvm;

import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.type.AType;
import convex.core.data.type.Types;

/**
 * Abstract base class for CVM operations
 * 
 * "...that was the big revelation to me when I was in graduate school—when I
 * finally understood that the half page of code on the bottom of page 13 of the
 * Lisp 1.5 manual was Lisp in itself. These were “Maxwell’s Equations of
 * Software!” This is the whole world of programming in a few lines that I can
 * put my hand over."
 * - Alan Kay
 *
 * @param <T> the type of the operation return value
 */
public abstract class AOp<T extends ACell> extends ACVMCode {

	/**
	 * Executes this op with the given context. Must preserve depth unless an
	 * exceptional is returned.
	 * 
	 * @param context Initial Context
	 * @return The updated Context after executing this operation
	 * 
	 */
	public abstract Context execute(Context context);

	@Override
	public final AType getType() {
		return Types.OP;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 10+Format.MAX_EMBEDDED_LENGTH;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override
	public ACell toCanonical() {
		return this;
	}

	@Override
	public final int encode(byte[] bs, int pos) {
		bs[pos++]=getTag();
		return encodeRaw(bs,pos);
	}
	
	@Override	
	public int encodeRaw(byte[] bs, int pos) {
		bs[pos++]=opCode();
		return encodeAfterOpcode(bs,pos);
	}

	protected byte opCode() {
		return 0;
	}

	/**
	 * Writes the raw data for this Op to the specified bytebuffer. Assumes Op tag
	 * and opcode already written.
	 * 
	 * @param bs Byte array to write to
	 * @param pos Position to write in byte array
	 * @return The updated position
	 */

	protected abstract int encodeAfterOpcode(byte[] bs, int pos);
	
	@Override
	public abstract AOp<T> updateRefs(IRefFunction func);
	
	@Override
	public byte getTag() {
		return CVMTag.OP_CODED;
	}
	
	@Override
	public boolean equals(ACell o) {
		return Cells.equalsGeneric(this, o);
	}
}