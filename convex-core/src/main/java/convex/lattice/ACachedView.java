package convex.lattice;

import convex.core.data.ACell;

/**
 * Abstract base class for cursor implementations that cache a value.
 * 
 * This class provides a framework for implementing different caching strategies
 * by delegating cache management to concrete subclasses. Subclasses must implement
 * the caching logic.
 * 
 * @param <V> The type of value being cached
 */
public abstract class ACachedView<V extends ACell> extends AView<V> {

	protected ACachedView(ACursor<V> source) {
		super(source);
	}


}
