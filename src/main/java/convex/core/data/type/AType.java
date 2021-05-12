package convex.core.data.type;

import convex.core.data.ACell;

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
	 * @return
	 */
	protected abstract ACell defaultValue();
	
	/**
	 * Gets the default value for this type. Returns null if the cast fails.
	 * @return
	 */
	protected abstract ACell implicitCast(ACell a);
}
