package convex.java.asset;

import convex.core.cvm.Address;
import convex.java.Convex;

/**
 * Abstract base class for asset instances
 * @param <T> Type of asset quantity values
 */
public abstract class BaseAsset<T> {
	
	protected final Convex convex;
	
	protected BaseAsset(Convex convex) {
		this.convex=convex;
	}

	public abstract T getBalance();
	
	public abstract T getBalance(Address holder);
}
