package convex.lattice.generic;

import java.util.function.BiPredicate;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.lattice.ALattice;

/**
 * Index-backed owner lattice with an instance-bound verifier. Supports strings
 * (including DIDs), blobs and other Index keys, using Index's blob-based equality.
 *
 * @param <K> Owner identity type
 * @param <V> Unsigned value type
 */
public class OwnerIndexLattice<K extends ABlobLike<?>,V extends ACell>
		extends AOwnerLattice<K,V,Index<K,SignedData<V>>> {

	public OwnerIndexLattice(ALattice<V> valueLattice,
			BiPredicate<? super K, ? super AccountKey> verifier) {
		super(valueLattice,verifier);
	}

	/** Creates an owner index with the supplied authorisation rule. */
	public static <K extends ABlobLike<?>,V extends ACell> OwnerIndexLattice<K,V> create(
			ALattice<V> valueLattice, BiPredicate<? super K, ? super AccountKey> verifier) {
		return new OwnerIndexLattice<>(valueLattice,verifier);
	}

	@Override
	public Index<K,SignedData<V>> zero() {
		return Index.none();
	}

	@Override
	public boolean checkForeign(Index<K,SignedData<V>> value) {
		return value instanceof Index;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected K ownerKey(ACell key) {
		return (key instanceof ABlobLike<?>)?(K)key:null;
	}
}
