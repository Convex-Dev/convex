package convex.lattice.generic;

import java.util.function.BiPredicate;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.ALattice;

/**
 * Hash-map-backed owner lattice with an instance-bound verifier.
 *
 * @param <K> Owner identity type, for example a DID string or account address
 * @param <V> Unsigned value type
 */
public class OwnerMapLattice<K extends ACell, V extends ACell>
		extends AOwnerLattice<K,V,AHashMap<K,SignedData<V>>> {

	public OwnerMapLattice(ALattice<V> valueLattice,
			BiPredicate<? super K, ? super AccountKey> verifier) {
		super(valueLattice,verifier);
	}

	/** Creates a typed owner map with the supplied authorisation rule. */
	public static <K extends ACell,V extends ACell> OwnerMapLattice<K,V> create(
			ALattice<V> valueLattice, BiPredicate<? super K, ? super AccountKey> verifier) {
		return new OwnerMapLattice<>(valueLattice,verifier);
	}

	@Override
	public AHashMap<K,SignedData<V>> zero() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<K,SignedData<V>> value) {
		return value instanceof AHashMap;
	}
}
