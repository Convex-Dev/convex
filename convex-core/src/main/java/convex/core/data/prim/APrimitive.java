package convex.core.data.prim;

import convex.core.data.ACell;

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
	public int getRefCount() {
		return 0;
	}
	
	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override public final boolean isDataValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// Usually embedded and no child Refs, so memory size = 0
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
