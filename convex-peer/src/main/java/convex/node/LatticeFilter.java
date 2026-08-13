package convex.node;

import convex.core.data.ACell;

/**
 * Filter applied to a lattice value before outbound replication.
 *
 * <p>Returns the value with private/local-only data removed or nulled. Filtering
 * occurs before the owning propagator announces, persists or broadcasts its outbound
 * view. It does not filter inbound acquisition; such cells may already exist in the
 * propagator's store even when they are excluded from its advertised root.
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
