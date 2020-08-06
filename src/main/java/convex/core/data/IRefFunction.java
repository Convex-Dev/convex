package convex.core.data;

/**
 * Functional interface for operations on Cell Refs that may throw a
 * MissingDataException
 *
 */
@FunctionalInterface
public interface IRefFunction {

	@SuppressWarnings("rawtypes")
	public Ref apply(Ref t);
}
