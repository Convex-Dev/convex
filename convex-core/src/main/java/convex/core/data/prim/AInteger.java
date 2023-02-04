package convex.core.data.prim;

/**
 * Abstract base class for CVM Integer values
 */
public abstract class AInteger extends ANumeric {
 
	@Override
	public abstract boolean isCanonical();

	/**
	 * Increments this Integer
	 * @return Incremented value
	 */
	public abstract AInteger inc();
	
	
	/**
	 * Decrements this Integer
	 * @return Decremented value
	 */
	public abstract AInteger dec();
	
	/**
	 * Parse an integer value as a canonical value
	 * @param s
	 * @return AInteger instance
	 */
	public static AInteger parse(String s) {
		int n=s.length();
		if (n<19) return CVMLong.parse(s); // can't be a big integer
		if (n>20) return CVMBigInteger.parse(s); // can't be a long
				
		try {	
			return CVMLong.parse(s);
		} catch (Throwable t) {
			return CVMBigInteger.parse(s);
		}
	}
}
