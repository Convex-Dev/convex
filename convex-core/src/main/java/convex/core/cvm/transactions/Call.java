package convex.core.cvm.transactions;

import convex.core.Coin;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.RecordFormat;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Tag;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

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

	protected final Address target;
	protected final long offer;
	protected final Symbol functionName;
	protected final AVector<ACell> args;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE,  Keywords.TARGET , Keywords.OFFER,Keywords.CALL };
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);

	protected Call(Address address, long sequence, Address target, long offer,Symbol functionName,AVector<ACell> args) {
		super(FORMAT.count(),address,sequence);
		this.target=target;
		this.functionName=functionName;
		this.offer=offer;
		this.args=args;
	}
	
	public static Call create(Address address, long sequence, Address target, long offer,Symbol functionName,AVector<ACell> args) {
		return new Call(address,sequence,target,offer,functionName,args);
	}

	
	public static Call create(Address address, long sequence, Address target, Symbol functionName,AVector<ACell> args) {
		return create(address,sequence,target,DEFAULT_OFFER,functionName,args);
	}
	
	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++] = Tag.CALL;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = super.encodeRaw(bs,pos); // sequence
		pos = Format.write(bs,pos, target);
		pos=Format.writeVLQCount(bs,pos, offer);
		pos=Format.write(bs,pos, functionName);
		pos=Format.write(bs,pos, args);
		return pos;
	}
	
	/**
	 * Reads a Call Transaction from a Blob encoding
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static Call read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		long aval=Format.readVLQCount(b,epos);
		Address origin=Address.create(aval);
		epos+=Format.getVLQCountLength(aval);
		
		long sequence = Format.readVLQCount(b,epos);
		epos+=Format.getVLQCountLength(sequence);
		
		Address target=Format.read(b, epos);
		epos+=Format.getEncodingLength(target);
		
		long offer=Format.readVLQCount(b,epos);
		epos+=Format.getVLQCountLength(offer);
		if (!Coin.isValidAmount(offer)) throw new BadFormatException("Invalid offer in Call");


		Symbol functionName=Format.read(b,epos);
		epos+=Format.getEncodingLength(functionName);

		AVector<ACell> args = Format.read(b,epos);
		epos+=Format.getEncodingLength(args);

		Call result=create(origin,sequence, target, offer, functionName,args);
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
		if (!Coin.isValidAmount(offer)) throw new InvalidDataException("Invalid offer",this);
		target.validateCell();
	}
	
	@Override
	public int getRefCount() {
		return args.getRefCount();
	}
	
	@Override
	public <T extends ACell> Ref<T> getRef(int i) {
		return args.getRef(i);
	}

	@Override
	public Call updateRefs(IRefFunction func) {
		AVector<ACell> newArgs=args.updateRefs(func);
		if (args==newArgs) return this;
		return new Call(origin,sequence,target,offer,functionName,newArgs);
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
	public byte getTag() {
		return Tag.CALL;
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.CALL.equals(key)) return args.cons(functionName);
		if (Keywords.OFFER.equals(key)) return CVMLong.create(offer);
		if (Keywords.ORIGIN.equals(key)) return origin;
		if (Keywords.SEQUENCE.equals(key)) return CVMLong.create(sequence);
		if (Keywords.TARGET.equals(key)) return target;

		return null;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

}
