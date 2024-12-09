package convex.core.cvm.transactions;

import convex.core.Coin;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Keywords;
import convex.core.cvm.RecordFormat;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;

/**
 * Transaction representing a Call to an Actor.
 * 
 * The signer of the transaction will be both the *origin* and *caller* for the Actor code.
 * 
 * This is the most efficient way to execute Actor code directly as a client, and is roughly equivalent to invoking
 * (call actor offer (function-name arg1 arg2 .....))
 */
public class Call extends ATransaction {
	public static final long DEFAULT_OFFER = 0;

	protected final ACell target;
	protected final long offer;
	protected final Symbol functionName;
	protected AVector<ACell> args;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE,  Keywords.TARGET , Keywords.OFFER,Keywords.CALL, Keywords.ARGS };
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	protected Call(AVector<ACell> values) {
		super(CVMTag.CALL,FORMAT,values);
		this.target=values.get(2);
		this.offer=RT.ensureLong(values.get(3)).longValue();
		this.functionName=RT.ensureSymbol(values.get(4));
		// no need to set args, will be pulled on demand
	}
	
	protected Call(Address origin, long sequence, ACell target, long offer,Symbol functionName,AVector<ACell> args) {
		super(CVMTag.CALL,FORMAT,Vectors.create(origin,CVMLong.create(sequence),target, CVMLong.create(offer),functionName,args));
		this.target=target;
		this.functionName=functionName;
		this.offer=offer;
		this.args=args;
	}
	
	public static Call create(Address address, long sequence, ACell target, long offer,Symbol functionName,AVector<ACell> args) {
		return new Call(address,sequence,target,offer,functionName,args);
	}

	
	public static Call create(Address address, long sequence, ACell target, Symbol functionName,AVector<ACell> args) {
		return create(address,sequence,target,DEFAULT_OFFER,functionName,args);
	}
	
	public static Call create(AVector<ACell> values) {
		return new Call(values);
	}

	
	/**
	 * Reads a Call Transaction from a Blob encoding
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static Call read(Blob b, int pos) throws BadFormatException {
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=KEYS.length) throw new BadFormatException(ErrorMessages.RECORD_VALUE_NUMBER);

		Call result=new Call(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public Context apply(Context ctx) {
		return ctx.actorCall(target, offer, functionName, args.toCellArray());
	}

	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		if (!Coin.isValidAmount(offer)) throw new InvalidDataException("Invalid offer",this);
	}

	@Override
	public Call withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(origin,newSequence,target,offer,functionName,args);
	}
	
	@Override
	public Call withOrigin(Address newAddress) {
		if (newAddress==this.origin) return this;
		return create(newAddress,sequence,target,offer,functionName,args);
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.CALL.equals(key)) return functionName;
		if (Keywords.OFFER.equals(key)) return values.get(3);
		if (Keywords.TARGET.equals(key)) return target;
		if (Keywords.ARGS.equals(key)) return getArgs();
		return super.get(key); // covers origin and sequence
	}

	private ACell getArgs() {
		if (args==null) {
			args=RT.ensureVector(values.get(5));
		}
		return args;
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Call(values);
	}


}
