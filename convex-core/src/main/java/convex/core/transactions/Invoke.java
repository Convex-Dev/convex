package convex.core.transactions;

import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.core.lang.RecordFormat;

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

	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE,Keywords.COMMAND};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);
	private static final long FORMAT_COUNT=FORMAT.count();

	protected Invoke(Address address,long sequence, ACell args) {
		super(FORMAT_COUNT,address,sequence);
		this.command = args;
	}

	public static Invoke create(Address address,long sequence, ACell command) {
		if (sequence<0) throw new IllegalArgumentException("Illegal sequence number: "+sequence);
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
		pos = super.encodeRaw(bs,pos); // origin, sequence
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
	 * Read a Transfer transaction from a Blob encoding
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static Invoke read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		long aval=Format.readVLCCount(b,epos);
		Address address=Address.create(aval);
		epos+=Format.getVLCCountLength(aval);
		
		long sequence = Format.readVLCCount(b,epos);
		epos+=Format.getVLCCountLength(sequence);
		
		ACell args=Format.read(b, epos);
		epos+=Format.getEncodingLength(args);

		Invoke result=create(address, sequence, args);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	public Context apply(final Context context) {
		Context ctx=context;
		
		// Run command
		if (command instanceof AOp) {
			ctx = ctx.run((AOp<?>) command);
		} else {
			ctx = ctx.run(command);
		}
		return ctx;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag (1), sequence(<12) and target (33)
		// plus allowance for Amount
		return 1 + 12 + Format.MAX_EMBEDDED_LENGTH + Format.MAX_VLC_LONG_LENGTH;
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
	public int getRefCount() {
		return Cells.refCount(command);
	}

	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return Cells.getRef(command, i);
	}

	@Override
	public Invoke updateRefs(IRefFunction func) {
		ACell newCommand = Ref.update(command, func);
		if (newCommand == command) return this;
		return Invoke.create(origin,getSequence(), newCommand);
	}
	
	@Override
	public Invoke withSequence(long newSequence) {
		if (newSequence==this.sequence) return this;
		return create(origin,newSequence,command);
	}
	
	@Override
	public Invoke withOrigin(Address newAddress) {
		if (newAddress==this.origin) return this;
		return create(newAddress,sequence,command);
	}

	@Override
	public byte getTag() {
		return Tag.INVOKE;
	}

	@Override
	public ACell get(Keyword key) {
		if (Keywords.COMMAND.equals(key)) return command;
		if (Keywords.ORIGIN.equals(key)) return origin;
		if (Keywords.SEQUENCE.equals(key)) return CVMLong.create(sequence);

		return null;
	}

	@Override
	public RecordFormat getFormat() {
		return FORMAT;
	}

}
