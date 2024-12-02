package convex.core.cvm.transactions;

import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.type.AType;
import convex.core.data.type.Transaction;
import convex.core.lang.RT;

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
public abstract class ATransaction extends ARecordGeneric {
	
	/**
	 * Sequence number for transactions where required sequence is currently unknown
	 */
	public static final long UNKNOWN_SEQUENCE = 0;
	
	protected final Address origin;
	protected final long sequence;

	protected ATransaction(byte tag,RecordFormat format, AVector<ACell> values) {
		super(tag,format,values);
		this.origin=RT.ensureAddress(values.get(0));
		if (origin==null) throw new IllegalArgumentException("Null Origin Address for transaction");
		this.sequence = RT.ensureLong(values.get(1)).longValue();
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
	 * @param ctx Context for which to apply this Transaction
	 * @return The updated Context
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
	 * Gets the sequence number for this transaction. The first transaction for any account should have sequence number 1. 
	 * The sequence number must be incremented by 1 for each subsequent transaction.
	 * 
	 * A sequence number of 0 stands for "unknown".
	 * 
	 * @return Sequence number
	 */
	public final long getSequence() {
		return sequence;
	}
	
	@Override
	public AType getType() {
		return Transaction.INSTANCE;
	}
	
	@Override
	public ACell get(Keyword key) {
		if (Keywords.ORIGIN.equals(key)) return origin;
		if (Keywords.SEQUENCE.equals(key)) return values.get(1);
		return null;
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
	
	@Override
	public boolean equals(ACell o) {
		return Cells.equalsGeneric(this, o);
	}

}
