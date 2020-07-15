package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.ErrorType;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AccountStatus;
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
	protected final long sequence;

	protected ATransaction(long sequence) {
		this.sequence = sequence;
	}

	/**
	 * Writes this transaction to a bytebuffer, including the message tag
	 */
	@Override
	public abstract ByteBuffer write(ByteBuffer b);

	/**
	 * Writes this transaction to a ByteBuffer, excluding the message tag
	 * 
	 * @param b
	 * @return Same ByteBuffer after writing
	 */
	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = Format.writeVLCLong(b, sequence);
		return b;
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
	protected abstract <T> Context<T> apply(Context<?> ctx);

	public final long getSequence() {
		return sequence;
	}

	/**
	 * Applies this transaction to the given state. Assumes the origin address has
	 * been checked via digital signature.
	 * 
	 * @param state  The initial chain state
	 * @param origin The origin account that has signed the transaction
	 * @return The updated chain state
	 */
	@SuppressWarnings("unchecked")
	public final <T> Context<T> applyTransaction(Address origin, State state) {
		AccountStatus account = state.getAccounts().get(origin);
		if (account == null) {
			return (Context<T>) Context.createFake(state).withError(ErrorType.NOBODY);
		}

		// Pre-transaction state updates (persist even if transaction fails)
		// update sequence
		account = account.updateSequence(sequence);
		if (account == null)
			return Context.createFake(state).withError(ErrorType.SEQUENCE, "Bad sequence: " + sequence);

		state = state.putAccount(origin, account);

		// Create context with juice subtracted
		Context<T> ctx = Context.createInitial(state, origin, getMaxJuice());
		final long totalJuice = ctx.getJuice();

		if (ctx.isExceptional()) {
			// error while preparing transaction. No state change.
			return ctx;
		}

		// apply transaction. This may result in an error!
		// comlete transaction handles error cases as well
		ctx = this.apply(ctx);
		ctx = ctx.completeTransaction(totalJuice, state.getJuicePrice());

		return (Context<T>) ctx;
	}

	/**
	 * Gets the max juice allowed for this transaction
	 * 
	 * @return Juice limit
	 */
	public abstract long getMaxJuice();
}
