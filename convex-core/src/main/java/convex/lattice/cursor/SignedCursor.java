package convex.lattice.cursor;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * A lattice cursor that sits at the signing boundary, transparently handling
 * sign/verify of lattice values.
 *
 * <p>Unlike {@link StampedCursor} (a same-type update override), a SignedCursor is a
 * genuine <b>view boundary</b>: the stored cell is a fundamentally different envelope
 * type, {@link SignedData}, whose {@code :value} child is the unsigned value this
 * cursor presents. So it overrides {@link #view} to project the value on read, and
 * its {@link #prepareWrite} signs on write through the {@link LatticeContext}'s
 * signing policy. All atomic operations are inherited from {@link AUpdateCursor}.</p>
 *
 * <h2>Owner binding</h2>
 *
 * <p>A cursor reached by navigating an owner-keyed lattice is bound to that owner. It
 * asks the context for a signer authorised for that owner — which a wallet or key
 * store may satisfy with any accessible identity, primary or not — and throws
 * {@link IllegalStateException} if there is none. Authorisation uses exactly the rule
 * that {@code OwnerLattice} applies on merge ({@link LatticeContext#verifyOwner}), so
 * a slot is never written locally in a form that a peer would reject: an owner that
 * directly denotes an account key requires that key, and an indirect owner (Address,
 * DID, ...) is resolved by the context's owner verifier, which is lenient when none
 * is installed.</p>
 *
 * <p>An unbound cursor (no owner known, e.g. the explicit key pair factory) simply
 * signs with the policy's primary signer.</p>
 *
 * <p>Created automatically by {@code ALatticeCursor.path()} when navigating through a
 * signing boundary.</p>
 *
 * @param <V> Type of the unsigned value
 */
public class SignedCursor<V extends ACell> extends AUpdateCursor<V, SignedData<V>> {

	/** Owner identity this cursor authors values for, or null when unbound. */
	private final ACell owner;

	SignedCursor(ACursor<SignedData<V>> base, ALattice<V> subLattice,
			LatticeContext context, ACell owner) {
		super(base, subLattice, context);
		this.owner=owner;
	}

	/**
	 * Creates a SignedCursor wrapping a cursor to SignedData, with an explicit key pair.
	 *
	 * <p>Convenience factory for direct use (e.g. tests). Wraps the key pair
	 * in a {@link LatticeContext} internally.</p>
	 *
	 * @param <V> Type of the unsigned value
	 * @param base Cursor pointing to SignedData
	 * @param keyPair Key pair for signing updates
	 * @return New SignedCursor
	 */
	public static <V extends ACell> SignedCursor<V> create(ACursor<SignedData<V>> base, AKeyPair keyPair) {
		if (keyPair == null) throw new IllegalArgumentException("SignedCursor requires a key pair");
		return new SignedCursor<>(base, null, LatticeContext.create(null, keyPair),null);
	}

	/**
	 * Creates a SignedCursor wrapping a lattice cursor to SignedData, with
	 * sub-lattice and context.
	 *
	 * @param <V> Type of the unsigned value
	 * @param base Lattice cursor pointing to SignedData
	 * @param subLattice Lattice for the unsigned inner value (may be null)
	 * @param context Local context override, or null to inherit from the base cursor
	 *                (the effective context must be able to sign for writes)
	 * @return New SignedCursor
	 */
	public static <V extends ACell> SignedCursor<V> create(ALatticeCursor<SignedData<V>> base, ALattice<V> subLattice, LatticeContext context) {
		return create(base,subLattice,context,null);
	}

	/**
	 * Creates a SignedCursor bound to an owner identity, whose authorised signer is
	 * requested from the context's signing policy.
	 *
	 * @param <V> Type of the unsigned value
	 * @param base Lattice cursor pointing to SignedData
	 * @param subLattice Lattice for the unsigned inner value (may be null)
	 * @param context Local context override, or null to inherit from the base cursor
	 * @param owner Owner identity for values at this position, or null when unbound
	 * @return New SignedCursor
	 */
	public static <V extends ACell> SignedCursor<V> create(
			ALatticeCursor<SignedData<V>> base, ALattice<V> subLattice,
			LatticeContext context, ACell owner) {
		return new SignedCursor<>(base,subLattice,context,owner);
	}

	@Override
	protected V view(SignedData<V> stored) {
		return (stored != null) ? stored.getValue() : null;
	}

	@Override
	protected SignedData<V> prepareWrite(V value) {
		LatticeContext context=getContext();
		SignedData<V> signed=context.signAs(owner,value);
		if (signed==null) {
			throw new IllegalStateException("SignedCursor requires an available signer"
				+((owner==null)?"":" authorised for owner "+owner));
		}
		return signed;
	}

	/**
	 * Merge synthesises a new value: merge the unsigned values via the lattice, then
	 * sign the result (via {@link #prepareWrite}). The signed envelope must always
	 * carry a valid signature over its current value, so a merged value is re-signed.
	 */
	@Override
	public V merge(V other) {
		if (lattice == null) throw new UnsupportedOperationException("Cannot merge without a lattice");
		return updateAndGet(current -> lattice.merge(getContext(), current, other));
	}
}
