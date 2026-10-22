package convex.lattice.generic;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.BiFunction;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.util.MergeFunction;
import convex.lattice.ALattice;

/**
 * Shared machinery for maps of owner identities to signed values: a union of owners
 * where each slot merges with the signed lattice bound to that owner, which
 * {@link #path} returns.
 *
 * <p>The verifier belongs to this lattice instance. It is retained when navigating
 * to an owner's signed child, and is used for both incoming merges and local
 * authoring. Explicit signers (or legacy contexts) supply capabilities, not
 * replacement verification rules. Verifiers must be thread-safe and use available authority
 * information rather than perform network work inside atomic updates.</p>
 *
 * @param <K> Owner identity type
 * @param <V> Unsigned value type
 * @param <M> Concrete map type
 */
public abstract class AOwnerLattice<K extends ACell, V extends ACell,
		M extends AMap<K, SignedData<V>>> extends AUnionLattice<K,SignedData<V>,M> {

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

	/**
	 * Returns the signing boundary for one owner's slot: the signed lattice bound to
	 * that owner and to this lattice's verification rule. A component crossing into
	 * the owner's value binds this rule to its signing policy before wrapping the slot,
	 * so authoring uses exactly the rule applied to incoming merges.
	 *
	 * @param key Owner key, in any representation this lattice resolves
	 * @return Owner-bound signed lattice, or null if the key is not a valid owner
	 */
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

	/**
	 * Merges with an explicit authoring capability for synthesised owner values.
	 * Unchanged and selected envelopes retain their signatures. The signer may
	 * return null for an owner it cannot author; that slot keeps its established
	 * envelope. Every result is checked by the same owner-bound rule as delivery.
	 * Callbacks run inside callers' atomic updates and must be safe to retry.
	 */
	@SuppressWarnings({"unchecked","rawtypes"})
	public M mergeAndSign(M own,M other,BiFunction<? super K,V,SignedData<V>> signer) {
		Objects.requireNonNull(signer);
		if (other==null) return own;
		if (!checkForeign(other)) throw new IllegalArgumentException("Invalid owner map");
		if (own==null) own=zero();
		// Keep foreign entries untyped until their envelope representation is checked.
		return (M)((AMap)own).mergeDifferences(other,new MergeFunction<ACell>() {
			@Override public ACell merge(ACell a,ACell b) {
				throw new IllegalStateException("Owner merge requires the entry key");
			}
			@Override public ACell merge(Object key,ACell a,ACell b) {
				if (!(key instanceof ACell owner) || !(b instanceof SignedData<?>) || !checkEntry(owner,b)) return a;
				SignedLattice<V> rule=signingFor(owner);
				if (rule==null) return a;
				return rule.mergeAndSign((SignedData<V>)a,(SignedData<V>)b,
					value -> signer.apply((K)rule.getOwner(),value));
			}
		});
	}

	@Override
	protected boolean checkEntry(ACell key,ACell value) {
		return value instanceof SignedData<?>;
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
