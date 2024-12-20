package convex.core.exceptions;

import convex.core.Constants;

@SuppressWarnings("serial")
public class FastRuntimeException extends RuntimeException {
	
	public FastRuntimeException(String message) {
		super(message);
	}

	// Don't fill in a stack trace for fast exceptions. We are going to catch and ignore it anyway.....
	@Override
	public Throwable fillInStackTrace() {
		if (Constants.OMIT_VALIDATION_STACKTRACES) return this;
		return super.fillInStackTrace();

	}      
}
