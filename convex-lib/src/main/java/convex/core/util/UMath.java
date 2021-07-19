package convex.core.util;

/**
 * Functions for unsigned maths.
 * 
 * It would be nice if Java included these by default.
 */
public class UMath {

	/**
	 * Gets the high 64 bits of an unsigned multiply
	 * @param a 64-bit long interpreted as unsigned value
	 * @param b 64-bit long interpreted as unsigned value
	 * @return High 64 bits of unsigned multiply
	 */
	public static long multiplyHigh(long a, long b) {
		long r=Math.multiplyHigh(a, b);
		if ((a<0)^(b<0)) r=-r;
		return r;
	}

	/**
	 * Gets the carry of an unsigned addition of two longs 
	 * 
	 * @param a 64-bit long interpreted as unsigned value
	 * @param b 64-bit long interpreted as unsigned value
	 * @return 1 if the addition carries, 0 otherwise
	 */
	public static long unsignedAddCarry(long a, long b) {
		boolean sa=(a<0); // high bit of a
		boolean sb=(b<0); // high bit of b
		return (sa&&sb)||((sa||sb)&&(!((a+b)<0))) ? 1 : 0;
	}

}
