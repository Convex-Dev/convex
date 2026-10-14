package convex.lattice.generic;

import java.util.Objects;
import java.util.function.BiPredicate;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.Ownership;

/**
 * Shared machinery for maps of owner identities to signed values.
 *
 * <p>The verifier belongs to this lattice instance. It is retained when navigating
 * to an owner's signed child, and is used for both incoming merges and local
 * authoring. Contexts supply signing capabilities, not replacement verification
 * rules. Verifiers must be thread-safe and should use locally available authority
 * information rather than perform network work inside atomic updates.</p>
 *
 * @param <K> Owner identity type
 * @param <V> Unsigned value type
 * @param <M> Concrete map type
 */
public abstract class AOwnerLattice<K extends ACell, V extends ACell,
		M extends AMap<K, SignedData<V>>> extends ALattice<M> implements Ownership<V> {

	protected final SignedLattice<V> signedLattice;
	private final BiPredicate<? super K, ? super AccountKey> ownerVerifier;

	/** Creates a lattice with an explicit owner verification rule. */
	protected AOwnerLattice(ALattice<V> valueLattice,
			BiPredicate<? super K, ? super AccountKey> ownerVerifier) {
		this.signedLattice=SignedLattice.create(Objects.requireNonNull(valueLattice));
		this.ownerVerifier=Objects.requireNonNull(ownerVerifier);
	}

	/** Compatibility constructor for the legacy context-verified {@link OwnerLattice}. */
	protected AOwnerLattice(ALattice<V> valueLattice) {
		this.signedLattice=SignedLattice.create(Objects.requireNonNull(valueLattice));
		this.ownerVerifier=null;
	}

	/**
	 * Resolves a stored or canonical owner key, or returns null for an invalid key.
	 * Subclasses may normalise equivalent representations, such as decoded key blobs.
	 */
	@SuppressWarnings("unchecked")
	protected K ownerKey(ACell key) {
		return (K)key;
	}

	private boolean verifyOwner(ACell owner, AccountKey signer) {
		try {
			K key=ownerKey(owner);
			return key!=null && ownerVerifier.test(key,signer);
		} catch (ClassCastException e) {
			// Foreign keys need not have the Java type declared by the caller.
			return false;
		}
	}

	@Override
	public M merge(M ownValue, M otherValue) {
		return merge(LatticeContext.EMPTY,ownValue,otherValue);
	}

	@SuppressWarnings("unchecked")
	@Override
	public M merge(LatticeContext context, M ownValue, M otherValue) {
		if (otherValue==null) return ownValue;
		if (!checkForeign(otherValue)) throw new IllegalArgumentException("Invalid owner map");
		if (ownValue==null) ownValue=zero();
		MergeFunction<SignedData<V>> merger=new MergeFunction<>() {
			@Override
			public SignedData<V> merge(SignedData<V> own, SignedData<V> other) {
				throw new IllegalStateException("Owner merge requires a key");
			}

			@Override
			public SignedData<V> merge(Object key, SignedData<V> own, SignedData<V> other) {
				if (other==null) return own;
				if (!(key instanceof ACell cell)) return own;
				SignedLattice<V> child=signingFor(cell);
				return (child==null)?own:child.merge(context,own,other);
			}
		};
		// Both concrete containers retain their type and share unchanged subtrees.
		return (M)ownValue.mergeDifferences(otherValue,merger);
	}

	/**
	 * Returns the signing boundary for one owner's slot: the signed lattice bound to
	 * that owner and to this lattice's verification rule. A component crossing into
	 * the owner's value wraps the slot with it, via
	 * {@code SignedCursor.forOwner(slot, this, owner)}, so authoring uses exactly the
	 * rule applied to incoming merges.
	 *
	 * @param key Owner key, in any representation this lattice resolves
	 * @return Owner-bound signed lattice, or null if the key is not a valid owner
	 */
	@Override
	public SignedLattice<V> signingFor(ACell key) {
		K owner=ownerKey(key);
		if (owner==null) return null;
		return (ownerVerifier==null)?signedLattice.withOwner(owner)
			:signedLattice.withOwner(owner,this::verifyOwner);
	}

	/**
	 * Returns the lattice of the unsigned value inside every owner's envelope: the
	 * merge a component passes when forking a cursor over one owner's value.
	 *
	 * @return Value lattice shared by all owner slots
	 */
	public ALattice<V> getValueLattice() {
		return signedLattice.getValueLattice();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell key) {
		return (ALattice<T>)signingFor(key);
	}

	@Override
	public ACell resolveKey(ACell key) {
		return ownerKey(key);
	}
}
