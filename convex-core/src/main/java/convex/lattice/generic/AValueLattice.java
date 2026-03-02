package convex.lattice.generic;

import convex.core.data.ACell;
import convex.lattice.ALattice;

/**
 * Abstract base class for leaf lattices that merge singular values with no children.
 *
 * <p>Value lattices are terminal nodes in the lattice hierarchy — {@code path()}
 * always returns null. Subclasses define their own merge semantics, zero value,
 * and foreign value checks.</p>
 *
 * @param <V> Type of values in this lattice
 */
public abstract class AValueLattice<V extends ACell> extends ALattice<V> {

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return null;
	}
}
