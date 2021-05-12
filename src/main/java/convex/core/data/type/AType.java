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
	 * @return
	 */
	public abstract boolean allowsNull();
}
