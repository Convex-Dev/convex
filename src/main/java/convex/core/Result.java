package convex.core;

import java.nio.ByteBuffer;

import convex.core.data.ARecord;
import convex.core.data.ARecordGeneric;
import convex.core.data.AVector;
import convex.core.data.Keywords;
import convex.core.data.Tag;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.impl.RecordFormat;

/**
 * Class representing the result of 
 */
public class Result extends ARecordGeneric {

	public static final RecordFormat RESULT_FORMAT=RecordFormat.of(Keywords.ID,Keywords.RESULT,Keywords.ERROR_CODE);
	
	protected Result(AVector<Object> values) {
		super(RESULT_FORMAT, values);
	}
	
	
	private static Result create(long id, Object value, Object errorCode) {
		return new Result(Vectors.of(id,value,errorCode));
	}


	public static Result create(long id, Object value) {
		return create(id,value,null);
	}

	/**
	 * Returns the message ID for this result. Message ID is an arbitrary ID assigned by a client requesting a transaction.
	 * 
	 * @return ID from this result
	 */
	public Long getID() {
		return (Long)values.get(0);
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
	 * Returns the error code from this Result. Will be null if no error occurred.
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

	protected ARecord withValues(AVector<Object> newValues) {
		if (values==newValues) return this;
		return new Result(newValues);
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb.put(Tag.RESULT);
		bb=values.writeRaw(bb);
		return bb;
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
		return new Result(v);
	}


}
