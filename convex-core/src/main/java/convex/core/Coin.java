package convex.core;

/**
 * Static Constants for Coin sizes and total supply
 * 
 * These denominations are intended to provide more sensible human-scale units for better understanding.
 * They have no effect on CVM behaviour.
 */
public class Coin {
	/**
	 * Copper coin, the lowest (indivisible) denomination.
	 */
	public static final long COPPER=1L;
	
	/**
	 * Copper coin, a denomination for small change/ Equal to 1000 Copper
	 */
	public static final long BRONZE=1000*COPPER;
	
	/**
	 * Silver Coin, a denomination for small payments. Equal to 1000 Bronze
	 */
	public static final long SILVER=1000*BRONZE;
	
	/**
	 * A denomination suitable for medium/large payments. Equal to 1000 Silver, and divisible into one billion copper coins.
	 * 
	 * Intended as the primary "human scale" quantity of Convex Coins in regular usage.
	 */
	public static final long GOLD=1000*SILVER;
	
	/**
	 * A large denomination. 1000 Gold.
	 */
	public static final long DIAMOND=1000*GOLD;
	
	/**
	 * A massively valuable amount of Convex Coins. One million Gold.
	 */
	public static final long EMERALD=1000*DIAMOND;
	
	/**
	 * The maximum Convex Coin supply limit. One billion Gold Coins. In practice, the actual supply 
	 * will be less than this.
	 */
	public static final long MAX_SUPPLY=1000*EMERALD;

	/**
	 * A zero quantity of coins, the minimum possible
	 */
	public static final long ZERO = 0;

	/**
	 * Number of decimals in Convex coin quantities
	 */
	public static final int DECIMALS = 9;

	/**
	 * Check if an amount is valid quantity of Convex Coins 
	 * @param amount Amount to test
	 * @return true if valid, false otherwsie
	 */
	public static boolean isValidAmount(long amount) {
		return (amount >=0) &&(amount <=Coin.MAX_SUPPLY) ;
	}
}
