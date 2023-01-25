package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.Ref;
import convex.core.data.RefDirect;

/**
 * Abstract base class for CVM primitive values.
 * 
 */
public abstract  class APrimitive extends ACell {
	@Override
	public boolean isCanonical() {
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
	 * @return Java long value representing this primitive CVM value
	 */
	public abstract long longValue();
	
	
	/**
	 * @return Java double value representing this primitive CVM value
	 */
	public abstract double doubleValue();
	
	@Override
	public ACell toCanonical() {
		// Always canonical, probably?
		return this;
	}



}
