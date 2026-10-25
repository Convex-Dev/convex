package convex.lattice;

import java.util.function.BiPredicate;

import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.OwnerIndexLattice;

/** An index-backed owned collection, for strings, blobs and other index keys. */
public class OwnedIndex<K extends ABlobLike<?>,V extends ACell> extends AOwnedMap<K,V,Index<K,SignedData<V>>> {

	/** The required verifier must be thread-safe and use locally available authority data. */
	public OwnedIndex(AForkableCursor<Index<K,SignedData<V>>> destination,
			ALattice<V> values,BiPredicate<? super K,? super AccountKey> verifier) {
		this(OwnerIndexLattice.create(values,verifier),destination);
	}

	/** Constructor for specialisations with a canonical owner-key representation. */
	protected OwnedIndex(AOwnerLattice<K,V,Index<K,SignedData<V>>> owners,AForkableCursor<Index<K,SignedData<V>>> destination) {
		super(owners,destination);
	}

	@Override protected OwnedIndex<K,V> rebind(AForkableCursor<Index<K,SignedData<V>>> cursor) {
		return new OwnedIndex<>(getLattice(),cursor);
	}

	@Override public OwnedIndex<K,V> fork() { return rebind(storage.fork()); }
}
