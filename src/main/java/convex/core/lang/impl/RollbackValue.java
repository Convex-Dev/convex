package convex.core.lang.impl;

import org.parboiled.common.Utils;

/**
 * Class representing a function return value
 * 
 * "Computers are useless. They can only give you answers." - Pablo Picasso
 * 
 * @param <T> Type of return value
 */
public class RollbackValue<T> extends AExceptional {

	private final T value;

	public RollbackValue(T value) {
		this.value = value;
	}

	public static <T> RollbackValue<T> wrap(T value) {
		return new RollbackValue<T>(value);
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "RollbackValue: " + Utils.toString(value);
	}
}
