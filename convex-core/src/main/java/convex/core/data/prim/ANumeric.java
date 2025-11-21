package convex.core.data.prim;

import java.math.BigDecimal;
import java.math.BigInteger;

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

	@Override
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
	
	/**
	 * Returns true if this numeric value is negative. Note zero and NaN are not negative
	 * @return true if negative
	 */
	public abstract boolean isNegative();

	/**
	 * Returns true if this numeric value is positive. Note zero and NaN are not positive
	 * @return true if negative
	 */
	public abstract boolean isPositive();
	
	/**
	 * Returns true if this numeric value is a natural integer
	 * @return true if a natural integer, false otherwise
	 */
	public abstract boolean isNatural();


	/**
	 * Gets the absolute value of this number. May be ##NaN for Doubles.
	 * @return Absolute value of the numeric value
	 */
	public abstract ANumeric abs();

	/**
	 * Gets the numeric value as a Long integer, or null if not a valid Long
	 * @return CVMLong value, or null
	 */
	public abstract CVMLong ensureLong();

	/**
	 * Adds a second numeric value to this value
	 * @param b Second number to add
	 * @return Result of addition
	 */
	public abstract ANumeric add(ANumeric b);
	
	/**
	 * Subtracts a second numeric value to this value
	 * @param b Number to subtract
	 * @return Result of subtraction
	 */
	public abstract ANumeric sub(ANumeric b);

	/**
	 * Negates this numeric value
	 * @return Negated value
	 */
	public abstract ANumeric negate();

	/**
	 * Multiplies a second numeric value with this value
	 * @param b Second number to add
	 * @return Result of multiplication
	 */	
	public abstract ANumeric multiply(ANumeric b);

	/**
	 * Converts this numeric value to the nearest integer
	 * @return Integer value, or null if bad conversion (e.g. infinity or NaN)
	 */
	public abstract AInteger toInteger();
	
	/**
	 * Check if this numeric value is equal to zero
	 * @return True if this value is numerically equal to zero
	 */
	public abstract boolean isZero();
	
	
	@SuppressWarnings("unchecked")
	public static <T extends ANumeric> T fromNumber(Number number) {
        if (number == null) {
            return null;
        }

        // Fast paths for common types
        if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return (T) CVMLong.create(number.longValue());
        }
        if (number instanceof Double || number instanceof Float) {
            return (T) CVMDouble.create(number.doubleValue());
        }

        if (number instanceof BigInteger bi) {
            return (T) AInteger.create(bi);
        }

        // Slow path for other Number subclasses (e.g., AtomicInteger, AtomicLong)
        try {
            BigDecimal bd = new BigDecimal(number.toString());
            BigInteger bi = bd.toBigIntegerExact();
            if (bi.bitLength() <= 63) {
                return (T) CVMLong.create(bi.longValue());
            }
            return (T) CVMBigInteger.create(bi);
        } catch (ArithmeticException | NumberFormatException e) {
            return (T) CVMDouble.create(number.doubleValue());
        }
    }
}
