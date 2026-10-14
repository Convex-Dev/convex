package convex.lattice.generic;

import java.util.Objects;
import java.util.function.BiPredicate;

import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.SignedData;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Lattice node representing signed Data.
 *
 * <p>A merge that <em>selects</em> one of its inputs needs no signature: the winner
 * is stored with the signature it arrived with. A merge that <em>synthesises</em> a
 * new value (because the inner lattice combined both inputs) must sign the result as
 * this node's owner. When the context signing policy cannot do that — the usual case
 * for another owner's data — the merge keeps the own value rather than producing an
 * entry no peer would accept. Convergence for that owner is then the owner's to
 * complete; see the class documentation on {@link ALattice} for why a merge must not
 * fail on data it merely cannot author.</p>
 *
 * <p>An instance-bound owner verifier, when present, checks incoming signers and
 * authoring independently of legacy context owner verification. The same rule is
 * retained by the unsigned write cursor below this signing boundary.</p>
 *
 * @param <V> Type of signed lattice value
 */
public class SignedLattice<V extends ACell> extends ALattice<SignedData<V>> {


	protected final ALattice<V> valueNode;

	/** Owner identity for values at this position, or null when unknown. */
	private final ACell owner;
	private final BiPredicate<ACell,AccountKey> ownerVerifier;

	private SignedLattice(ALattice<V> valueNode, ACell owner) {
		this(valueNode,owner,null);
	}

	private SignedLattice(ALattice<V> valueNode, ACell owner,
			BiPredicate<ACell,AccountKey> ownerVerifier) {
		this.valueNode=valueNode;
		this.owner=owner;
		this.ownerVerifier=ownerVerifier;
	}

	public static <V extends ACell> SignedLattice<V> create(ALattice<V> childNode) {
		return new SignedLattice<>(childNode,null);
	}

	/**
	 * Returns a view bound to a specific owner identity, whose signer the context
	 * signing policy is asked for when a value must be authored at this position.
	 *
	 * @param owner Owner identity. An existing bound verifier is retained; otherwise
	 *              authoring uses the legacy context owner verifier.
	 * @return Owner-bound view of this lattice
	 */
	public SignedLattice<V> withOwner(ACell owner) {
		if (Utils.equals(owner,this.owner)) return this;
		return new SignedLattice<>(valueNode,owner,ownerVerifier);
	}

	/**
	 * Binds an owner and its verification rule to this lattice, independently of
	 * the context's legacy owner verifier. The rule follows child cursor writes.
	 *
	 * @param owner Owner identity
	 * @param verifier Rule authorising a signer for an owner
	 * @return Owner-bound signed lattice
	 */
	public SignedLattice<V> withOwner(ACell owner, BiPredicate<ACell,AccountKey> verifier) {
		return new SignedLattice<>(valueNode,Objects.requireNonNull(owner),Objects.requireNonNull(verifier));
	}

	/** Returns the lattice for the unsigned value. */
	public ALattice<V> getValueLattice() {
		return valueNode;
	}

	/**
	 * Authors this owner's value with an available authorised signer, or returns
	 * null. A bound verifier takes precedence over legacy context verification.
	 */
	public SignedData<V> sign(LatticeContext context, V value) {
		return sign(context,owner,value);
	}

	private SignedData<V> sign(LatticeContext context, ACell requestedOwner, V value) {
		if (ownerVerifier==null) return context.signAs(requestedOwner,value);
		// A bound instance's owner cannot be replaced by a per-call merge argument.
		AccountKey direct=LatticeContext.ownerAccountKey(owner);
		SignedData<V> signed=context.sign(direct,value);
		if (signed!=null && ownerVerifier.test(owner,signed.getAccountKey())) return signed;
		// A custom rule may authorise a delegate even for a direct-key identity.
		if (direct!=null) {
			signed=context.sign(null,value);
			if (signed!=null && ownerVerifier.test(owner,signed.getAccountKey())) return signed;
		}
		return null;
	}

	@Override
	public SignedData<V> merge(SignedData<V> ownValue, SignedData<V> otherValue) {
		// No context: signature synthesis is unavailable, so this merge can only select
		return merge(LatticeContext.EMPTY, ownValue, otherValue);
	}

	@Override
	public SignedData<V> merge(LatticeContext context, SignedData<V> ownValue, SignedData<V> otherValue) {
		return merge(context,owner,ownValue,otherValue);
	}

	/**
	 * Context-aware merge for values owned by a specific identity.
	 *
	 * @param context Context supplying the signing policy
	 * @param owner Owner identity for this position, or null when unknown
	 * @param ownValue Established value, preferred by directional tie-breaks
	 * @param otherValue Value to merge in, possibly received from an untrusted source
	 * @return Merged signed value
	 */
	public SignedData<V> merge(LatticeContext context, ACell owner,
			SignedData<V> ownValue, SignedData<V> otherValue) {
		if (otherValue==null) return ownValue;

		// If we don't have a value, use other as long as signature is correct
		if (ownValue==null) {
			if (checkForeign(otherValue)) return otherValue;
			return null;
		}

		// Fast path for identical values (common case after null checks)
		V a=ownValue.getValue();
		V b=otherValue.getValue();
		if (Utils.equals(a,b)) return ownValue;

		// Bail out if other signature is invalid
		if (!checkForeign(otherValue)) return ownValue;

		// Perform child lattice merge with context
		V m=valueNode.merge(context, a, b);

		// Check if new lattice value is identical to either input
		if (Utils.equals(a, m)) return ownValue;
		if (Utils.equals(b, m)) return otherValue;

		// A synthesised value must be signed as this owner. If we cannot author it,
		// retain the own value: never store, or propagate, a slot signed by a
		// non-owner, and never abort an otherwise valid merge over it.
		SignedData<V> signed=sign(context,owner,m);
		return (signed!=null)?signed:ownValue;
	}

	@Override
	public boolean checkForeign(SignedData<V> otherValue) {
		if (otherValue==null) return false;
		if (ownerVerifier!=null && !ownerVerifier.test(owner,otherValue.getAccountKey())) return false;
		return otherValue.checkSignature();
	}

	@Override
	public SignedData<V> zero() {
		return null;
	}

	private static final ABlob VALUE_BLOB = Keywords.VALUE.toBlob();

	@Override
	public ACell resolveKey(ACell key) {
		if (key instanceof ABlobLike<?> blobLike) {
			if (VALUE_BLOB.equals(blobLike.toBlob())) {
				return Keywords.VALUE;
			}
		}
		return null;
	}

	/**
	 * The schema below the envelope: {@code :value} selects the unsigned value's
	 * lattice. This describes the tree for hosts and tools; a cursor reaches the
	 * value for writing only through the {@code SignedCursor} the owning component
	 * wraps around the slot, and a plain write through {@code :value} is refused by
	 * the write engine because nothing may be written into a signed envelope.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (Keywords.VALUE.equals(childKey)) {
			return (ALattice<T>) valueNode;
		}
		return null;
	}

	/**
	 * Gets the owner identity this lattice authors values for, if known.
	 *
	 * @return Owner identity, or null
	 */
	public ACell getOwner() {
		return owner;
	}

}
