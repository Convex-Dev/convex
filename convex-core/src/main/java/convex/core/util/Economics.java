package convex.core.util;

import java.math.BigInteger;

/**
 * Utility function for Convex Cryptoeconomics
 */
public class Economics {

	/**
	 * Computes the marginal exchange rate between assets A and B with pool quantities,
	 * such that a constant liquidity pool c = a * b is maintained.
	 * 
	 * @param a Quantity of Asset A
	 * @param b Quantity of Asset B
	 * @return Price of A in terms of B
	 */
	public static double swapRate(long a, long b) {
		if ((a<=0)||(b<=0)) throw new IllegalArgumentException("Pool quantities must be positive");
		return (double)b/(double)a;
	}
	
	static final BigInteger MAX_POOL_SIZE=BigInteger.valueOf(Long.MAX_VALUE);
	
	
	/**
	 * Computes the smallest price for d units of Asset A in terms of units of Asset B
	 * such that a constant liquidity pool c = a * b is increased
	 * 
	 * @param a Quantity of Asset A in Pool
	 * @param b Quantity of Asset B in Pool
	 * @param delta Quantity of Unit A to buy (negative = sell)
	 * @return Price of A in terms of B
	 */
	public static long swapPrice(long delta,long a, long b) {
		if ((a<=0)||(b<=0)) throw new IllegalArgumentException("Pool quantities must be positive");
		
		BigInteger c = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
		long newA = a-delta;
		if (newA<=0) throw new IllegalArgumentException("Cannot buy entire Pool");
		
		BigInteger newBigA=BigInteger.valueOf(newA);
		if (newBigA.compareTo(MAX_POOL_SIZE)>=0) throw new IllegalArgumentException("Can't exceed Long pool size for A");

		BigInteger newBigB = c.divide(newBigA);
		if (newBigB.compareTo(MAX_POOL_SIZE)>=0) throw new IllegalArgumentException("Can't exceed Long pool size for B");
		
		// Convert back to long, add one so pool size must strictly increase
		long finalB=newBigB.longValueExact()+1;
		
		return finalB-b;
	}
}
