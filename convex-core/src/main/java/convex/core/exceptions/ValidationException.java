package convex.core.exceptions;

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
}
