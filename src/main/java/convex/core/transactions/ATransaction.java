package convex.core.transactions;

import convex.core.data.ACell;
import convex.core.data.Address;
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
	protected final Address address;
	protected final long sequence;

	protected ATransaction(Address address, long sequence) {
		if (address==null) throw new ClassCastException("Null Address for transaction");
		this.address=address;
		this.sequence = sequence;
	}

	/**
	 * Writes this transaction to a byte array, including the message tag
	 */
	@Override
	public abstract int encode(byte[] bs, int pos);

	/**
	 * Writes this transaction to a byte array, excluding the message tag
	 * 
	 * @param bb
	 * @return Same ByteBuffer after writing
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, address.longValue());
		pos = Format.writeVLCLong(bs,pos, sequence);
		return pos;
	}

	@Override
	public abstract int estimatedEncodingSize();

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

	/**
	 * Gets the Address for this transaction
	 * @return
	 */
	public Address getAddress() {
		return address;
	}
	
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
	 * @param newSequence New sequence number
	 * @return Updated transaction, or this transaction if the sequence number is unchanged.
	 */
	public abstract ATransaction withSequence(long newSequence);

	/**
	 * Updates this transaction with the specified address
	 * @param newSequence New address
	 * @return Updated transaction, or this transaction if unchanged.
	 */
	public abstract ATransaction withAddress(Address newAddress);

}
