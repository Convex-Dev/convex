package convex.lattice.kad;

import convex.core.data.AVector;
import convex.lattice.ALattice;

/**
 * Lattice implementing that Kademlia routing algorithm
 * 
 * Lattice values are a Vector of Buckets, one for each bit of distance
 * 
 * Buckets are an Index of Keys to Entries
 * 
 * Entries are a Vector:
 * - 0 = Key
 * - 1 = Location data
 * - 2 = Last seen timestamp
 */
public class KadLattice extends ALattice<AVector<?>> {

	@Override
	public AVector<?> merge(AVector<?> ownValue, AVector<?> otherValue) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public AVector<?> zero() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean checkForeign(AVector<?> value) {
		// TODO Auto-generated method stub
		return false;
	}

}
