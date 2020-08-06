package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.Amount;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.util.Utils;

/**
 * Transaction class representing the Invoke of an on-chain operation.
 * 
 * The command provided may be specified as either: 
 * <ul>
 * <li> A Form (will be compiled and executed) </li>
 * <li> A pre-compiled Op (will be executed directly, cheaper)</li>
 * </ul>
 * 
 * Peers may separately implement functionality to parse and compile a command provided as a String: this must be
 * performed outside the CVM which not provide a parser internally.
 */
public class Invoke extends ATransaction {
	protected final Object command;

	protected Invoke(long nonce, Object args) {
		super(nonce);
		this.command = args;
	}

	public static Invoke create(long nonce, Object command) {
		return new Invoke(nonce, command);
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.INVOKE);
		return writeRaw(b);
	}
	
	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = super.writeRaw(b); // nonce, address
		b = Format.write(b, command);
		return b;
	}
	
	/**
	 * Get the command for this transaction, as code.
	 * @return Command object.
	 */
	public Object getCommand() {
		return command;
	}

	/**
	 * Read a Transfer transaction from a ByteBuffer
	 * 
	 * @param b ByteBuffer containing the transaction
	 * @throws BadFormatException if the data is invalid
	 * @return The Transfer object
	 */
	public static Invoke read(ByteBuffer b) throws BadFormatException {
		long nonce = Format.readVLCLong(b);

		Object args = Format.read(b);
		return create(nonce, args);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Context<T> apply(Context<?> ctx) {
		if (command instanceof AOp) {
			ctx = ctx.execute((AOp<Object>) command);
		} else {
			ctx = ctx.eval(command);
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
		sb.append("#trans/invoke {");
		sb.append(":args ");
		Utils.ednString(sb, command);
		sb.append('}');
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (command instanceof AOp) {
			// OK?
			((AOp<?>) command).validateCell();
		} else {
			if (!Format.isCanonical(command)) throw new InvalidDataException("Non-canonical object as command?", this);
		}
	}

	@Override
	public long getMaxJuice() {
		// TODO make this a field
		return Constants.MAX_TRANSACTION_JUICE;
	}

	@Override
	public int getRefCount() {
		return Utils.refCount(command);
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		return Utils.getRef(command, i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <N extends ACell> N updateRefs(IRefFunction func) {
		Object newCommand = Utils.updateRefs(command, func);
		if (newCommand == command) return (N) this;
		return (N) Invoke.create(getSequence(), newCommand);
	}

}
