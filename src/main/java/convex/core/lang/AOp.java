package convex.core.lang;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.IRefContainer;
import convex.core.data.Symbol;
import convex.core.data.Tag;

/**
 * Abstract base class for operations
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
public abstract class AOp<T> extends ACell implements IRefContainer {

	/**
	 * Executes this op with the given context. Must preserve depth unless an
	 * exceptional is returned.
	 * 
	 * @param <I>
	 * @param context
	 * @return The updated Context after executing this operation
	 * 
	 * @throws ExecutionException
	 */
	public abstract <I> Context<T> execute(Context<I> context);

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Returns the opcode for this op
	 * 
	 * @return Opcode as a byte
	 */
	public abstract byte opCode();

	@Override
	public final ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.OP);
		b = b.put(opCode());
		return writeRaw(b);
	}

	/**
	 * Writes the raw data for this Op to the specified bytebuffer. Assumes Op tag
	 * and opcode already written.
	 * 
	 * @param b ByteBuffer to write to
	 * @return The updated ByteBuffer
	 */
	@Override
	public abstract ByteBuffer writeRaw(ByteBuffer b);

	/**
	 * Specialise this Op with a given set of bindings. Specialisation replaces
	 * symbol lookups with specific values. TODO: how useful is this? Make more
	 * general?
	 * 
	 * @param binds Bindings of symbols to replace within this Op.
	 * @return This op specialised with the given bindings.
	 */
	public abstract AOp<T> specialise(AMap<Symbol, Object> binds);
}