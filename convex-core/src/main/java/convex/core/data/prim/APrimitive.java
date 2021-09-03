package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.RefDirect;

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
	@SuppressWarnings("unchecked")
	protected <R extends ACell> Ref<R> createRef() {
		// Create Ref at maximum status to reflect internal embedded nature
		Ref<ACell> newRef= RefDirect.create(this,cachedHash(),Ref.INTERNAL_FLAGS);
		cachedRef=newRef;
		return (Ref<R>) newRef;
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
	
	/**
	 * @return long value representing primitive
	 */
	public abstract long longValue();
	
	
	/**
	 * @return double value representing primitive
	 */
	public abstract double doubleValue();
	
	@Override
	public ACell toCanonical() {
		// Always canonical, probably?
		return this;
	}



}
