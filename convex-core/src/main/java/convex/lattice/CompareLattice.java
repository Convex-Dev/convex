package convex.lattice;

import java.util.Comparator;

import convex.core.data.ACell;

/**
 * Lattice node implementing a comparison function.
 * 
 * Suitable for lattice values where "greater" values replace previous ones, 
 * e.g. taking a value with a more recent timestamp 
 * 
 * @param <V> Type of lattice values
 */
public class CompareLattice<V extends ACell> extends ALattice<V> {

	private Comparator<V> comparator;

	private CompareLattice(Comparator<V> comparator) {
		this.comparator = comparator;
	}
	
	public static <V extends ACell> CompareLattice<V> create(Comparator<V> comparator) {
		return new CompareLattice<>(comparator);
	}
	
	@Override
	public V merge(V ownValue, V otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		
		// retain own value if other value is lesser or equal
		if (comparator.compare(ownValue, otherValue)>=0) return ownValue;
		
		return otherValue;
	}

	@Override
	public V zero() {
		return null;
	}

	@Override
	public boolean checkForeign(V value) {
		if (value==null) return false;
		return true;
	}

}
