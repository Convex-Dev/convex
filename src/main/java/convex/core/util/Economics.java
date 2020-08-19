package convex.core.util;

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
	
	/**
	 * Computes the price for d units of Asset A in terms of units of Asset B
	 * such that a constant liquidity pool c = a * b is maintained.
	 * 
	 * @param a Quantity of Asset A in Pool
	 * @param b Quantity of Asset B in Pool
	 * @param delta Quantity of Unit A to buy (negative = sell)
	 * @return Price of A in terms of B
	 */
	public static long swapPrice(long delta,long a, long b) {
		if ((a<0)||(b<0)) throw new IllegalArgumentException("Pool quantities cannot be negative");
		
		double c = (double)a*(double)b;
		double newA = (double)a-(double)delta;
		if (newA>Long.MAX_VALUE) throw new IllegalArgumentException("Can't exceed Long pool size for A");
		if (newA<=0) throw new IllegalArgumentException("Cannot buy entire Pool");
		double newB = c / newA;
		
		if (newB>Long.MAX_VALUE) throw new IllegalArgumentException("Can't exceed Long pool size for B");
		
		// Round to closest whole value. Preserves c as close as possible.
		long finalB=Math.round(newB);
		
		return finalB-b;
	}
}
