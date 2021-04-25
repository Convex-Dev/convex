package convex.core.lang;

import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.data.Tag;

/**
 * Base class for functions expressed as values
 * 
 * "You know what's web-scale? The Web. And you know what it is? Dynamically
 * typed." - Stuart Halloway
 *
 * @param <T> Return type of functions.
 */
public abstract class AFn<T extends ACell> extends ACell implements IFn<T> {
	
	@Override
	public abstract Context<T> invoke(Context<ACell> context, ACell[] args);
	
	@Override
	public abstract AFn<T> updateRefs(IRefFunction func);

	/**
	 * Tests if this function supports the given argument list
	 * 
	 * By default, checks if the function supports the given arity only.
	 * 
	 * TODO: intention is to override this to include dynamic type checks etc.
	 */
	public boolean supportsArgs(Object[] args) {
		return hasArity(args.length);
	}
	
	/**
	 * Tests if this function supports the given arity.
	 */
	public abstract boolean hasArity(int n);
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	@Override
	public byte getTag() {
		return Tag.FN;
	}
}
