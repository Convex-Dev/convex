package convex.core.lang.impl;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.ASequence;
import convex.core.lang.RT;

/**
 * Class representing a function return value.
 * 
 * Contains argument values for each parameter to be substituted in the
 * surrounding function / loop
 */
public class RecurValue extends AReturn {

	private final ACell[] values;

	private RecurValue(ACell[] values) {
		this.values = values;
	}

	/**
	 * Wraps an object array as a RecurValue
	 * 
	 * @param values
	 * @return new RecurValue
	 */
	public static RecurValue wrap(ACell... values) {
		return new RecurValue(values);
	}

	public Object getValue(int i) {
		return values[i];
	}

	public ACell[] getValues() {
		return values;
	}

	public int arity() {
		return values.length;
	}

	@Override
	public String toString() {
		ASequence<?> seq = RT.sequence(values); // should always convert OK
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
