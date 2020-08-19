package convex.core.data;

import convex.core.exceptions.InvalidDataException;

/**
 * Interface for classes that can be validated
 */
public interface IValidated {

	/**
	 * Validates the complete structure of this object.
	 * 
	 * It is necessary to ensure all child Refs are validated, so the general contract for validate is:
	 * 
	 * <ol>
	 * <li>Call super.validate() - which will indirectly call validateCell()</li>
	 * <li>Call validate() on any contained cells in this class</li>
	 * </ol>
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
