package convex.lattice.generic;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.lattice.ALattice;

/**
 * Lattice implementation for owner-based signed data.
 *
 * <p>The value is a map from owner (any {@link ACell}) to {@code SignedData<V>}, where
 * each owner has their own signed lattice value. The merge operation merges the maps
 * and, for each owner key, merges the signed values at that owner's slot. The slot
 * verifies that the signer is authorised for the owner through the context's owner
 * verification, so merging a value at one slot means the same as merging it through
 * the map. {@link #checkForeign} only validates structure.</p>
 *
 * <p>This one-parameter API resolves owner authorisation from the context at merge
 * time, by {@link convex.lattice.LatticeContext#verifyOwner(ACell, convex.core.data.AccountKey)}:
 * without a context owner verifier, only owners that are account keys are accepted,
 * each signed by its own key. New applications should use {@link OwnerMapLattice},
 * {@link OwnerIndexLattice} or {@link OwnerKeyLattice} to bind the verification rule
 * to the lattice instance.</p>
 *
 * @param <V> Type of the underlying signed value
 */
public class OwnerLattice<V extends ACell> extends AOwnerLattice<ACell,V,AHashMap<ACell, SignedData<V>>> {

	/**
	 * Creates an OwnerLattice with the given child lattice for signed values
	 *
	 * @param valueLattice Lattice for the underlying value type V
	 */
	public OwnerLattice(ALattice<V> valueLattice) {
		super(valueLattice);
	}

	/**
	 * Creates an OwnerLattice with the given child lattice for signed values
	 *
	 * @param <V> Type of the underlying signed value
	 * @param valueLattice Lattice for the underlying value type V
	 * @return New OwnerLattice instance
	 */
	public static <V extends ACell> OwnerLattice<V> create(ALattice<V> valueLattice) {
		return new OwnerLattice<>(valueLattice);
	}

	@Override
	public AHashMap<ACell, SignedData<V>> zero() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<ACell, SignedData<V>> value) {
		// Structure only: each owner's slot verifies its signer during merge
		return value instanceof AHashMap;
	}

	@Override
	public ACell resolveKey(ACell key) {
		if (key instanceof ABlob) return key;
		if (key instanceof AString s) {
			return Blob.parse(s.toString());
		}
		return key;
	}
}
