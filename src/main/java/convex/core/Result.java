package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ARecord;
import convex.core.data.ARecordGeneric;
import convex.core.data.AVector;
import convex.core.data.Keywords;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Context;
import convex.core.lang.impl.AExceptional;
import convex.core.lang.impl.RecordFormat;

/**
 * Class representing the result of a query or transaction.
 * 
 * A Result is used to communicate the outcome of a query of a transaction from a Peer to a Client.
 * 
 * 
 */
public class Result extends ARecordGeneric {

	public static final RecordFormat RESULT_FORMAT=RecordFormat.of(Keywords.ID,Keywords.RESULT,Keywords.ERROR_CODE);
	
	protected Result(AVector<Object> values) {
		super(RESULT_FORMAT, values);
	}
	
	public static Result create(AVector<Object> values) {
		return new Result(values);
	}
	
	public static Result create(Object id, Object value, Object errorCode) {
		return create(Vectors.of(id,value,errorCode));
	}

	public static Result create(Object id, Object value) {
		return create(id,value,null);
	}

	/**
	 * Returns the message ID for this result. Message ID is an arbitrary ID assigned by a client requesting a transaction.
	 * 
	 * @return ID from this result
	 */
	public Object getID() {
		return values.get(0);
	}
	
	/**
	 * Returns the value this result. The value is the result of transaction execution (may be an error message if the transaction failed)
	 * 
	 * @return ID from this result
	 */
	public Object getValue() {
		return values.get(1);
	}
	
	/**
	 * Returns the Error Code from this Result. Normally this should be a Keyword.
	 * 
	 * Will be null if no error occurred.
	 * 
	 * @return ID from this result
	 */
	public Object getErrorCode() {
		return values.get(2);
	}
	
	@Override
	public AVector<Object> getValues() {
		return values;
	}

	@Override
	protected ARecord withValues(AVector<Object> newValues) {
		if (values==newValues) return this;
		return new Result(newValues);
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		Object id=values.get(0);
		if ((id!=null)&&!(id instanceof Long)) {
			throw new InvalidDataException("Result ID must be a long value",this);
		}
	}

	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.RESULT;
		pos=values.writeRaw(bs,pos);
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
		AVector<Object> v=Vectors.read(bb);
		if (v.size()!=3) throw new BadFormatException("Invalid number of fields for Result!");
		
		return create(v);
	}

	public boolean isError() {
		return getErrorCode()!=null;
	}

	public static Result fromContext(Object id,Context<?> ctx) {
		Object result=ctx.getValue();
		Object code=null;
		if (result instanceof AExceptional) {
			AExceptional ex=(AExceptional)result;
			result=ex.getMessage();
			code=ex.getCode();
		}
		return create(id,result,code);
	}

	public Result withID(Object id) {
		return create(values.assoc(0, id));
	}


}
