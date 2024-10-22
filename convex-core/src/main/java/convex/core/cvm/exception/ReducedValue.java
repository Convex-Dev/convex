package convex.core.cvm.exception;

import convex.core.ErrorCodes;
import convex.core.data.ACell;

public class ReducedValue extends AReturn {

	private final ACell value;

	public ReducedValue(ACell value) {
		this.value=value;
	}

	public static ReducedValue wrap(ACell value) {
		return new ReducedValue(value);
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
