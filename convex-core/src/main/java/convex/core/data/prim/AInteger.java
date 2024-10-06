package convex.core.data.prim;

import java.math.BigInteger;

import convex.core.data.ABlob;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

/**
 * Abstract base class for CVM Integer values
 */
public abstract class AInteger extends ANumeric {
 
	@Override
	public abstract boolean isCanonical();
	
	@Override
	public abstract AInteger toCanonical();

	/**
	 * Increments this Integer
	 * @return Incremented value
	 */
	public abstract AInteger inc();
	
	@Override
	public abstract CVMLong signum();
	
	/**
	 * Decrements this Integer
	 * @return Decremented value
	 */
	public abstract AInteger dec();
	
	public AType getType() {
		return Types.INTEGER;
	}
	
	/**
	 * Parse an integer value as a canonical value
	 * @param s String to parse
	 * @return AInteger instance, or null if not convertible
	 */
	public static AInteger parse(String s) {
		int n=s.length();
		if (n<19) return CVMLong.parse(s); // can't be a big integer
		if (n>20) return CVMBigInteger.parse(s); // can't be a long
				
		// Try long first, otherwise fall back to big integer
		AInteger r= CVMLong.parse(s);
		if (r==null) {
			r=CVMBigInteger.parse(s);
			if (r!=null) r=r.toCanonical();
		}
		return r;
	}
	
	/**
	 * Create a canonical integer from a two's complement Blob
	 * @param o Object to parse
	 * @return AInteger instance, or null if not convertible
	 */
	public static AInteger create(ABlob o) {
		if (o.count()<=8) {
			return CVMLong.create(o.longValue());
		} else {
			CVMBigInteger bi= CVMBigInteger.create(o);
			if (bi==null) return null;
			return bi.getCanonical();
		}
	}
	
	/**
	 * Parse a  value as a canonical integer
	 * @param o Object to parse
	 * @return AInteger instance, or null if not convertible
	 */
	public static AInteger parse(Object o) {
		if (o==null) return null;
		if (o instanceof AInteger) return (AInteger)o;
		if (o instanceof Number) return create((Number)o);
		return parse(o.toString());
	}

	/**
	 * Number of bytes in minimal representation of this Integer. Returns 0 if and only if the integer is zero.
	 * @return Number of bytes
	 */
	public abstract int byteLength();

	@Override
	public ANumeric add(ANumeric b) {
		if (b instanceof AInteger) return add((AInteger)b);
		return CVMDouble.create(doubleValue()+b.doubleValue());
	}
	
	/**
	 * Adds another integer to this integer
	 * @param a Integer value to add
	 * @return New integer
	 */
	public abstract AInteger add(AInteger a);
	
	@Override
	public ANumeric sub(ANumeric b) {
		if (b instanceof AInteger) return sub((AInteger)b);
		return CVMDouble.create(doubleValue()-b.doubleValue());
	}
	
	/**
	 * Subtracts another integer from this integer
	 * @param a Integer value to subtract
	 * @return New integer
	 */
	public abstract AInteger sub(AInteger a);

	/**
	 * Converts this integer to a Java BigInteger. WARNING: might be O(n)
	 * @return Java BigInteger
	 */
	public abstract BigInteger big();
	
	@Override
	public AInteger toInteger() {
		return this;
	}

	/**
	 * Create a canonical CVM integer representation of the given Java BigInteger
	 * @param bi BigInteger value
	 * @return AInteger instance, or null if BigInteger too large or null
	 */
	public static AInteger create(BigInteger bi) {
		if (bi==null) return null;
		int len=Utils.byteLength(bi);
		if (len<=8) return CVMLong.create(bi.longValue());
		return CVMBigInteger.wrap(bi); // note: returns null if bigInteger is too large
	}
	
	/**
	 * Create a canonical CVM integer representation of the given Java Long
	 * @param value Long value
	 * @return AInteger instance
	 */
	public static AInteger create(long value) {
		return CVMLong.create(value);
	}
	
	/**
	 * Create a canonical CVM integer representation of the given Java Number
	 * @param value Long value
	 * @return AInteger instance, or null if too large to represent as a CVM integer
	 */
	public static AInteger create(Number value) {
		if (value instanceof Long) return CVMLong.create(((Long)value).longValue());
		if (value instanceof BigInteger) return create((BigInteger)value);
		return CVMLong.create(value.longValue());
	}

	/**
	 * Returns the modulus of this integer with a given integer base
	 * @param base Base of modulus operation
	 * @return Modulus result
	 */
	public abstract AInteger mod(AInteger base);

	/**
	 * Divides this integer with a given denominator. Performs Euclidian division consistent with mod
	 * @param base Base of division
	 * @return Division result
	 */
	public abstract AInteger div(AInteger base);

	/**
	 * Divides this integer with a given denominator. Performs division consistent with rem
	 * @param divisor Base of division
	 * @return Division result
	 */
	public abstract AInteger quot(AInteger divisor);

	/**
	 * Returns the remainder of dividing this integer with a given divisor
	 * @param divisor Base of division operation
	 * @return Modulus result
	 */
	public abstract AInteger rem(AInteger divisor);

	/**
	 * Converts this Integer value to Blob form
	 * @return minimal blob representing the integer value
	 */
	public abstract ABlob toBlob();

	/**
	 * Return true if this value is a valid 64-bit long integer
	 * 
	 * @return true if this integer fits in a 64-bit long
	 */
	public abstract boolean isLong();

	/**
	 * Raise the integer to the given power
	 * @param power Power to raise integer to
	 * @return Result, or null if invalid
	 */
	public AInteger toPower(AInteger power) {
		throw new TODOException();
	}

}
