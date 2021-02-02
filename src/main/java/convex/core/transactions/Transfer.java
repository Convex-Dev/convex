package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.RT;

/**
 * Transaction class representing a coin Transfer from one account to another
 */
public class Transfer extends ATransaction {
	public static final long TRANSFER_JUICE = Juice.TRANSFER;

	protected final Address target;
	protected final long amount;

	protected Transfer(Address address,long nonce, Address target, long amount) {
		super(address,nonce);
		this.target = target;
		this.amount = amount;
	}

	public static Transfer create(Address address,long nonce, Address target, long amount) {
		return new Transfer(address,nonce, target, amount);
	}


	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.TRANSFER;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = super.encodeRaw(bs,pos); // nonce, address
		pos = target.encodeRaw(bs,pos);
		pos = Format.writeVLCLong(bs, pos, amount);
		return pos;
	}

	/**
	 * Read a Transfer transaction from a ByteBuffer
	 * 
	 * @param bb ByteBuffer containing the transaction
	 * @throws BadFormatException if the data is invalid
	 * @return The Transfer object
	 */
	public static Transfer read(ByteBuffer bb) throws BadFormatException {
		Address address=Address.create(Format.readVLCLong(bb));
		long nonce = Format.readVLCLong(bb);
		Address target = Address.readRaw(bb);
		long amount = Format.readVLCLong(bb);
		if (!RT.isValidAmount(amount)) throw new BadFormatException("Invalid amount: "+amount);
		return create(address,nonce, target, amount);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Context<T> apply(Context<?> ctx) {
		// consume juice, ensure we have enough to make transfer!
		ctx = ctx.consumeJuice(Juice.TRANSFER);
		if (!ctx.isExceptional()) {
			ctx = ctx.transfer(target, amount);
		}
		return (Context<T>) ctx;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag (1), nonce(<12) and target (33)
		// plus allowance for Amount
		return 1 + 12 + 33 + Format.MAX_VLC_LONG_LENGTH;
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
		sb.append(":amount "+amount);
		sb.append('}');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("{");
		sb.append(":transfer-to ");
		target.print(sb);
		sb.append(',');
		sb.append(":amount "+amount);
		sb.append('}');
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((amount<0)||(amount>Constants.MAX_SUPPLY)) throw new InvalidDataException("Invalid amount", this);
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
		return amount;
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
	public Transfer withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(address,newSequence,target,amount);
	}
	
	@Override
	public Transfer withAddress(Address newAddress) {
		if (newAddress==this.address) return this;
		return create(newAddress,sequence,target,amount);
	}
}
