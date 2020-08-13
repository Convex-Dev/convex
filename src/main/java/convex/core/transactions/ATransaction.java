package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.Format;
import convex.core.lang.Context;

/**
 * Abstract base class for immutable transactions
 * 
 * Transactions may modify the on-chain State according to the rules of the
 * specific transaction type. When applied to a State, a transaction must
 * produce either: a) A valid updated State b) A TransactionException
 * 
 * Any other class of exception should be regarded as a serious failure,
 * indicating a code error or system integrity issue.
 *
 */
public abstract class ATransaction extends ACell {
	protected final long sequence;

	protected ATransaction(long sequence) {
		this.sequence = sequence;
	}

	/**
	 * Writes this transaction to a bytebuffer, including the message tag
	 */
	@Override
	public abstract ByteBuffer write(ByteBuffer bb);

	/**
	 * Writes this transaction to a ByteBuffer, excluding the message tag
	 * 
	 * @param bb
	 * @return Same ByteBuffer after writing
	 */
	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = Format.writeVLCLong(bb, sequence);
		return bb;
	}

	@Override
	public abstract int estimatedEncodingSize();

	/**
	 * Applies the effect of this transaction to the current state. Assumes all
	 * relevant preparation complete.
	 * 
	 * @param state  The initial chain state
	 * @param source The source account that has signed the transaction
	 * @return The updated chain state
	 */
	public abstract <T> Context<T> apply(Context<?> ctx);

	public final long getSequence() {
		return sequence;
	}

	/**
	 * Gets the max juice allowed for this transaction
	 * 
	 * @return Juice limit
	 */
	public abstract long getMaxJuice();
	
	@Override
	protected boolean isEmbedded() {
		// Transactions generally too large for embedding
		return false;
	}
}
