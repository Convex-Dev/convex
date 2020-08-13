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


	
	protected Call(long sequence, Address target, long offer,Symbol functionName,AVector<Object> args) {
		super(sequence);
		this.target=target;
		this.functionName=functionName;
		this.offer=offer;
		this.args=args;
	}
	
	public static Call create(long sequence, Address target, long offer,Symbol functionName,AVector<Object> args) {
		return new Call(sequence,target,0,functionName,args);
	}

	
	public static Call create(long sequence, Address target, Symbol functionName,AVector<Object> args) {
		return create(sequence,target,0,functionName,args);
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
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.CALL);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = super.writeRaw(bb); // sequence
		bb = Format.write(bb, target);
		bb=Format.writeVLCLong(bb, offer);
		bb=Format.write(bb, functionName);
		bb=Format.write(bb, args);
		return bb;
	}
	
	public static ATransaction read(ByteBuffer bb) throws BadFormatException {
		long sequence = Format.readVLCLong(bb);
		Address target=Format.read(bb);
		long offer = Format.readVLCLong(bb);
		Symbol functionName=Format.read(bb);
		AVector<Object> args = Format.read(bb);
		return create(sequence, target, offer, functionName,args);
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
	public long getMaxJuice() {
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
		return new Call(sequence,target,offer,functionName,newArgs);
	}


}
