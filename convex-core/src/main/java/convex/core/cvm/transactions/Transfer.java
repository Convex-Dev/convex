package convex.core.cvm.transactions;

import convex.core.Coin;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;

/**
 * Transaction class representing a coin Transfer from one account to another
 */
public class Transfer extends ATransaction {
	protected final Address target;
	protected final long amount;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE, Keywords.TARGET, Keywords.AMOUNT};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	protected Transfer(Address origin,long sequence, Address target, long amount) {
		super(CVMTag.TRANSFER,FORMAT,Vectors.create(origin,CVMLong.create(sequence),target,CVMLong.create(amount)));
		this.target = target;
		this.amount = amount;
	}
	
	protected Transfer(AVector<ACell> values) {
		super(CVMTag.TRANSFER,FORMAT,values);
		this.target = RT.ensureAddress(values.get(2));
		this.amount = RT.ensureLong(values.get(3)).longValue();
	}

	public static Transfer create(Address origin,long sequence, Address target, long amount) {
		if (!Coin.isValidAmount(amount)) throw new IllegalArgumentException(ErrorMessages.BAD_AMOUNT);
		return new Transfer(origin,sequence, target, amount);
	}

	public static ATransaction read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=KEYS.length) throw new BadFormatException(ErrorMessages.RECORD_VALUE_NUMBER);

		Transfer result=new Transfer(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	@Override
	public Context apply(Context ctx) {
		// consume juice, ensure we have enough to make transfer!
		ctx = ctx.consumeJuice(Juice.TRANSFER);
		
		// As long as juice was successfully consumed, make the transfer
		if (!ctx.isExceptional()) {
			ctx = ctx.transfer(target, amount);
		}
		
		// Return unconditionally. Might be an error.
		return ctx;
	}
	

	@Override
	public int estimatedEncodingSize() {
		// tag (1), sequence(<12) and target (33)
		// plus allowance for Amount
		return 1 + 12 + 33 + Format.MAX_VLQ_LONG_LENGTH;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (!Coin.isValidAmount(amount)) throw new InvalidDataException("Invalid amount", this);
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
	public Transfer withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(origin,newSequence,target,amount);
	}
	
	@Override
	public Transfer withOrigin(Address newAddress) {
		if (newAddress==this.origin) return this;
		return create(newAddress,sequence,target,amount);
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.TARGET.equals(key)) return RT.ensureAddress(values.get(2));
		if (Keywords.AMOUNT.equals(key)) return RT.ensureLong(values.get(3));
		return super.get(key); // covers origin and sequence
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Transfer(newValues);
	}
}
