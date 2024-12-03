package convex.core.cvm.ops;

import convex.core.ErrorCodes;
import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.ErrorMessages;

/**
 * Op to look up a local value from the lexical environment
 *
 * @param <T> Result type of Op
 */
public class Local<T extends ACell> extends AOp<T> {
	
	/**
	 * Stack position in lexical stack
	 */
	private final long position;
	

	private Local(long position) {
		this.position=position;
	}
	

	/**
	 * Creates Local to look up a lexical value in the given position
	 * 
	 * @param position Position in lexical value vector
	 * @return Special instance, or null if not found
	 */
	public static final <R extends ACell> Local<R> create(long position) {
		if (position<0) return null;
		// TODO: we can probably cache these?
		return new Local<R>(position);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Context execute(Context ctx) {
		AVector<ACell> env=ctx.getLocalBindings();
		long ec=env.count();
		if ((position<0)||(position>=ec)) {
			return ctx.withError(ErrorCodes.BOUNDS,"Bad position for Local: "+position);
		}
		T result = (T)env.get(position);
		return ctx.withResult(Juice.LOOKUP,result);
	}
	
	@Override
	public byte getTag() {
		return CVMTag.OP_LOCAL;
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLQLong(bs, pos, position);
		return pos;
	}

	@Override
	public int encodeAfterOpcode(byte[] bs, int pos) {
		throw new Error(ErrorMessages.UNREACHABLE);
	}

	@Override
	public Local<T> updateRefs(IRefFunction func) {
		return this;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (position<0) {
			throw new InvalidDataException("Invalid Local position "+position, this);
		}
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append(toString());
		return bb.check(limit);
	}
	
	@Override
	public String toString() {
		return "%" + position;
	}





}
