package convex.core.lang.exception;

import convex.core.data.ACell;

public abstract class AThrowable extends AExceptional {

	protected final ACell code;

	public AThrowable(ACell code) {
		if (code==null) throw new IllegalArgumentException("Error code must not be null");
		this.code=code;
	}
	
	@Override
	public final ACell getCode() {
		return code;
	}

	public abstract void addTrace(String traceMessage);
}
