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
}
