package convex.lattice.generic;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

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
 * this node's owner. When no authorised signer can do that — the usual case
 * for another owner's data — the merge keeps the own value rather than producing an
 * entry no peer would accept. Convergence for that owner is then the owner's to
 * complete; see the class documentation on {@link ALattice} for why a merge must not
 * fail on data it merely cannot author.</p>
 *
 * <p>The context-free merge never signs and uses the payload's context-free
 * merge. Owned-map components use this public operation. Authoring components use
 * {@link #mergeAndSign} with an explicit signer. Context-based overloads remain
 * available for legacy callers.</p>
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
	 * Returns a view bound to a specific owner identity.
	 *
	 * @param owner Owner identity. An existing bound verifier is retained; otherwise
	 *              verification uses the legacy context owner verifier.
	 * @return Owner-bound view of this lattice
	 */
	public SignedLattice<V> withOwner(ACell owner) {
		if (Utils.equals(owner,this.owner)) return this;
		return new SignedLattice<>(valueNode,owner,ownerVerifier);
	}

	/**
	 * Binds an owner and its verification rule to this lattice, independently of
	 * the context's legacy owner verifier. Components bind this rule when authoring signed updates.
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
	 * Authors exactly the requested value using an explicit signer. Null means the
	 * signer is unavailable or unauthorised. Malformed results are rejected before
	 * storage: a signer cannot substitute a different payload or an invalid signature.
	 */
	public SignedData<V> sign(V value,Function<V,SignedData<V>> signer) {
		Objects.requireNonNull(signer,"Signer must not be null");
		if (!validPayload(value)) throw new IllegalArgumentException("Invalid signed payload");
		SignedData<V> signed=checkedResult(value,signer.apply(value));
		if (signed==null) return null;
		return accepts(LatticeContext.EMPTY,owner,signed)?signed:null;
	}

	private SignedData<V> checkedResult(V value,SignedData<V> signed) {
		if (signed==null) return null;
		if (!Utils.equals(value,signed.getValue())) throw new IllegalArgumentException("Signer changed the requested payload");
		if (!signed.checkSignature()) throw new IllegalArgumentException("Signer returned an invalid signature");
		return signed;
	}

	/**
	 * Merges with explicit permission to author a synthesised payload. Selecting an
	 * existing envelope never calls the signer. An unavailable or unauthorised signer
	 * leaves the established envelope unchanged.
	 */
	public SignedData<V> mergeAndSign(SignedData<V> own,SignedData<V> other,Function<V,SignedData<V>> signer) {
		Objects.requireNonNull(signer,"Signer must not be null");
		return merge(null,owner,own,other,value -> sign(value,signer));
	}


	/**
	 * Authors this owner's value with an available authorised signer, or returns
	 * null. A bound verifier takes precedence over legacy context verification.
	 */
	public SignedData<V> sign(LatticeContext context, V value) {
		return sign(context,owner,value);
	}

	private SignedData<V> sign(LatticeContext context, ACell requestedOwner, V value) {
		if (!validPayload(value)) throw new IllegalArgumentException("Invalid signed payload");
		if (ownerVerifier==null) return checkedResult(value,context.signAs(requestedOwner,value));
		// A bound instance's owner cannot be replaced by a per-call merge argument.
		AccountKey direct=LatticeContext.ownerAccountKey(owner);
		SignedData<V> signed=checkedResult(value,context.sign(direct,value));
		if (signed!=null && ownerVerifier.test(owner,signed.getAccountKey())) return signed;
		// A custom rule may authorise a delegate even for a direct-key identity.
		if (direct!=null) {
			signed=checkedResult(value,context.sign(null,value));
			if (signed!=null && ownerVerifier.test(owner,signed.getAccountKey())) return signed;
		}
		return null;
	}

	@Override
	public SignedData<V> merge(SignedData<V> ownValue, SignedData<V> otherValue) {
		// No context: signature synthesis is unavailable, so this merge can only select
		return merge(null,owner,ownValue,otherValue);
	}

	@Override
	public SignedData<V> merge(LatticeContext context, SignedData<V> ownValue, SignedData<V> otherValue) {
		return merge(context,owner,ownValue,otherValue);
	}

	/**
	 * Context-aware merge for values owned by a specific identity. A foreign value is
	 * accepted only if its signature verifies and its signer is authorised for the
	 * owner, so merging at one owner's slot means the same as merging through the
	 * owner map that contains it.
	 *
	 * @param context Explicit authoring policy and, for a lattice without a bound
	 *        verifier, the owner verification rule. Null selects a public merge which
	 *        never signs and uses the payload lattice's context-free merge.
	 * @param owner Owner identity for this position, or null when unknown
	 * @param ownValue Established value, preferred by directional tie-breaks
	 * @param otherValue Value to merge in, possibly received from an untrusted source
	 * @return Merged signed value
	 */
	public SignedData<V> merge(LatticeContext context, ACell owner,
			SignedData<V> ownValue, SignedData<V> otherValue) {
		return merge(context,owner,ownValue,otherValue,context==null?null:value -> sign(context,owner,value));
	}

	private SignedData<V> merge(LatticeContext context,ACell owner,SignedData<V> ownValue,
			SignedData<V> otherValue,Function<V,SignedData<V>> author) {
		if (otherValue==null) return ownValue;
		LatticeContext policy=(context==null)?LatticeContext.EMPTY:context;

		// If we don't have a value, use other as long as it is validly authored for this owner
		if (ownValue==null) return accepts(policy,owner,otherValue)?otherValue:null;

		// Fast path for identical values (common case after null checks)
		V a=ownValue.getValue();
		V b=otherValue.getValue();
		if (Utils.equals(a,b)) return ownValue;

		// Bail out if other is not validly authored for this owner
		if (!accepts(policy,owner,otherValue)) return ownValue;

		V m=(context==null)?valueNode.merge(a,b):valueNode.merge(context,a,b);

		// Check if new lattice value is identical to either input
		if (Utils.equals(a, m)) return ownValue;
		if (Utils.equals(b, m)) return otherValue;
		if (author==null) return ownValue;

		// A synthesised value must be signed as this owner. If we cannot author it,
		// retain the own value: never store, or propagate, a slot signed by a
		// non-owner, and never abort an otherwise valid merge over it.
		SignedData<V> signed=author.apply(m);
		return (signed!=null)?signed:ownValue;
	}

	/**
	 * Whether a foreign value may be merged at this owner's position. A bound verifier
	 * is authoritative, as it is for authoring; otherwise the context's owner
	 * verification applies whenever the owner is known.
	 */
	private boolean accepts(LatticeContext context, ACell owner, SignedData<V> otherValue) {
		if (!checkForeign(otherValue)) return false;
		if (ownerVerifier!=null || owner==null) return true;
		return context.verifyOwner(owner,otherValue.getAccountKey());
	}

	@Override
	public boolean checkForeign(SignedData<V> otherValue) {
		if (otherValue==null) return false;
		if (ownerVerifier!=null && !ownerVerifier.test(owner,otherValue.getAccountKey())) return false;
		return otherValue.checkSignature() && validPayload(otherValue.getValue());
	}

	private boolean validPayload(V value) {
		try {
			return valueNode.checkForeign(value);
		} catch (ClassCastException e) {
			// SignedData's Java type parameter is not evidence about a foreign payload.
			return false;
		}
	}

	@Override
	public SignedData<V> zero() {
		return null;
	}

	/** No key resolves below a signed envelope. */
	@Override
	public ACell resolveKey(ACell key) {
		return null;
	}

	/**
	 * A signed envelope is a leaf of the lattice tree: no path continues inside it,
	 * because nothing merges or writes there except as a complete envelope. The owning
	 * component reaches the value through the {@code SignedCursor} it wraps around the
	 * slot, with {@link #getValueLattice()} as the merge for its working copy.
	 */
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
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
