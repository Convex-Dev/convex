package convex.lattice.kad;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.exceptions.TODOException;
import convex.lattice.ALattice;

/**
 * Lattice implementing the Kademlia routing algorithm.
 *
 * Lattice values are a Vector of Buckets, one for each bit of distance.
 *
 * Buckets are an Index of Keys to Entries.
 *
 * Entries are a Vector:
 * - 0 = Key
 * - 1 = Location data
 * - 2 = Last seen timestamp
 *
 * <p><b>Not yet implemented.</b> Every operation throws {@link TODOException}
 * rather than returning a placeholder — a stub {@code merge} that returned null
 * would silently destroy lattice state, so this fails loudly until the routing
 * merge is designed and built.</p>
 */
public class KadLattice extends ALattice<AVector<?>> {

	@Override
	public AVector<?> merge(AVector<?> ownValue, AVector<?> otherValue) {
		throw new TODOException("KadLattice merge not yet implemented");
	}

	@Override
	public AVector<?> zero() {
		throw new TODOException("KadLattice zero not yet implemented");
	}

	@Override
	public boolean checkForeign(AVector<?> value) {
		throw new TODOException("KadLattice checkForeign not yet implemented");
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		throw new TODOException("KadLattice path not yet implemented");
	}

}
