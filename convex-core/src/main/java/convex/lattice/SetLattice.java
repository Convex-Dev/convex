package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Sets;

/**
 * Lattice for set values that grow via set union
 * 
 * @param <V> Type of set values
 */
public class SetLattice<V extends ACell> extends ALattice<ASet<V>> {

	private SetLattice() {
		// private to enforce Singleton
	}
	
	private static final SetLattice<?> INSTANCE = new SetLattice<>();

	@Override
	public ASet<V> merge(ASet<V> ownValue, ASet<V> otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return ownValue.includeAll(otherValue);
	}

	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLattice<V> create() {
		return (SetLattice<V>) INSTANCE;
	}

	@Override
	public ASet<V> zero() {
		return Sets.empty();
	}

	@Override
	public boolean checkForeign(ASet<V> value) {
		return (value instanceof ASet);
	}

}
