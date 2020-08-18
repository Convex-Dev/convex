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
