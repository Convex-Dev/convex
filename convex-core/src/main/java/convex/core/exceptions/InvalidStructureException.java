package convex.core.exceptions;

/**
 * Exception thrown when a cell is inconsistent with one of its children, so the
 * tree it heads is not structurally correct
 * (<a href="https://docs.convex.world/docs/cad/encoding">CAD003</a>).
 */
@SuppressWarnings("serial")
public class InvalidStructureException extends FastRuntimeException {

	/**
	 * Creates an exception for a structural check that failed
	 * @param message Description of the inconsistency
	 * @param cause The failure raised by the check
	 */
	public InvalidStructureException(String message, Throwable cause) {
		super(message);
		initCause(cause);
	}
}
