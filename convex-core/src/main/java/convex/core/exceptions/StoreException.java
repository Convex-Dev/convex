package convex.core.exceptions;

/**
 * Exception thrown when a store operation fails fundamentally, e.g. an IO
 * failure or an attempt to read from a closed store.
 *
 * Unchecked: this represents a fundamental failure of code or infrastructure
 * assumptions, not a condition sane application code should attempt to handle.
 * It must never be interpreted as "data not present" — genuine absence is
 * signalled by a null Ref or MissingDataException.
 */
@SuppressWarnings("serial")
public class StoreException extends RuntimeException {
	public StoreException(String message, Throwable cause) {
		super(message, cause);
	}

	public StoreException(String message) {
		this(message, null);
	}
}
