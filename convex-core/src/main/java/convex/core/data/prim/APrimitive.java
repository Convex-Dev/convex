package convex.core.data.prim;

import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;

/**
 * Abstract base class for CVM primitive values.
 * 
 * We don't use JVM primitives directly in CVM data structures because these primitives need to inherit 
 * the basic ACell functionality, but these are otherwise relatively lightweight wrappers over the
 * underlying JVM types.
 * 
 */
public abstract  class APrimitive extends ACell {
	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override
	public boolean isEmbedded() {
		return true;
	}
	
	@Override public boolean isCVMValue() {
		return true;
	}
	
	@Override
	protected long calcMemorySize() {	
		// Usually embedded and no child Refs, so memory size = 0
		return 0;
	}
	
	/**
	 * @return Java long value representing this primitive CVM value. Essentially equivalent to JVM cast.
	 */
	public abstract long longValue();
	
	
	/**
	 * @return Java double value representing this primitive CVM value
	 */
	public abstract double doubleValue();
	
	@Override
	public ACell toCanonical() {
		// We expect primitives to be canonical, though implementations may override this
		return this;
	}

	// Primitives have no Refs
	
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException(i);
	}

	@Override
	public final ACell updateRefs(IRefFunction func) {
		return this;
	}
	
	@Override
	public final int getRefCount() {
		// No Refs - primitives are always a leaf cell!
		return 0;
	}
	
	@Override
	public final int getBranchCount() {
		// Never any branches, even for Big Integer
		return 0;
	}

}
