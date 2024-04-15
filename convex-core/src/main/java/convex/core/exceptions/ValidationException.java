package convex.core.exceptions;

import convex.core.Constants;

/**
 * Class representing a validation failure
 * 
 */
@SuppressWarnings("serial")
public class ValidationException extends BaseException {
	public ValidationException(String message) {
		super(message);
	}

	public ValidationException(String message, Throwable cause) {
		super(message, cause);
	}
	
	// Don't fill in a stack trace for fast exceptions. We are going to catch and ignore it anyway.....
	@Override
	public Throwable fillInStackTrace() {
		if (Constants.OMIT_VALIDATION_STACKTRACES) return this;
		return super.fillInStackTrace();
	}   
}
