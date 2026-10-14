package convex.lattice.cursor;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.lattice.LatticeContext;
import convex.lattice.Ownership;
import convex.lattice.Shape;
import convex.lattice.Signing;

/**
 * A cursor that sits at a signing boundary, transparently handling sign/verify of
 * values.
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
 * <p>The {@link Signing} rule a cursor is created with binds it to an owner. It asks
 * the context for a signer authorised for that owner — which a wallet or key store
 * may satisfy with any accessible identity, primary or not — and throws
 * {@link IllegalStateException} if there is none. A rule obtained from an owner
 * lattice carries that lattice's verification, so authoring and incoming merges
 * cannot disagree. An unbound rule (no owner known, e.g. the explicit key pair
 * factory) simply signs with the policy's primary signer.</p>
 *
 * <p>Navigation never inserts this cursor. The component which owns an owner-keyed
 * level wraps each owner's slot with {@link #forOwner}, so the boundary is visible
 * exactly where the domain structure places it and its descendants never see
 * {@code SignedData}.</p>
 *
 * @param <V> Type of the unsigned value
 */
public class SignedCursor<V extends ACell> extends AUpdateCursor<V, SignedData<V>> {

	private final Signing<V> signing;

	private SignedCursor(ACursor<SignedData<V>> base, Signing<V> signing, LatticeContext context) {
		super(base,signing.getValueShape(),context);
		this.signing=signing;
	}

	/**
	 * Wraps one owner's slot in an owner-keyed level with the signing rule that level
	 * verifies with. This is how a component crosses into an owner's value:
	 * {@code SignedCursor.forOwner(cursor.path(owner), OWNERS, owner)}. Authoring then
	 * uses exactly the bound rule the host applies to incoming merges, and the
	 * resulting cursor inherits the slot's context live.
	 *
	 * @param <V> Type of the unsigned value
	 * @param slot Cursor at the owner's {@code SignedData} slot
	 * @param owners Owner-keyed level containing the slot, usually its owner lattice
	 * @param owner Owner identity whose slot this is
	 * @return Cursor over the owner's unsigned value
	 * @throws IllegalArgumentException if the owner is not a valid key for the level
	 */
	public static <V extends ACell> SignedCursor<V> forOwner(ACursor<SignedData<V>> slot,
			Ownership<V> owners, ACell owner) {
		Signing<V> rule=owners.signingFor(owner);
		if (rule==null) {
			throw new IllegalArgumentException("Not a valid owner key for "
				+owners.getClass().getSimpleName()+": "+owner);
		}
		return new SignedCursor<>(slot,rule,null);
	}

	/**
	 * Creates a cursor authoring with an explicit signing rule.
	 *
	 * @param <V> Type of the unsigned value
	 * @param base Cursor at the {@code SignedData} slot
	 * @param signing Signing rule, usually an owner-bound signed lattice
	 * @param context Local context override, or null to inherit from the base cursor
	 * @return New SignedCursor
	 */
	public static <V extends ACell> SignedCursor<V> create(ACursor<SignedData<V>> base,
			Signing<V> signing, LatticeContext context) {
		if (signing==null) throw new IllegalArgumentException("Signing rule must not be null");
		return new SignedCursor<>(base,signing,context);
	}

	/**
	 * Creates a SignedCursor wrapping a cursor to SignedData, with an explicit key pair.
	 *
	 * <p>Convenience factory for direct use (e.g. tests). The cursor is bound to no
	 * owner and signs with the key pair, installed as its local context.</p>
	 *
	 * @param <V> Type of the unsigned value
	 * @param base Cursor pointing to SignedData
	 * @param keyPair Key pair for signing updates
	 * @return New SignedCursor
	 */
	public static <V extends ACell> SignedCursor<V> create(ACursor<SignedData<V>> base, AKeyPair keyPair) {
		if (keyPair == null) throw new IllegalArgumentException("SignedCursor requires a key pair");
		return new SignedCursor<>(base, primarySigner(), LatticeContext.create(null, keyPair));
	}

	/** A rule bound to no owner which signs with the effective context's primary signer. */
	private static <V extends ACell> Signing<V> primarySigner() {
		return new Signing<V>() {
			@Override public SignedData<V> sign(LatticeContext context, V value) { return context.sign(value); }
			@Override public ACell getOwner() { return null; }
			@Override public Shape<V> getValueShape() { return Shape.none(); }
		};
	}

	/** Returns the owner identity this cursor authors for, or null when unbound. */
	public ACell getOwner() {
		return signing.getOwner();
	}

	@Override
	protected V view(SignedData<V> stored) {
		return (stored != null) ? stored.getValue() : null;
	}

	@Override
	protected SignedData<V> prepareWrite(V value) {
		SignedData<V> signed=signing.sign(getContext(),value);
		if (signed==null) {
			ACell owner=signing.getOwner();
			throw new IllegalStateException("SignedCursor requires an available signer"
				+((owner==null)?"":" authorised for owner "+owner));
		}
		return signed;
	}
}
