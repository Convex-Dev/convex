package convex.core.cvm.transactions;

import convex.core.cvm.AOp;
import convex.core.cvm.ARecordGeneric;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
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
import convex.core.lang.Reader;
import convex.core.util.ErrorMessages;

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
	protected ACell command;

	private static final Keyword[] KEYS = new Keyword[] { Keywords.ORIGIN, Keywords.SEQUENCE,Keywords.COMMAND};
	private static final RecordFormat FORMAT = RecordFormat.of(KEYS);
	private static final long FORMAT_COUNT=FORMAT.count();

	protected Invoke(Address origin,long sequence, ACell command) {
		super(CVMTag.INVOKE,FORMAT,Vectors.create(origin,CVMLong.create(sequence),command));
		this.command = command;
	}
	
	protected Invoke(AVector<ACell> values) {
		super(CVMTag.INVOKE,FORMAT,values);
	}

	public static Invoke create(Address origin,long sequence, ACell command) {
		if (sequence<0) throw new IllegalArgumentException("Illegal sequence number: "+sequence);
		return new Invoke(origin,sequence, command);
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
	
	/**
	 * Get the command for this transaction, as code.
	 * @return Command object.
	 */
	public ACell getCommand() {
		if (command==null) command=values.get(2);
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
		AVector<ACell> values=Vectors.read(b, pos);
		int epos=pos+values.getEncodingLength();

		if (values.count()!=FORMAT_COUNT) throw new BadFormatException(ErrorMessages.RECORD_VALUE_NUMBER);

		Invoke result=new Invoke(values);
		result.attachEncoding(b.slice(pos,epos));
		return result;
	}

	@Override
	public Context apply(final Context context) {
		Context ctx=context;
		ACell cmd=getCommand();
		// Run command
		if (cmd instanceof AOp) {
			ctx = ctx.run((AOp<?>) cmd);
		} else {
			ctx = ctx.run(cmd);
		}
		return ctx;
	}

	@Override
	public int estimatedEncodingSize() {
		// tag (1), sequence(<12) and target (33)
		// plus allowance for Amount
		return 1 + 12 + Format.MAX_EMBEDDED_LENGTH + Format.MAX_VLQ_LONG_LENGTH;
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
	public ACell get(Keyword key) {
		if (Keywords.COMMAND.equals(key)) return getCommand();
		return super.get(key); // covers origin and sequence
	}

	@Override
	protected ARecordGeneric withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Invoke(values);
	}

}
