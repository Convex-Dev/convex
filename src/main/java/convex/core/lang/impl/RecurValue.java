package convex.core.lang.impl;

import convex.core.ErrorCodes;
import convex.core.data.ASequence;
import convex.core.lang.RT;

/**
 * Class representing a function return value.
 * 
 * Contains argument values for each parameter to be substituted in the
 * surrounding function / loop
 */
public class RecurValue extends AReturn {

	private final Object[] values;

	private RecurValue(Object[] values) {
		this.values = values;
	}

	/**
	 * Wraps an object array as a RecurValue
	 * 
	 * @param values
	 * @return new RecurValue
	 */
	public static RecurValue wrap(Object... values) {
		return new RecurValue(values);
	}

	public Object getValue(int i) {
		return values[i];
	}

	public Object[] getValues() {
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
	public Object getCode() {
		return ErrorCodes.RECUR;
	}

	@Override
	public Object getMessage() {
		return values;
	}
}
