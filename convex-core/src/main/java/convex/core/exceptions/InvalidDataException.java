package convex.core.exceptions;

/**
 * Class representing errors encountered during data validation.
 * 
 * In general, InvalidDataException occurs if the data format is correct, but
 * the data fails to satisfy a validation invariant.
 */
@SuppressWarnings("serial")
public class InvalidDataException extends ValidationException {
	private final Object data;

	public InvalidDataException(String message, Object data, Throwable cause) {
		super(message,cause);
		this.data = data;
	}

	
	public InvalidDataException(String message, Object data) {
		super(message);
		this.data = data;
	}

	public Object getData() {
		return data;
	}
}
