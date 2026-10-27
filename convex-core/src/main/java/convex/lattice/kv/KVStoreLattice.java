package convex.lattice.kv;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Index;
import convex.lattice.generic.ADelegatingLattice;
import convex.lattice.generic.IndexLattice;

/**
 * Top-level lattice for the KV store.
 *
 * <p>A thin {@link ADelegatingLattice} over
 * {@code IndexLattice<AString, AVector<ACell>>}, where each key maps to a KVEntry
 * (value vector) merged by {@link KVEntryLattice}. The Index provides lexicographic
 * key ordering; merge (context-threaded), navigation and structure are inherited by
 * delegation to the inner lattice.</p>
 */
public class KVStoreLattice extends ADelegatingLattice<Index<AString, AVector<ACell>>> {

	public static final KVStoreLattice INSTANCE = new KVStoreLattice();

	private KVStoreLattice() {
		super(IndexLattice.create(KVEntryLattice.INSTANCE));
	}
}
