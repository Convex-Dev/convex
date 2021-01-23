package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.util.Utils;

/**
 * Transaction representing a Call to an Actor.
 * 
 * The signer of the transaction will be both the *origin* and *caller* for the Actor code.
 * 
 * This is the most efficient way to execute Actor code directly as a client, and is roughly equivalent to invoking
 * (call actor offer (function-name arg1 arg2 .....))
 */
public class Call extends ATransaction {

	protected final Address target;
	protected final long offer;
	protected final Symbol functionName;
	protected final AVector<Object> args;


	
	protected Call(Address address, long sequence, Address target, long offer,Symbol functionName,AVector<Object> args) {
		super(address,sequence);
		this.target=target;
		this.functionName=functionName;
		this.offer=offer;
		this.args=args;
	}
	
	public static Call create(Address address, long sequence, Address target, long offer,Symbol functionName,AVector<Object> args) {
		return new Call(address,sequence,target,0,functionName,args);
	}

	
	public static Call create(Address address, long sequence, Address target, Symbol functionName,AVector<Object> args) {
		return create(address,sequence,target,0,functionName,args);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#trans/call {");
		sb.append(":target ");
		Utils.ednString(sb, target);
		sb.append('}');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("{");
		sb.append(":target ");
		Utils.ednString(sb, target);
		if (offer>0) {
			sb.append(" :offer ");
			sb.append(offer);
		}
		sb.append('}');
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
		pos=Format.writeVLCLong(bs,pos, offer);
		pos=Format.write(bs,pos, functionName);
		pos=Format.write(bs,pos, args);
		return pos;
	}
	
	public static ATransaction read(ByteBuffer bb) throws BadFormatException {
		Address address=Address.readRaw(bb);
		long sequence = Format.readVLCLong(bb);
		Address target=Format.read(bb);
		long offer = Format.readVLCLong(bb);
		Symbol functionName=Format.read(bb);
		AVector<Object> args = Format.read(bb);
		return create(address,sequence, target, offer, functionName,args);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public <T> Context<T> apply(Context<?> ctx) {
		return ctx.actorCall(target, offer, functionName, args.toArray());
	}

	@Override
	public Long getMaxJuice() {
		return Constants.MAX_TRANSACTION_JUICE;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		target.validateCell();
	}
	
	@Override
	public int getRefCount() {
		return args.getRefCount();
	}
	
	@Override
	public <T> Ref<T> getRef(int i) {
		return args.getRef(i);
	}

	@Override
	public ACell updateRefs(IRefFunction func) {
		AVector<Object> newArgs=args.updateRefs(func);
		if (args==newArgs) return this;
		return new Call(address,sequence,target,offer,functionName,newArgs);
	}

	@Override
	public ATransaction withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(address,newSequence,target,offer,functionName,args);
	}


}
