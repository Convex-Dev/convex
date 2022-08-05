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
	 * @throws InvalidDataException If the data Value is invalid in any way
	 */
	public void validate() throws InvalidDataException;



}
