package convex.core.data;

/**
 * Base interface for objects.
 * 
 * May be useful for off-chain objects and utility functionality
 * 
 * Functionality for on-chain objects should go in IData.
 * 
 */
public interface IObject {

	/**
	 * Appends the String value of this object to the given StringBuilder in edn
	 * format
	 * 
	 */
	public void ednString(StringBuilder sb);

	/**
	 * Gets the representation of this object as an edn format String
	 * 
	 * @return An edn format String representing this object.
	 */
	public default String ednString() {
		StringBuilder sb = new StringBuilder();
		ednString(sb);
		return sb.toString();
	}
}
