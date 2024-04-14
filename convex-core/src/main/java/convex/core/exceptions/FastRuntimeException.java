package convex.core.exceptions;

@SuppressWarnings("serial")
public class FastRuntimeException extends RuntimeException {
	
	public FastRuntimeException(String message) {
		super(message);
	}

	// Don't fill in a stack trace for fast exceptions. We are going to catch and ignore it anyway.....
	@Override
	public Throwable fillInStackTrace() {
		return this;
	}      
}
