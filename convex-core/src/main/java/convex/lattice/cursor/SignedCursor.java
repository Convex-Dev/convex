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
 * <p>Wraps a parent cursor holding {@link SignedData} and presents the unsigned
 * inner value. Reads extract the value; writes re-sign using the key pair from
 * the {@link LatticeContext}. Throws {@link IllegalStateException} on write if
 * no signing key is available — this is the enforcement point.</p>
 *
 * <p>The sign/unsign transform pair is the {@link #encode}/{@link #decode}
 * implementation of {@link ABoundaryCursor}; all atomic operations are inherited
 * from there.</p>
 *
 * <p>Created automatically by {@code ALatticeCursor.path()} when navigating
 * through a signing boundary.</p>
 *
 * @param <V> Type of the unsigned value
 */
public class SignedCursor<V extends ACell> extends ABoundaryCursor<V, SignedData<V>> {

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
	protected SignedData<V> encode(V value) {
		AKeyPair kp = context.getSigningKey();
		if (kp == null) throw new IllegalStateException("SignedCursor requires a signing key in context");
		return kp.signData(value);
	}

	@Override
	protected V decode(SignedData<V> stored) {
		return stored.getValue();
	}
}
