package convex.core.util;

import convex.core.cpos.CPoSConstants;

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
	 * Computes the smallest price for d units of Asset A in terms of units of Asset B
	 * such that a constant liquidity pool c = a * b is increased
	 * 
	 * @param a Quantity of Asset A in Pool
	 * @param b Quantity of Asset B in Pool
	 * @param delta Quantity of Unit A to buy (negative = sell)
	 * @return Price of A in terms of B. Long.MAX_VALUE or Long.MIN_VALUE in case of overflow
	 */
	public static long swapPrice(long delta,long a, long b) {
		if ((a<=0)||(b<=0)) throw new IllegalArgumentException("Pool quantities must be positive");
		if (delta>=a) throw new IllegalArgumentException("Trying to buy entire pool!");
		long newA=a-delta;
		if (newA<=0) throw new IllegalArgumentException("Trying to sell beyond maxiumum pool size!");
		
		long newB=Utils.mulDiv(a, b, newA);
		if (newB<0) {
			// overflow case
			return delta>0?Long.MAX_VALUE:Long.MIN_VALUE;
		}
		
		// Ensure strict increase:
		// - if newB was exact, this ensures a strict increase of 1
		// - if newB was rounded down (had remainder) then this effectively rounds up
		long result=(newB-b)+1; 
		
		return result;
	}

	public static double stakeDecay(long time, long peerTime) {
		if (peerTime<0) return CPoSConstants.PEER_DECAY_MINIMUM;
		if (peerTime>=time) return 1.0;
		double delay=time-peerTime;
		delay-=CPoSConstants.PEER_DECAY_DELAY;
		if (delay<0) return 1.0;
		
		return Math.max(CPoSConstants.PEER_DECAY_MINIMUM, Math.exp(-delay/CPoSConstants.PEER_DECAY_TIME));
	}
}
