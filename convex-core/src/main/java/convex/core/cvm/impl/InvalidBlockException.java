package convex.core.cvm.impl;

import convex.core.exceptions.ValidationException;

@SuppressWarnings("serial")
public class InvalidBlockException extends ValidationException {

	public InvalidBlockException(String message) {
		super(message);
	}

}
