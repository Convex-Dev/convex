package convex.core.lang.impl;

import convex.core.ErrorCodes;
import convex.core.data.ACell;

public class Reduced extends AReturn {

	private final ACell value;

	public Reduced(ACell value) {
		this.value=value;
	}

	public static Reduced wrap(ACell value) {
		return new Reduced(value);
	}
	
	@Override
	public ACell getCode() {
		return ErrorCodes.REDUCED;
	}

	@Override
	public ACell getMessage() {
		return null;
	}
	
	public ACell getValue() {
		return value;
	}

}
