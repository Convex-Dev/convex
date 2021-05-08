package convex.core.data;

import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

/**
 * Interface for CVM Numeric types
 */
public interface INumeric {

	
	public CVMLong toLong();
	
	public CVMDouble toDouble();

	public double doubleValue();
	
	/**
	 * Gets the numeric type that should be used as for calculations
	 * @return Double.class or Long.class, or null if not a numeric type
	 */
	public Class<?> numericType();

	/**
	 * Gets the signum of this numerical value. Will be a Long -1, 0 or 1
	 * @return
	 */
	public CVMLong signum();
	
}
