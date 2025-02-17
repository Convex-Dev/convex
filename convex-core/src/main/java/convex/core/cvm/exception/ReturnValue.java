package convex.core.cvm.exception;

import convex.core.ErrorCodes;
import convex.core.data.ACell;

/**
 * Class representing a function return value
 * 
 * "Computers are useless. They can only give you answers." - Pablo Picasso
 * 
 * @param <T> Type of return value
 */
public class ReturnValue<T extends ACell> extends AReturn {

	private final T value;

	public ReturnValue(T value) {
		this.value = value;
	}

	public static <T extends ACell> ReturnValue<T> wrap(T value) {
		return new ReturnValue<T>(value);
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "ReturnValue: " + value;
	}

	@Override
	public ACell getCode() {
		return ErrorCodes.RETURN;
	}

	@Override
	public ACell getMessage() {
		return null;
	}
}
