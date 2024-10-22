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
public class RollbackValue<T extends ACell> extends AReturn {

	private final T value;

	public RollbackValue(T value) {
		this.value = value;
	}

	public static <T extends ACell> RollbackValue<T> wrap(T value) {
		return new RollbackValue<T>(value);
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "RollbackValue: " + value;
	}
	
	@Override
	public ACell getCode() {
		return ErrorCodes.ROLLBACK;
	}

	@Override
	public ACell getMessage() {
		return value;
	}
}
