package convex.core.lattice;

import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Sets;

/**
 * Lattice node for set values that grow via set union
 * 
 * @param <V> Type of set values
 */
public class SetNode<V extends ACell> extends ALattice<ASet<V>> {

	private static final SetNode<?> INSTANCE = new SetNode<>();

	@Override
	public ASet<V> merge(ASet<V> ownValue, ASet<V> otherValue) {
		if (otherValue==null) return ownValue;
		if (ownValue==null) return otherValue;
		return ownValue.includeAll(otherValue);
	}

	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetNode<V> create() {
		return (SetNode<V>) INSTANCE;
	}

	@Override
	public ASet<V> zero() {
		return Sets.empty();
	}

}
