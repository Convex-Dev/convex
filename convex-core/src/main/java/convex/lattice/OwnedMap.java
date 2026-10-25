package convex.lattice;

import java.util.function.BiPredicate;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.OwnerMapLattice;

/** A hash-map-backed owned collection, with a required owner authorisation rule. */
public class OwnedMap<K extends ACell,V extends ACell> extends AOwnedMap<K,V,AHashMap<K,SignedData<V>>> {

	/** The verifier must be thread-safe and use locally available authority data. */
	public OwnedMap(AForkableCursor<AHashMap<K,SignedData<V>>> destination,
			ALattice<V> values,BiPredicate<? super K,? super AccountKey> verifier) {
		this(OwnerMapLattice.create(values,verifier),destination);
	}

	/** Binds storage to an existing owner rule, shared by delivery and local authoring. */
	public OwnedMap(AOwnerLattice<K,V,AHashMap<K,SignedData<V>>> owners,
			AForkableCursor<AHashMap<K,SignedData<V>>> cursor) {
		super(owners,cursor);
	}

	@Override protected OwnedMap<K,V> rebind(AForkableCursor<AHashMap<K,SignedData<V>>> cursor) {
		return new OwnedMap<>(getLattice(),cursor);
	}

	@Override public OwnedMap<K,V> fork() { return rebind(storage.fork()); }
}
