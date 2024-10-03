package convex.core.cvm.impl;

import convex.core.exceptions.ValidationException;

/**
 * Exception thrown when a Block contains invalid structure or data
 */
@SuppressWarnings("serial")
public class InvalidBlockException extends ValidationException {

	public InvalidBlockException(String message) {
		super(message);
	}

}
