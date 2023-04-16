package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.BlobBuilder;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.util.Errors;

/**
 * Op to set a lexical value in the local execution context. i.e. `set!`
 *
 * @param <T> Result type of Op
 */
public class Set<T extends ACell> extends AOp<T> {

	/**
	 * Stack position in lexical stack
	 */
	private final long position;

	/**
	 * Op to compute new value
	 */
	private final Ref<AOp<T>> op;

	private Set(long position, Ref<AOp<T>> op) {
		this.position = position;
		this.op = op;
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

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Context<T> execute(Context<R> context) {
		Context<T> ctx = (Context<T>) context;
		AVector<ACell> env = ctx.getLocalBindings();
		long ec = env.count();
		if ((position < 0) || (position >= ec))
			return context.withError(ErrorCodes.BOUNDS, "Bad position for set!: " + position);

		ctx = ctx.execute(op.getValue());
		if (ctx.isExceptional()) return ctx;
		ACell value = ctx.getResult();

		AVector<ACell> newEnv = env.assoc(position, value);
		ctx = ctx.withLocalBindings(newEnv);
		return ctx.consumeJuice(Juice.SET_BANG);
	}

	@Override
	public byte opCode() {
		return Ops.SET;
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs, pos, position);
		pos = op.encode(bs,pos);
		return pos;
	}

	public static <R extends ACell> Set<R> read(ByteBuffer bb) throws BadFormatException {
		long position = Format.readVLCLong(bb);
		AOp<R> op = Format.read(bb);
		return create(position, op);
	}
	

	public static <R extends ACell> Set<R> read(Blob b, int pos) throws BadFormatException{
		int epos=pos+2;
		
		long position = Format.readVLCLong(b,epos);
		epos+=Format.getVLCLength(position);
		
		AOp<R> op = Format.read(b,epos);
		epos+=Format.getEncodingLength(op);
		
		Set<R> result= create(position, op);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	public Set<T> updateRefs(IRefFunction func) {
		@SuppressWarnings("unchecked")
		Ref<AOp<T>> newOp = (Ref<AOp<T>>) func.apply(op);
		if (op == newOp) return this;
		return new Set<T>(position, newOp);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (op == null) {
			throw new InvalidDataException("Null Set op ", this);
		}
		if (position < 0) {
			throw new InvalidDataException("Invalid Local position " + position, this);
		}
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<AOp<T>> getRef(int i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return op;
	}

	@Override
	public boolean print(BlobBuilder sb, long limit) {
		sb.append("(set! %");
		sb.append(Long.toString(position));
		sb.append(' ');
		if (!op.getValue().print(sb, limit)) return false;
		sb.append(')');
		return sb.check(limit);
	}

}
