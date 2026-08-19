package convex.lattice.generic;

import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.AUpdateCursor;
import convex.lattice.cursor.SignedCursor;

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
 * @param <V> Type of signed lattice value
 */
public class SignedLattice<V extends ACell> extends ALattice<SignedData<V>> {


	protected final ALattice<V> valueNode;

	/** Owner identity for values at this position, or null when unknown. */
	private final ACell owner;

	private SignedLattice(ALattice<V> valueNode, ACell owner) {
		this.valueNode=valueNode;
		this.owner=owner;
	}

	public static <V extends ACell> SignedLattice<V> create(ALattice<V> childNode) {
		return new SignedLattice<>(childNode,null);
	}

	/**
	 * Returns a view bound to a specific owner identity, whose signer the context
	 * signing policy is asked for when a value must be authored at this position.
	 *
	 * @param owner Owner identity (an AccountKey, or an indirect identity such as an
	 *              Address or DID resolved by the context's owner verifier)
	 * @return Owner-bound view of this lattice
	 */
	public SignedLattice<V> withOwner(ACell owner) {
		if (Utils.equals(owner,this.owner)) return this;
		return new SignedLattice<>(valueNode,owner);
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
		SignedData<V> signed=context.signAs(owner,m);
		return (signed!=null)?signed:ownValue;
	}

	@Override
	public boolean checkForeign(SignedData<V> otherValue) {
		if (otherValue==null) return false;
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

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ALattice<T> path(ACell childKey) {
		if (Keywords.VALUE.equals(childKey)) {
			return (ALattice<T>) valueNode;
		}
		return null;
	}

	/**
	 * The signing boundary is at {@code :value}: crossing it goes from
	 * {@code SignedData<V>} to the unsigned {@code V}, so a {@link SignedCursor}
	 * is inserted to handle sign/verify, consuming the {@code :value} key (the
	 * default {@link #consumesPathKey}).
	 */
	@Override
	public boolean isWriteBoundary(ACell key) {
		return Keywords.VALUE.equals(key);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AUpdateCursor<?, ?> createPathCursor(ALatticeCursor<?> base, ACell key, LatticeContext context) {
		// Only called when isWriteBoundary(key) is true, i.e. key is :value.
		ALattice<V> inner = path(key); // valueNode
		return SignedCursor.create((ALatticeCursor<SignedData<V>>) base,inner,context,owner);
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
