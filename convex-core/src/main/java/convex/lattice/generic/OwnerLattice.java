package convex.lattice.generic;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;

/**
 * Lattice implementation for owner-based signed data.
 * 
 * The value is a Map from owner (ACell) to SignedData<V>, where each owner
 * has their own signed lattice value. The merge operation merges the maps,
 * and for each owner key, merges the SignedData values using SignedLattice
 * semantics.
 * 
 * Signature validation for owners will be looked up later, so checkForeign
 * is lenient and only checks the structure.
 * 
 * @param <V> Type of the underlying signed value
 */
public class OwnerLattice<V extends ACell> extends ALattice<AHashMap<ACell, SignedData<V>>> {

	/**
	 * The child lattice for signed values
	 */
	protected final SignedLattice<V> signedLattice;
	
	/**
	 * Merge function for SignedData values using SignedLattice
	 */
	protected final MergeFunction<SignedData<V>> mergeFunction;

	/**
	 * Creates an OwnerLattice with the given child lattice for signed values
	 * 
	 * @param valueLattice Lattice for the underlying value type V
	 */
	public OwnerLattice(ALattice<V> valueLattice) {
		this.signedLattice = SignedLattice.create(valueLattice);
		this.mergeFunction = (a, b) -> {
			return signedLattice.merge(a, b);
		};
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
	public AHashMap<ACell, SignedData<V>> merge(
			AHashMap<ACell, SignedData<V>> ownValue,
			AHashMap<ACell, SignedData<V>> otherValue) {
		if (otherValue == null) {
			return ownValue;
		}
		if (ownValue == null) {
			if (checkForeign(otherValue)) {
				return otherValue;
			}
			return zero();
		}

		// Merge the maps using the SignedLattice merge function for each owner
		return ownValue.mergeDifferences(otherValue, mergeFunction);
	}

	@Override
	public AHashMap<ACell, SignedData<V>> merge(
			convex.lattice.LatticeContext context,
			AHashMap<ACell, SignedData<V>> ownValue,
			AHashMap<ACell, SignedData<V>> otherValue) {
		if (otherValue == null) {
			return ownValue;
		}
		if (ownValue == null) {
			if (checkForeign(otherValue)) {
				return otherValue;
			}
			return zero();
		}

		// Merge the maps using context-aware SignedLattice merge for each owner
		MergeFunction<SignedData<V>> contextMergeFunction = (a, b) -> {
			return signedLattice.merge(context, a, b);
		};

		return ownValue.mergeDifferences(otherValue, contextMergeFunction);
	}

	@Override
	public AHashMap<ACell, SignedData<V>> zero() {
		return Maps.empty();
	}

	@Override
	public boolean checkForeign(AHashMap<ACell, SignedData<V>> value) {
		if (value == null) {
			return false;
		}
		
		// Check that it's a valid HashMap
		// TODO: Signature validation for specific owners will be looked up later,
		// so we only check the structure here
		return (value instanceof AHashMap);
	}

	@Override
	public ACell resolveKey(ACell key) {
		if (key instanceof ABlob) return key;
		if (key instanceof AString s) {
			return Blob.parse(s.toString());
		}
		return key;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// For OwnerLattice, child paths are owner keys
		// Each owner's value is a SignedData<V>, which uses SignedLattice
		// Return the SignedLattice for child nodes
		return (ALattice<T>) signedLattice;
	}

}
