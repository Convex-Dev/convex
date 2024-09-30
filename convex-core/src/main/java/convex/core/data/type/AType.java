package convex.core.data.type;

import convex.core.data.ACell;
import convex.core.data.AString;

/**
 * Abstract base class for CVM value types
 */
public abstract class AType {

	/**
	 * Checks if a value is an instance of this Type.
	 * @param value Any CVM value
	 * @return true if value is an instance of this Type, false otherwise
	 */
	public abstract boolean check(ACell value);
	
	/**
	 * Checks if this type allows a null value.
	 * 
	 * @return True if this type allows null values, false otherwise
	 */
	public abstract boolean allowsNull();
	
	@Override
	public abstract String toString();

	/**
	 * Gets the default value for this type. May return null.
	 * @return Default value for the given Type
	 */
	public abstract ACell defaultValue();
	
	/**
	 * Gets the default value for this type. Returns null if the cast fails.
	 * @param a Value to cast
	 * @return Value cast to this Type, or null if the cast fails
	 */
	public abstract ACell implicitCast(ACell a);
	
	/**
	 * Gets the Java common base class for all instances of this type.
	 * 
	 * @return Java Class representing this Type
	 */
	public abstract Class<? extends ACell> getJavaClass();

	/**
	 * Gets the tag to be used for printing
	 * @return Tag string, or null if no tag required
	 */
	public AString getTag() {
		return null;
	}
}
