package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.Symbol;
import convex.core.data.Tag;
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
	protected final Symbol functionName;
	protected final long offer;
	protected final AVector<Object> args;


	
	protected Call(long sequence, Address target, long offer,Symbol functionName,AVector<Object> args) {
		super(sequence);
		this.target=target;
		this.functionName=functionName;
		this.offer=offer;
		this.args=args;
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
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.CALL);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = super.writeRaw(b); // nonce, address
		b = Format.write(b, target);
		return b;
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	protected <T> Context<T> apply(Context<?> ctx) {
		return ctx.actorCall(target, 0L, functionName, args.toArray());
	}

	@Override
	public long getMaxJuice() {
		return 0;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		target.validateCell();
	}

}
