package convex.core.exceptions;

/**
 * Class for reader parse exceptions
 *
 */
@SuppressWarnings("serial")
public class ParseException extends Error {

	public ParseException(String message) {
		super(message);
	}

	public ParseException(String message, Throwable cause) {
		super(message, cause);
	}
}
