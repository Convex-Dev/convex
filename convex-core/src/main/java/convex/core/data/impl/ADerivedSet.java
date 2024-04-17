package convex.core.data.impl;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;

/**
 * Abstract base class for non-canonical sets derived off maps
 * 
 * Useful for cases where we want a Set instance, but don't want to construct a whole new data structure
 * 
 * @param <T> Type of set element
 * @param <K> Type of Map keys
 * @param <V> Type of Map values
 */
public abstract class ADerivedSet<T extends ACell, K extends ACell, V extends ACell> extends ASet<T> {
	protected AHashMap<K,V> map;

	protected ADerivedSet(AHashMap<K,V> map) {
		super(map.count());
		this.map=map;
	}

	@Override
	public boolean isCanonical() {
		// We aren't a canonical Set
		return false;
	}


}
