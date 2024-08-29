package convex.core.exceptions;

/**
 * Class representing errors in format encountered when trying to read data from
 * a serialised form.
 * 
 * 
 *
 */
@SuppressWarnings("serial")
public class BadFormatException extends ValidationException {

	public BadFormatException(String message) {
		super(message);
	}

	public BadFormatException(String message, Throwable cause) {
		super(message, cause);
	}
	
	public BadFormatException(Throwable cause) {
		this("Bad format", cause);
	}

}
