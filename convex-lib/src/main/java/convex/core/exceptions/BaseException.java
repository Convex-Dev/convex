package convex.core.exceptions;

/**
 * Abstract base class for exceptions that we expect to encounter and need to
 * handle.
 * 
 * "If you don’t handle [exceptions], we shut your application down. That
 * dramatically increases the reliability of the system.” - Anders Hejlsberg
 * 
 */
@SuppressWarnings("serial")
public abstract class BaseException extends Exception {

	public BaseException(String message) {
		super(message);
	}

	public BaseException(String message, Throwable cause) {
		super(message, cause);
	}

	@Override
	public Throwable fillInStackTrace() {
		return super.fillInStackTrace();
		// return this; // possible optimisation??
	}
}
