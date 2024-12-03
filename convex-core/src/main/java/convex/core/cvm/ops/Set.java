package convex.core.cvm.ops;

import convex.core.ErrorCodes;
import convex.core.cvm.AOp;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Ref;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Op to set a lexical value in the local execution context. i.e. `set!`
 *
 * @param <T> Result type of Op
 */
public class Set<T extends ACell> extends ACodedOp<T,CVMLong,AOp<T>> {

	/**
	 * Stack position in lexical stack
	 */
	private final long position;

	public Set(Ref<CVMLong> code, Ref<AOp<T>> value) {
		super(CVMTag.OP_CODED,code,value);
		this.position = code.getValue().longValue(); // safe because always embedded
	}
	
	private Set(long position, Ref<AOp<T>> op) {
		this(CVMLong.create(position).getRef(),op);
	}

	/**
	 * Creates Set Op for the given opCode
	 * 
	 * @param position Position in lexical value vector
	 * @param op Op to calculate new value
	 * @return Special instance, or null if not found
	 */
	public static final <R extends ACell> Set<R> create(long position, AOp<R> op) {
		if (position < 0) return null;
		return new Set<R>(position, op.getRef());
	}

	@Override
	public Context execute(Context ctx) {
		AVector<ACell> env = ctx.getLocalBindings();
		long ec = env.count();
		if ((position < 0) || (position >= ec)) {
			return ctx.withError(ErrorCodes.BOUNDS, "Bad position for set!: " + position);
		}

		ctx = ctx.execute(value.getValue());
		if (ctx.isExceptional()) return ctx;
		ACell value = ctx.getResult();

		AVector<ACell> newEnv = env.assoc(position, value);
		ctx = ctx.withLocalBindings(newEnv);
		return ctx.consumeJuice(Juice.SET_BANG);
	}

	/**
	 * Reads a Set Op from a Blob encoding
	 * 
	 * @param <R> Type of Set result
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <R extends ACell> Set<R> read(Blob b, int pos) throws BadFormatException{
		int epos=pos+1; // skip tag to get to data
		
		Ref<CVMLong> index=Format.readRef(b, epos);
		epos+=index.getEncodingLength();
		
		Ref<AOp<R>> op=Format.readRef(b, epos);
		epos+=op.getEncodingLength();
		
		Set<R> result= new Set<R>(index,op);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (position < 0) {
			throw new InvalidDataException("Invalid Local position " + position, this);
		}
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("(set! %");
		sb.append(Long.toString(position));
		sb.append(' ');
		if (!value.getValue().print(sb, limit)) return false;
		sb.append(')');
		return sb.check(limit);
	}

	@Override
	protected AOp<T> rebuild(Ref<CVMLong> newCode, Ref<AOp<T>> newValue) {
		if ((newCode==code)&&(newValue==value)) return this;
		return new Set<T>(newCode,newValue);
	}

}
