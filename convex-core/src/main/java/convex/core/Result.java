package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.ARecord;
import convex.core.data.ARecordGeneric;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Keywords;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.ErrorValue;
import convex.core.lang.impl.RecordFormat;

/**
 * Class representing the result of a Query or Transaction.
 * 
 * A Result is typically used to communicate the outcome of a Query or a Transaction from a Peer to a Client.
 * 
 * 
 */
public class Result extends ARecordGeneric {

	public static final RecordFormat RESULT_FORMAT=RecordFormat.of(Keywords.ID,Keywords.RESULT,Keywords.ERROR_CODE,Keywords.TRACE);
	
	protected Result(AVector<ACell> values) {
		super(RESULT_FORMAT, values);
	}
	
	public static Result create(AVector<ACell> values) {
		return new Result(values);
	}
	
	public static Result create(CVMLong id, ACell value, ACell errorCode, ACell trace) {
		return create(Vectors.of(id,value,errorCode,trace));
	}
	
	public static Result create(CVMLong id, ACell value, ACell errorCode) {
		return create(id,value,errorCode,null);
	}

	public static Result create(CVMLong id, ACell value) {
		return create(id,value,null,null);
	}

	/**
	 * Returns the message ID for this result. Message ID is an arbitrary ID assigned by a client requesting a transaction.
	 * 
	 * @return ID from this result
	 */
	public ACell getID() {
		return values.get(0);
	}
	
	/**
	 * Returns the value for this result. The value is the result of transaction execution (may be an error message if the transaction failed)
	 * 
	 * @return ID from this result
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T getValue() {
		return (T)values.get(1);
	}
	
	/**
	 * Returns the stack trace for this result. May be null
	 * 
	 * @return ID from this result
	 */
	@SuppressWarnings("unchecked")
	public AVector<AString> getTrace() {
		return (AVector<AString>) values.get(3);
	}
	
	/**
	 * Returns the Error Code from this Result. Normally this should be a Keyword.
	 * 
	 * Will be null if no error occurred.
	 * 
	 * @return ID from this result
	 */
	public ACell getErrorCode() {
		return values.get(2);
	}
	
	@Override
	public AVector<ACell> values() {
		return values;
	}

	@Override
	protected ARecord withValues(AVector<ACell> newValues) {
		if (values==newValues) return this;
		return new Result(newValues);
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		Object id=values.get(0);
		if ((id!=null)&&!(id instanceof CVMLong)) {
			throw new InvalidDataException("Result ID must be a CVM long value",this);
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.RESULT;
		pos=values.encodeRaw(bs,pos);
		return pos;
	}
	
	/**
	 * Reads a Result from a ByteBuffer encoding. Assumes tag byte already read.
	 * 
	 * @param bb
	 * @return The Result read
	 * @throws BadFormatException If a Result could not be read
	 */
	public static Result read(ByteBuffer bb) throws BadFormatException {
		AVector<ACell> v=Vectors.read(bb);
		if (v.size()!=RESULT_FORMAT.count()) throw new BadFormatException("Invalid number of fields for Result!");
		
		return create(v);
	}

	public boolean isError() {
		return getErrorCode()!=null;
	}

	public static Result fromContext(CVMLong id,Context<?> ctx) {
		Object result=ctx.getValue();
		ACell errorCode=null;
		ACell trace=null;
		if (result instanceof AExceptional) {
			AExceptional ex=(AExceptional)result;
			result=ex.getMessage();
			errorCode=ex.getCode();
			if (ex instanceof ErrorValue) {
				trace=Vectors.create(((ErrorValue)ex).getTrace());
			}
		}
		return create(id,(ACell)result,errorCode,trace);
	}

	/**
	 * Updates result with a given message ID. Used to tag Results for return to Clients
	 * @param id New Result message ID
	 * @return Updated Result
	 */
	public Result withID(ACell id) {
		return create(values.assoc(0, id));
	}

	@Override
	public byte getTag() {
		return Tag.RESULT;
	}


}
