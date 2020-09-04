package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.Format;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.Juice;

/**
 * Transaction class representing a coin Transfer from one account to another
 */
public class Transfer extends ATransaction {
	public static final long TRANSFER_JUICE = Juice.TRANSFER;

	protected final Address target;
	protected final Amount amount;

	protected Transfer(long nonce, Address target, Amount amount) {
		super(nonce);
		this.target = target;
		this.amount = amount;
	}

	public static Transfer create(long nonce, Address target, Amount amount) {
		return new Transfer(nonce, target, amount);
	}

	public static Transfer create(long nonce, Address target, long amount) {
		return create(nonce, target, Amount.create(amount));
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.TRANSFER);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = super.writeRaw(b); // nonce, address
		b = target.writeRaw(b);
		b = Format.write(b, amount);
		return b;
	}

	/**
	 * Read a Transfer transaction from a ByteBuffer
	 * 
	 * @param b ByteBuffer containing the transaction
	 * @throws BadFormatException if the data is invalid
	 * @return The Transfer object
	 */
	public static Transfer read(ByteBuffer b) throws BadFormatException {
		long nonce = Format.readVLCLong(b);
		Address target = Address.readRaw(b);
		Amount amount = Amount.read(b.get(), b);
		return create(nonce, target, amount);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Context<T> apply(Context<?> ctx) {
		// consume juice, ensure we have enough to make transfer!
		ctx = ctx.consumeJuice(Juice.TRANSFER);
		if (!ctx.isExceptional()) {
			ctx = ctx.transfer(target, amount.getValue());
		}
		return (Context<T>) ctx;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag (1), nonce(<12) and target (33)
		// plus allowance for Amount
		return 1 + 12 + 33 + Amount.MAX_BYTE_LENGTH;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#trans/transfer {");
		sb.append(":target ");
		target.ednString(sb);
		sb.append(',');
		sb.append(":amount ");
		amount.ednString(sb);
		sb.append('}');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("{");
		sb.append(":transfer-to ");
		target.print(sb);
		sb.append(',');
		sb.append(":amount ");
		amount.print(sb);
		sb.append('}');
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (amount == null) throw new InvalidDataException("Null Amount", this);
		if (target == null) throw new InvalidDataException("Null Address", this);
	}
	
	/**
	 * Gets the target address for this transfer
	 * @return Address of the destination for this transfer.
	 */
	public Address getTarget() {
		return target;
	}
	
	/**
	 * Gets the transfer amount for this transaction.
	 * @return Amount of transfer, as a long
	 */
	public long getAmount() {
		return amount.getValue();
	}

	@Override
	public Long getMaxJuice() {
		return Juice.TRANSFER;
	}
	
	@Override
	public int getRefCount() {
		return 0;
	}
	
	@Override
	protected boolean isEmbedded() {
		// TODO: consider if Transfer can be embedded. It's probably always small enough?
		return false;
	}
	
	@Override
	public ATransaction withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(newSequence,target,amount);
	}
}
