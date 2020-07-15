package convex.core.lang.impl;

import org.parboiled.common.Utils;

/**
 * Class representing a halt return value
 * 
 * "Computers are useless. They can only give you answers." - Pablo Picasso
 * 
 * @param <T> Type of return value
 */
public class HaltValue<T> extends AExceptional {

	private final T value;

	public HaltValue(T value) {
		this.value = value;
	}

	public static <T> HaltValue<T> wrap(T value) {
		return new HaltValue<T>(value);
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return "HaltValue: " + Utils.toString(value);
	}
}
