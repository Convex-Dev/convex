package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * Interface for classes that can be validated
 */
public interface IValidated {

	/**
	 * Validates the complete structure of this object.
	 * 
	 * It may be necessary to ensure all child Refs are validated.
	 * 
	 * @throws InvalidDataException
	 */
	public void validate() throws InvalidDataException;

	/**
	 * Returns true if this object is in a canonical format for message writing.
	 * Reading or writing a non-canonical value should be considered illegal
	 * 
	 * @return true if the object is in canonical format, false otherwise
	 */
	public boolean isCanonical();

}
