package convex.lattice.cursor;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.AOwnerLattice;
import convex.lattice.generic.SignedLattice;

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
 * {@link IllegalStateException} if there is none. A lattice-bound verification rule
 * is retained and applies to both authoring and incoming signed merges, regardless
 * of the context's owner verifier. Legacy factories without a bound rule continue
 * to use {@link LatticeContext#verifyOwner}, including its compatibility behaviour
 * for indirect owners.</p>
 *
 * <p>An unbound cursor (no owner known, e.g. the explicit key pair factory) simply
 * signs with the policy's primary signer.</p>
 *
 * <p>Navigation never inserts this cursor. The component which owns an owner-keyed
 * level wraps each owner's slot with {@link #forOwner}, so the boundary is visible
 * exactly where the domain structure places it and its descendants never see
 * {@code SignedData}.</p>
 *
 * @param <V> Type of the unsigned value
 */
public class SignedCursor<V extends ACell> extends AUpdateCursor<V, SignedData<V>> {

	/** Owner identity this cursor authors values for, or null when unbound. */
	private final ACell owner;
	private final SignedLattice<V> signingLattice;

	SignedCursor(ACursor<SignedData<V>> base, ALattice<V> subLattice,
			LatticeContext context, ACell owner) {
		this(base,SignedLattice.create(subLattice).withOwner(owner),context);
	}

	private SignedCursor(ACursor<SignedData<V>> base, SignedLattice<V> signingLattice,
			LatticeContext context) {
		super(base,signingLattice.getValueLattice(),context);
		this.owner=signingLattice.getOwner();
		this.signingLattice=signingLattice;
	}

	/**
	 * Wraps one owner's slot in an owner-keyed lattice with the signing boundary that
	 * lattice verifies with. This is how a component crosses into an owner's value:
	 * {@code SignedCursor.forOwner(cursor.path(owner), OWNERS, owner)}. Authoring then
	 * uses exactly the bound rule the host applies to incoming merges, and the
	 * resulting cursor inherits the slot's context live.
	 *
	 * @param <V> Type of the unsigned value
	 * @param slot Cursor at the owner's {@code SignedData} slot
	 * @param owners Owner-keyed lattice containing the slot
	 * @param owner Owner identity whose slot this is
	 * @return Cursor over the owner's unsigned value
	 * @throws IllegalArgumentException if the owner is not a valid key for the lattice
	 */
	public static <V extends ACell> SignedCursor<V> forOwner(ALatticeCursor<SignedData<V>> slot,
			AOwnerLattice<?,V,?> owners, ACell owner) {
		SignedLattice<V> rule=owners.ownerLattice(owner);
		if (rule==null) {
			throw new IllegalArgumentException("Not a valid owner key for "
				+owners.getClass().getSimpleName()+": "+owner);
		}
		return new SignedCursor<>(slot,rule,null);
	}

	/** Creates a cursor retaining the supplied signed lattice's ownership rule. */
	public static <V extends ACell> SignedCursor<V> createBound(
			ALatticeCursor<SignedData<V>> base, SignedLattice<V> signingLattice,
			LatticeContext context) {
		return new SignedCursor<>(base,signingLattice,context);
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
		SignedData<V> signed=signingLattice.sign(context,value);
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
