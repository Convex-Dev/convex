package convex.core.cvm;

import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.data.Tag;
import convex.core.data.type.AType;
import convex.core.data.type.Types;

/**
 * Base class for functions expressed as values
 * 
 * "You know what's web-scale? The Web. And you know what it is? Dynamically
 * typed." - Stuart Halloway
 *
 * @param <T> Return type of functions.
 */
public abstract class AFn<T extends ACell> extends ACVMCode implements IFn<T> {
	
	@Override
	public abstract Context invoke(Context context, ACell[] args);
	
	@Override
	public abstract AFn<T> updateRefs(IRefFunction func);
	
	@Override
	public final AType getType() {
		return Types.FUNCTION;
	}

	/**
	 * Tests if this function supports the given argument list
	 * 
	 * By default, checks if the function supports the given arity only.
	 * 
	 * TODO: intention is to override this to include dynamic type checks etc.
	 * @param args Array of arguments
	 * @return true if function supports the specified args array
	 */
	public boolean supportsArgs(ACell[] args) {
		return hasArity(args.length);
	}
	
	/**
	 * Tests if this function supports the given arity.
	 * @param arity Arity to check
	 * @return true if function supports the given arity, false otherwise
	 */
	public abstract boolean hasArity(int arity);
	
	@Override
	public byte getTag() {
		return Tag.FN;
	}
	
	
	@Override
	public boolean equals(ACell o) {
		if (!(o instanceof AFn)) return false;
		return ACell.genericEquals(this, o);
	}

}
