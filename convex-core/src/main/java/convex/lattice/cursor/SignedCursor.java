package convex.lattice.cursor;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.util.Utils;
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
 * its {@link #updateOnWrite} re-signs on write using the key pair from the
 * {@link LatticeContext} — throwing {@link IllegalStateException} if no signing key
 * is available (the enforcement point). All atomic operations are inherited from
 * {@link AUpdateCursor}.</p>
 *
 * <p>Created automatically by {@code ALatticeCursor.path()} when navigating through a
 * signing boundary.</p>
 *
 * @param <V> Type of the unsigned value
 */
public class SignedCursor<V extends ACell> extends AUpdateCursor<V, SignedData<V>> {

	SignedCursor(ACursor<SignedData<V>> base, ALattice<V> subLattice, LatticeContext context) {
		super(base, subLattice, context);
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
		return new SignedCursor<>(base, null, LatticeContext.create(null, keyPair));
	}

	/**
	 * Creates a SignedCursor wrapping a lattice cursor to SignedData, with
	 * sub-lattice and context.
	 *
	 * @param <V> Type of the unsigned value
	 * @param base Lattice cursor pointing to SignedData
	 * @param subLattice Lattice for the unsigned inner value (may be null)
	 * @param context Lattice context (must contain signing key for writes)
	 * @return New SignedCursor
	 */
	public static <V extends ACell> SignedCursor<V> create(ALatticeCursor<SignedData<V>> base, ALattice<V> subLattice, LatticeContext context) {
		return new SignedCursor<>(base, subLattice, context);
	}

	@Override
	protected V view(SignedData<V> stored) {
		return (stored != null) ? stored.getValue() : null;
	}

	@Override
	protected SignedData<V> updateOnWrite(SignedData<V> current, V value) {
		if (value == null) return null;
		// Unchanged value: keep the existing signature rather than re-signing
		if (current != null && Utils.equals(value, current.getValue())) return current;
		AKeyPair kp = context.getSigningKey();
		if (kp == null) throw new IllegalStateException("SignedCursor requires a signing key in context");
		return kp.signData(value);
	}

	/**
	 * Merge synthesises a new value: merge the unsigned values via the lattice, then
	 * re-sign the result (via {@link #updateOnWrite}). The signed envelope must always
	 * carry a valid signature over its current value, so a merged value is re-signed.
	 */
	@Override
	public V merge(V other) {
		if (lattice == null) throw new UnsupportedOperationException("Cannot merge without a lattice");
		return view(base.updateAndGet(cur -> updateOnWrite(cur, lattice.merge(context, view(cur), other))));
	}
}
