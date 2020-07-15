package convex.core.lang;

/**
 * Interface for invokable objects with function interface.
 * 
 * "Any sufficiently advanced technology is indistinguishable from magic." -
 * Arthur C. Clarke
 * 
 * @param <T> Return type of function
 */
public interface IFn<T> {

	/**
	 * Invoke this function in the given context.
	 * 
	 * @param <I>
	 * @param context Context in which the function is to be executed
	 * @param args    Arguments to the function
	 * @return Context containing result of function invocation, or an exceptional
	 *         value
	 * @throws ExecutionException
	 */
	public abstract <I> Context<T> invoke(Context<I> context, Object[] args);

}
