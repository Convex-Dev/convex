package convex.core.lang.exception;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * Class representing a function return value.
 * 
 * Contains argument values for each parameter to be substituted in the
 * surrounding function / loop
 */
public class RecurValue extends ATrampoline {

	private RecurValue(ACell[] values) {
		super(values);
	}
 
	/**
	 * Wraps an object array as a RecurValue
	 * 
	 * @param values Values to recur with
	 * @return new RecurValue
	 */
	public static RecurValue wrap(ACell... values) {
		return new RecurValue(values);
	}

	@Override
	public String toString() {
		AVector<?> seq = Vectors.create(args); // should always convert OK
		return "RecurValue: " + seq;
	}

	@Override
	public ACell getCode() {
		return ErrorCodes.RECUR;
	}

	@Override
	public ACell getMessage() {
		return null;
	}
}
