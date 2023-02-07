package convex.core.data.prim;

/**
 * BAse class for CVM numeric types
 */
public abstract class ANumeric extends APrimitive implements Comparable<ANumeric> {


	/**
	 * Casts to a CVM Long value. Takes last 64 bits.
	 * @return Long representation of this number
	 */
	public abstract CVMLong toLong();
	
	/**
	 * Casts to a CVM Double value. 
	 * @return Double value
	 */
	public abstract CVMDouble toDouble();

	public abstract double doubleValue();
	
	/**
	 * Gets the numeric type that should be used as for calculations
	 * @return Double.class or Long.class, or null if not a numeric type
	 */
	public abstract Class<?> numericType();

	/**
	 * Gets the signum of this numerical value. Will be -1, 0 or 1 for Longs, -1.0, 0.0 , 1.0 or ##NaN for Doubles.
	 * @return Signum of the numeric value
	 */
	public abstract APrimitive signum();

	public abstract ANumeric toStandardNumber();

	/**
	 * Gets the absolute value of this number. May be ##NaN for Doubles.
	 * @return Absolute value of the numeric value
	 */
	public abstract APrimitive abs();

	/**
	 * Gets the numeric value as a Long integer, or null if not a valid Long
	 * @return CVMLong value, or null
	 */
	public abstract CVMLong asLongInteger();
}
