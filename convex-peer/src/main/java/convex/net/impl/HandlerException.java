package convex.net.impl;

/**
 * Exception thrown when an unexpected error occurs in a message handler
 */
@SuppressWarnings("serial")
public class HandlerException extends Exception {

	public HandlerException(String message, Exception cause) {
		super(message,cause);
	}

}
