package convex.core.transactions;

import convex.core.data.ARecord;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.type.AType;
import convex.core.data.type.Transaction;
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
public abstract class ATransaction extends ARecord {
	protected final Address origin;
	protected final long sequence;

	protected ATransaction(long count,Address origin, long sequence) {
		super(count);
		if (origin==null) throw new ClassCastException("Null Origin Address for transaction");
		this.origin=origin;
		this.sequence = sequence;
	}

	/**
	 * Writes this transaction to a byte array, including the message tag
	 */
	@Override
	public abstract int encode(byte[] bs, int pos);

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, origin.toExactLong());
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
	 * @param ctx Context for which to apply this Transaction
	 * @return The updated chain state
	 */
	public abstract Context apply(Context ctx);

	/**
	 * Gets the *origin* Address for this transaction
	 * @return Address for this Transaction
	 */
	public Address getOrigin() {
		return origin;
	}
	
	/**
	 * Gets the sequence number for this transaction
	 * @return Sequence number
	 */
	public final long getSequence() {
		return sequence;
	}
	
	@Override public final boolean isCVMValue() {
		// Transactions exist outside CVM only
		return false;
	}
	
	@Override
	public AType getType() {
		return Transaction.INSTANCE;
	}
	
	/**
	 * Updates this transaction with the specified sequence number
	 * @param newSequence New sequence number
	 * @return Updated transaction, or this transaction if the sequence number is unchanged.
	 */
	public abstract ATransaction withSequence(long newSequence);

	/**
	 * Updates this transaction with the specified origin address
	 * @param newAddress New address
	 * @return Updated transaction, or this transaction if unchanged.
	 */
	public abstract ATransaction withOrigin(Address newAddress);
	
	@Override
	public boolean isCanonical() {
		return true;
	}

}
