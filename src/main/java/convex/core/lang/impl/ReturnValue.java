package convex.core.lang.impl;

import org.parboiled.common.Utils;

import convex.core.ErrorCodes;

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

	@Override
	public Object getCode() {
		return ErrorCodes.RETURN;
	}

	@Override
	public Object getMessage() {
		return value;
	}
}
