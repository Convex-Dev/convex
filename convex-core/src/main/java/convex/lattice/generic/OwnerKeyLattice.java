package convex.lattice.generic;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.lattice.ALattice;

/**
 * Index-backed public-key namespaces. Every owner's value must be signed by that
 * exact key. Decoded 32-byte blobs are accepted as public-key representations.
 *
 * <p>{@code AccountKey} has the same wire representation as a blob. The backing
 * index may therefore contain ordinary blob keys after decoding, despite its
 * Java type parameter. Use {@link AccountKey#create(ABlob)} to obtain an AccountKey
 * from an extracted key rather than casting it. Merge retains structural sharing and
 * does not rebuild the index to replace equivalent key objects.</p>
 *
 * @param <V> Unsigned value type
 */
public final class OwnerKeyLattice<V extends ACell> extends OwnerIndexLattice<AccountKey,V> {

	public OwnerKeyLattice(ALattice<V> valueLattice) {
		super(valueLattice,AccountKey::equals);
	}

	public static <V extends ACell> OwnerKeyLattice<V> create(ALattice<V> valueLattice) {
		return new OwnerKeyLattice<>(valueLattice);
	}

	@Override
	protected AccountKey ownerKey(ACell key) {
		return (key instanceof ABlob blob)?AccountKey.create(blob):null;
	}
}
