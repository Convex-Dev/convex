package convex.core.transactions;

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
	 * Writes this transaction to a byte array, including the message tag
	 */
	@Override
	public abstract int write(byte[] bs, int pos);

	/**
	 * Writes this transaction to a byte array, excluding the message tag
	 * 
	 * @param bb
	 * @return Same ByteBuffer after writing
	 */
	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, sequence);
		return pos;
	}

	@Override
	public abstract int estimatedEncodingSize();
	
	@Override
	public final boolean isEmbedded() {
		// don't embed transactions. Might need to persist individually.
		return false;
	}

	/**
	 * Applies the functional effect of this transaction to the current state. 
	 * 
	 * Important points:
	 * <ul>
	 * <li>Assumes all relevant accounting preparation already complete, including juice reservation</li>
	 * <li>Performs complete state update (including any rollbacks from errors)</li>
	 * <li>Produces result, which may be exceptional</li>
	 * <li>Does not finalise memory/juice accounting (will be completed afterwards)</li>
	 * </ul>
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
	public abstract Long getMaxJuice();

	/**
	 * Updates this transaction with the specified sequence number
	 * @param newSequence NEw sequence number
	 * @return Updated transaction, or this transaction is the sequence number is unchanged.
	 */
	public abstract ATransaction withSequence(long newSequence);
}
