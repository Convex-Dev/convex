package convex.core.lang.impl;

import org.parboiled.common.Utils;

/**
 * Class representing a function return value
 * 
 * "Computers are useless. They can only give you answers." - Pablo Picasso
 * 
 * @param <T> Type of return value
 */
public class ReturnValue<T> extends AExceptional {

	private final T value;

	public ReturnValue(T value) {
		this.value = value;
	}

	public static <T> ReturnValue<T> wrap(T value) {
		return new ReturnValue<T>(value);
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "ReturnValue: " + Utils.toString(value);
	}
}
