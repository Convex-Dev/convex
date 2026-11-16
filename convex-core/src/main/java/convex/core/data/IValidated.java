package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * Interface for classes that can be validated
 */
public interface IValidated {

	/**
	 * Validates the complete structure of this object, trusting any part already
	 * validated or persisted.
	 *
	 * @throws InvalidDataException If the data Value is invalid in any way
	 */
	public void validate() throws InvalidDataException;



}
