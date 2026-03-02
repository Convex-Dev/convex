package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.lattice.ALattice;
import convex.lattice.generic.IndexLattice;

/**
 * Top-level lattice for the KV store.
 *
 * Wraps an IndexLattice&lt;AString, AVector&lt;ACell&gt;&gt; where each key maps to
 * a KVEntry (value vector), merged using KVEntryLattice.
 *
 * The Index provides lexicographic ordering on keys.
 */
public class KVStoreLattice extends ALattice<Index<AString, AVector<ACell>>> {

	public static final KVStoreLattice INSTANCE = new KVStoreLattice();

	private final IndexLattice<AString, AVector<ACell>> delegate;

	private KVStoreLattice() {
		this.delegate = IndexLattice.create(KVEntryLattice.INSTANCE);
	}

	@Override
	public Index<AString, AVector<ACell>> merge(Index<AString, AVector<ACell>> ownValue, Index<AString, AVector<ACell>> otherValue) {
		return delegate.merge(ownValue, otherValue);
	}

	@Override
	public Index<AString, AVector<ACell>> zero() {
		return delegate.zero();
	}

	@Override
	public boolean checkForeign(Index<AString, AVector<ACell>> value) {
		return delegate.checkForeign(value);
	}

	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		return delegate.path(childKey);
	}
}
