package convex.node;

import convex.core.data.ACell;

/**
 * Filter applied to a lattice value before outbound replication.
 *
 * <p>Returns the value with private/local-only data removed or nulled.
 * The full (unfiltered) value is persisted locally; only the filtered
 * value is announced and broadcast to peers.
 *
 * <p>Implementations must be idempotent: {@code filter(filter(v)) == filter(v)}.
 *
 * @param <V> The lattice value type
 */
@FunctionalInterface
public interface LatticeFilter<V extends ACell> {

	/**
	 * Filters a lattice value before outbound replication.
	 *
	 * @param value The full local lattice value
	 * @return The filtered value suitable for replication
	 */
	V filter(V value);
}
