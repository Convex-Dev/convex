package convex.core.cvm;

import convex.core.data.ACell;

/**
 * Interface for invokable objects with function interface.
 * 
 * "Any sufficiently advanced technology is indistinguishable from magic." -
 * Arthur C. Clarke
 * 
 * @param <T> Return type of function
 */
public interface IFn<T extends ACell> {

	/**
	 * Invoke this function in the given context.
	 * 
	 * @param context Context in which the function is to be executed
	 * @param args    Arguments to the function
	 * @return Context containing result of function invocation, or an exceptional
	 *         value
	 */
	public abstract Context invoke(Context context, ACell[] args);

}
