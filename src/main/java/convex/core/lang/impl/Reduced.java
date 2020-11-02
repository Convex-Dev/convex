package convex.core.lang.impl;

import convex.core.ErrorCodes;

public class Reduced extends AReturn {

	private final Object value;

	public Reduced(Object value) {
		this.value=value;
	}

	public static Reduced wrap(Object value) {
		return new Reduced(value);
	}
	
	@Override
	public Object getCode() {
		return ErrorCodes.REDUCED;
	}

	@Override
	public Object getMessage() {
		return "Return value from 'reduced'";
	}
	
	public Object getValue() {
		return value;
	}

}
