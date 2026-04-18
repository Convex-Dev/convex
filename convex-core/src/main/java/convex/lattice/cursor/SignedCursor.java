package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

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
 * <p>Created automatically by {@code SignedLattice.createPathCursor()} when
 * navigating through a signing boundary via {@code path()}.</p>
 *
 * @param <V> Type of the unsigned value
 */
public class SignedCursor<V extends ACell> extends ALatticeCursor<V> {

	private final ACursor<SignedData<V>> base;

	@SuppressWarnings("unchecked")
	SignedCursor(ACursor<SignedData<V>> base, ALattice<V> subLattice, LatticeContext context) {
		super(subLattice, context, null);
		this.base = base;
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
	public V sync() {
		if (base instanceof ALatticeCursor<?> lc) {
			lc.sync();
		} else {
			throw new IllegalStateException(
				"SignedCursor.sync(): base cursor is not an ALatticeCursor (got " +
				base.getClass().getSimpleName() + "). Sync cannot propagate.");
		}
		return get();
	}

	@Override
	public V get() {
		SignedData<V> sd = base.get();
		return (sd != null) ? sd.getValue() : null;
	}

	@Override
	public void set(V newValue) {
		base.set(sign(newValue));
	}

	@Override
	public V getAndSet(V newValue) {
		SignedData<V> old = base.getAndSet(sign(newValue));
		return (old != null) ? old.getValue() : null;
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		SignedData<V> old = base.getAndUpdate(sd -> {
			V current = (sd != null) ? sd.getValue() : null;
			V updated = updateFunction.apply(current);
			return sign(updated);
		});
		return (old != null) ? old.getValue() : null;
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		@SuppressWarnings("unchecked")
		V[] result = (V[]) new ACell[1];
		base.updateAndGet(sd -> {
			V current = (sd != null) ? sd.getValue() : null;
			V updated = updateFunction.apply(current);
			result[0] = updated;
			return sign(updated);
		});
		return result[0];
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		SignedData<V> old = base.getAndUpdate(sd -> {
			V current = (sd != null) ? sd.getValue() : null;
			V accumulated = accumulatorFunction.apply(x, current);
			return sign(accumulated);
		});
		return (old != null) ? old.getValue() : null;
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		@SuppressWarnings("unchecked")
		V[] result = (V[]) new ACell[1];
		base.updateAndGet(sd -> {
			V current = (sd != null) ? sd.getValue() : null;
			V accumulated = accumulatorFunction.apply(x, current);
			result[0] = accumulated;
			return sign(accumulated);
		});
		return result[0];
	}

	@Override
	public boolean compareAndSet(V expected, V newValue) {
		boolean[] updated = new boolean[1];
		base.update(sd -> {
			V current = (sd != null) ? sd.getValue() : null;
			if (convex.core.util.Utils.equals(expected, current)) {
				updated[0] = true;
				return sign(newValue);
			}
			return sd;
		});
		return updated[0];
	}

	private SignedData<V> sign(V value) {
		if (value == null) return null;
		AKeyPair kp = context.getSigningKey();
		if (kp == null) throw new IllegalStateException("SignedCursor requires a signing key in context");
		return kp.signData(value);
	}
}
