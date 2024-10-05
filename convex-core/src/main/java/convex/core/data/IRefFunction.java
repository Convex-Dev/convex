package convex.core.data;

/**
 * Functional interface for operations on Cell Refs that may throw a
 * MissingDataException
 *
 * In general, IRefFunction is used to provide a visitor for data objects containing nested Refs.
 */
@FunctionalInterface
public interface IRefFunction  {

	// Note we can't have a generic type parameter in a functional interface.
	// So using a wildcard seems the best option?
	
	@SuppressWarnings("rawtypes")
	public Ref apply(Ref t);
}
