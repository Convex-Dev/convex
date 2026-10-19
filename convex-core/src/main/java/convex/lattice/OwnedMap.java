package convex.lattice;

import java.util.function.BiPredicate;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.lattice.cursor.ACursor;
import convex.lattice.generic.OwnerMapLattice;

/** A hash-map-backed owned collection, with a required owner authorisation rule. */
public class OwnedMap<K extends ACell,V extends ACell> extends AOwnedMap<K,V,AHashMap<K,SignedData<V>>> {

	/** The verifier must be thread-safe and use locally available authority data. */
	public OwnedMap(ACursor<AHashMap<K,SignedData<V>>> destination,
			ALattice<V> values,BiPredicate<? super K,? super AccountKey> verifier) {
		super(OwnerMapLattice.create(values,verifier),destination);
	}
}
