package convex.core.util;

public abstract interface MergeFunction<V> {

	public abstract V merge(V a, V b);

	/**
	 * Merge with access to the map key. Default delegates to merge(a, b).
	 * Override to use the key for validation or context during merge.
	 *
	 * @param key The map key for the entry being merged
	 * @param a Value from the first map (null if absent)
	 * @param b Value from the second map (null if absent)
	 * @return Merged value
	 */
	public default V merge(Object key, V a, V b) {
		return merge(a, b);
	}

	/**
	 * Reverse a MergeFunction so that it can be applied with opposite ordering.
	 * This is useful for handling merge functions that are not commutative.
	 *
	 * @return A MergeFunction that merges the arguments in the reverse order.
	 */
	public default MergeFunction<V> reverse() {
		return new MergeFunction<V>() {
			public V merge(V a, V b) { return MergeFunction.this.merge(b, a); }
			public V merge(Object key, V a, V b) { return MergeFunction.this.merge(key, b, a); }
		};
	}
}
