package convex.core.transactions;

import java.nio.ByteBuffer;

import convex.core.Constants;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Reader;
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
	protected final ACell command;

	protected Invoke(Address address,long sequence, ACell args) {
		super(address,sequence);
		this.command = args;
	}

	public static Invoke create(Address address,long sequence, ACell command) {
		return new Invoke(address,sequence, command);
	}
	
	/**
	 * Creates an Invoke transaction
	 * @param address Address of origin Account
	 * @param sequence Sequence number
	 * @param command Command as a string, which will be read as Convex Lisp code
	 * @return New Invoke transaction instance
	 */
	public static Invoke create(Address address,long sequence, String command) {
		return create(address,sequence, Reader.read(command));
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++] = Tag.INVOKE;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = super.encodeRaw(bs,pos); // nonce, address
		pos = Format.write(bs,pos, command);
		return pos;
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
	 * @param bb ByteBuffer containing the transaction
	 * @throws BadFormatException if the data is invalid
	 * @return The Transfer object
	 */
	public static Invoke read(ByteBuffer bb) throws BadFormatException {
		Address address=Address.create(Format.readVLCLong(bb));
		long sequence = Format.readVLCLong(bb);

		ACell args = Format.read(bb);
		return create(address,sequence, args);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Context<T> apply(final Context<?> context) {
		Context<T> ctx=(Context<T>) context;
		
		// Run command
		if (command instanceof AOp) {
			ctx = ctx.run((AOp<T>) command);
		} else {
			ctx = ctx.run(command);
		}
		return (Context<T>) ctx;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag (1), nonce(<12) and target (33)
		// plus allowance for Amount
		return 1 + 12 + Format.MAX_EMBEDDED_LENGTH + Format.MAX_VLC_LONG_LENGTH;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("{");
		sb.append(":invoke ");
		Utils.print(sb, command);
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
	public Long getMaxJuice() {
		// TODO make this a field
		return Constants.MAX_TRANSACTION_JUICE;
	}

	@Override
	public int getRefCount() {
		return Utils.refCount(command);
	}

	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return Utils.getRef(command, i);
	}

	@Override
	public Invoke updateRefs(IRefFunction func) {
		ACell newCommand = Utils.updateRefs(command, func);
		if (newCommand == command) return this;
		return Invoke.create(address,getSequence(), newCommand);
	}
	
	@Override
	public Invoke withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(address,newSequence,command);
	}
	
	@Override
	public Invoke withAddress(Address newAddress) {
		if (newAddress==this.address) return this;
		return create(newAddress,sequence,command);
	}

	@Override
	public byte getTag() {
		return Tag.INVOKE;
	}
}
