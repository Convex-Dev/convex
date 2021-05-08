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
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	public abstract long longValue();
	
	
	public abstract double doubleValue();
	




}
