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
import convex.lattice.LatticeContext;

/**
 * Lattice implementation for owner-based signed data.
 * 
 * The value is a Map from owner (ACell) to SignedData<V>, where each owner
 * has their own signed lattice value. The merge operation merges the maps,
 * and for each owner key, merges the SignedData values using SignedLattice
 * semantics.
 * 
 * Owner verification (checking that the signer key is authorised for the
 * owner) is performed during context-aware merge via LatticeContext.verifyOwner().
 * The checkForeign method only validates structure.
 *
 * <p>This one-parameter API retains its historical context-dependent behaviour,
 * including signature-only verification on the context-free merge. New applications
 * should use {@link OwnerMapLattice}, {@link OwnerIndexLattice} or
 * {@link OwnerKeyLattice} to bind verification to the lattice instance and enforce
 * it consistently at signed child paths.</p>
 * 
 * @param <V> Type of the underlying signed value
 */
public class OwnerLattice<V extends ACell> extends AOwnerLattice<ACell,V,AHashMap<ACell, SignedData<V>>> {
	
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
		super(valueLattice);
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
		// #561: never wholesale-accept a foreign map. Merge against an empty own so every
		// foreign entry is routed through the per-owner merge (signature check) rather than
		// being committed unvalidated. mergeDifferences visits only entries that differ, so
		// trusted own state is not re-scanned.
		if (ownValue == null) {
			ownValue = zero();
		}

		// Merge the maps using the SignedLattice merge function for each owner
		return ownValue.mergeDifferences(otherValue, mergeFunction);
	}

	@Override
	public AHashMap<ACell, SignedData<V>> merge(
			LatticeContext context,
			AHashMap<ACell, SignedData<V>> ownValue,
			AHashMap<ACell, SignedData<V>> otherValue) {
		if (otherValue == null) {
			return ownValue;
		}
		// #561: never wholesale-accept. Merge against an empty own so every foreign entry
		// passes through verifyOwner + the signature check below, instead of bypassing them
		// on the first (own==null) merge into an unpopulated region. mergeDifferences visits
		// only entries that differ, so trusted own state is not re-scanned.
		if (ownValue == null) {
			ownValue = zero();
		}

		// Merge the maps using context-aware SignedLattice merge for each owner,
		// with owner key verification via keyed merge
		MergeFunction<SignedData<V>> contextMergeFunction = new MergeFunction<>() {
			public SignedData<V> merge(SignedData<V> a, SignedData<V> b) {
				return signedLattice.merge(context, a, b);
			}
			public SignedData<V> merge(Object key, SignedData<V> a, SignedData<V> b) {
				ACell ownerKey=(key instanceof ACell cell)?cell:null;
				// Verify signer key is valid for this owner
				if (b != null && ownerKey != null) {
					if (!context.verifyOwner(ownerKey, b.getAccountKey())) {
						return a; // reject: signer not authorised for this owner
					}
				}
				// Owner-bound merge: a synthesised value is authored as this owner or
				// not at all, so the own value is retained rather than mis-signed
				return signedLattice.merge(context, ownerKey, a, b);
			}
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
		
		// Check that it's a valid HashMap (owner verification is done during merge)
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

	/**
	 * Legacy behaviour: any key is an owner, bound without an instance verifier so
	 * authoring falls back to the context's owner verification.
	 */
	@Override
	public SignedLattice<V> signingFor(ACell childKey) {
		return signedLattice.withOwner(childKey);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		// For OwnerLattice, child paths are owner keys
		// Each owner's value is a SignedData<V>, which uses SignedLattice
		// Return an owner-bound SignedLattice, so a write or a synthesised merge
		// below this key is authored as that owner or not at all
		return (ALattice<T>) signingFor(childKey);
	}

}
