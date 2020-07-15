package convex.core.lang;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.IRefContainer;
import convex.core.data.Syntax;

/**
 * Base class for functions expressed as values
 * 
 * "You know what's web-scale? The Web. And you know what it is? Dynamically
 * typed." - Stuart Halloway
 *
 * @param <T> Return type of functions.
 */
public abstract class AFn<T> extends ACell implements IFn<T>, IRefContainer {

	/**
	 * Returns the declared param names for a function, or null if not available.
	 * 
	 * @return A binding vector describing the parameters for this function
	 */
	public abstract AVector<Syntax> getParams();

}
