package convex.core.data.prim;

import convex.core.data.ACell;

/**
 * Abstract base class for small CVM primitive values.
 * 
 * Primitives never contain Refs, are always embedded, and are always canonical
 */
public abstract  class APrimitive extends ACell {
	@Override
	public final boolean isCanonical() {
		return true;
	}

	@Override
	public final int getRefCount() {
		return 0;
	}
	
	@Override
	public final boolean isEmbedded() {
		return true;
	}
	
	public abstract long longValue();

}
