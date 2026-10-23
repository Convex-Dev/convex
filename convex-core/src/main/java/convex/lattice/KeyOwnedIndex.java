package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.OwnerKeyLattice;

/**
 * An owned index whose owner key must equal the signing public key. Decoded
 * 32-byte blob keys are equivalent to AccountKey values; use the lattice's
 * resolveKey when extracting keys from decoded storage.
 */
public class KeyOwnedIndex<V extends ACell> extends OwnedIndex<AccountKey,V> {

	public KeyOwnedIndex(AForkableCursor<Index<AccountKey,SignedData<V>>> destination,ALattice<V> values) {
		super(OwnerKeyLattice.create(values),destination);
	}

	private KeyOwnedIndex(AOwnerLattice<AccountKey,V,Index<AccountKey,SignedData<V>>> owners,
			AForkableCursor<Index<AccountKey,SignedData<V>>> cursor) {
		super(owners,cursor);
	}

	@Override protected KeyOwnedIndex<V> rebind(AForkableCursor<Index<AccountKey,SignedData<V>>> cursor) {
		return new KeyOwnedIndex<>(getLattice(),cursor);
	}

	@Override public KeyOwnedIndex<V> fork() { return rebind(storage.fork()); }
}
