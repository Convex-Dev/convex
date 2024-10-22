package convex.core.cvm.ops;

import convex.core.ErrorCodes;
import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

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
	public byte opCode() {
		return Ops.LOCAL;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=Format.writeVLQLong(bs, pos, position);
		return pos;
	}
	
	public static <R extends ACell> Local<R> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		long position=Format.readVLQLong(b,epos);
		epos+=Format.getVLQLongLength(position);
		
		Local<R> result= create(position);
		if (result==null) throw new BadFormatException("Can't create Local with position: "+position);
		result.attachEncoding(b.slice(pos, epos));
		return result;
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
