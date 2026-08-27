package convex.node;

import convex.core.data.ACell;

/**
 * Filter applied to a lattice value before outbound replication.
 *
 * <p>Returns the value with data outside the group's publication policy removed
 * or replaced. Filtering occurs before the owning propagator materialises,
 * announces or broadcasts its served view. It does not filter inbound
 * acquisition; excluded cells may therefore already exist in the serving store.</p>
 *
 * <p>Implementations must be idempotent: {@code filter(filter(v)) == filter(v)}.</p>
 *
 * @param <V> The lattice value type
 */
@FunctionalInterface
public interface LatticeFilter<V extends ACell> {

	/**
	 * Filters a lattice value before outbound replication.
	 *
	 * @param value full local lattice value
	 * @return filtered value suitable for replication
	 */
	V filter(V value);
}
