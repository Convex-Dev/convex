package convex.core.util;

public abstract interface MergeFunction<V> {

	public abstract V merge(V a, V b);

	/**
	 * Reverse a MergeFunction so that it can be applied with opposite ordering.
	 * This is useful for handling merge functions that are not commutative.
	 * 
	 * @return A MergeFunction that merges the arguments in the reverse order.
	 */
	public default MergeFunction<V> reverse() {
		return (a, b) -> merge(b, a);
	}
}
