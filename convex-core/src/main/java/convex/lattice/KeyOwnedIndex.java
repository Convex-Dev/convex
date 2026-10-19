package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.SignedData;
import convex.lattice.cursor.ACursor;
import convex.lattice.generic.OwnerKeyLattice;

/**
 * An owned index whose owner key must equal the signing public key. Decoded
 * 32-byte blob keys are equivalent to AccountKey values; use the lattice's
 * resolveKey when extracting keys from decoded storage.
 */
public class KeyOwnedIndex<V extends ACell> extends OwnedIndex<AccountKey,V> {

	public KeyOwnedIndex(ACursor<Index<AccountKey,SignedData<V>>> destination,ALattice<V> values) {
		super(OwnerKeyLattice.create(values),destination);
	}
}
