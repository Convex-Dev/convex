package convex.core.lang.exception;

import convex.core.ErrorCodes;
import convex.core.data.ACell;

/**
 * Class representing a halt return value
 * 
 * "Computers are useless. They can only give you answers." - Pablo Picasso
 * 
 * @param <T> Type of return value
 */
public class HaltValue<T extends ACell> extends AReturn {

	private final T value;

	public HaltValue(T value) {
		this.value = value;
	}

	public static <T extends ACell> HaltValue<T> wrap(T value) {
		return new HaltValue<T>(value);
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "HaltValue: " + value;
	}

	@Override
	public ACell getCode() {
		return ErrorCodes.HALT;
	}

	@Override
	public ACell getMessage() {
		return null;
	}
}
