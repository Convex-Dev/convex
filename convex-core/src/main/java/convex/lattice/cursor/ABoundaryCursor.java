package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Abstract base for lattice cursors that sit at a boundary between a stored
 * representation and a view representation, transparently transforming values
 * in both directions.
 *
 * <p>A boundary cursor wraps a {@code base} cursor holding values of the
 * <em>stored</em> type {@code S} and presents values of the <em>view</em> type
 * {@code V}. Reads {@link #decode(ACell) decode} the stored value; writes
 * {@link #encode(ACell) encode} the view value before storing it. All atomic
 * operations (set, getAndSet, getAndUpdate, updateAndGet, accumulate, CAS) and
 * {@link #sync()} are implemented once here in terms of this pair, so concrete
 * subclasses only supply {@code encode}/{@code decode}.</p>
 *
 * <p>{@code encode} may consult the cursor's {@link #context} (e.g. to source a
 * signing key or timestamp) and may throw to enforce a precondition — this is
 * the place to enforce write-time invariants. Both hooks are only ever invoked
 * with non-null arguments; null maps to null automatically.</p>
 *
 * <p>The base class performs lattice merge in the <em>view</em> domain: the
 * inherited {@link ALatticeCursor#merge(ACell)} reads the current value, merges
 * via the (view) lattice, and writes the result back — which re-{@code encode}s
 * it. This is correct for transforms like signing where the merged value must be
 * re-stamped. Subclasses whose encode must <em>not</em> run on merge results
 * should override the merge path accordingly.</p>
 *
 * @param <V> Type of the view value presented by this cursor
 * @param <S> Type of the stored value held by the base cursor
 */
public abstract class ABoundaryCursor<V extends ACell, S extends ACell> extends ALatticeCursor<V> {

	protected final ACursor<S> base;

	protected ABoundaryCursor(ACursor<S> base, ALattice<V> lattice, LatticeContext context) {
		super(lattice, context, null);
		this.base = base;
	}

	/**
	 * Encodes a view value into its stored representation for writing.
	 *
	 * <p>May consult {@link #context} and may throw to enforce a write
	 * precondition. Never invoked with a null argument — null view values map
	 * to null stored values automatically.</p>
	 *
	 * @param view Non-null view value to encode
	 * @return Stored representation
	 */
	protected abstract S encode(V view);

	/**
	 * Decodes a stored value into its view representation for reading.
	 *
	 * <p>Never invoked with a null argument — null stored values map to null
	 * view values automatically.</p>
	 *
	 * @param stored Non-null stored value to decode
	 * @return View representation
	 */
	protected abstract V decode(S stored);

	private S encodeNullable(V view) {
		return (view != null) ? encode(view) : null;
	}

	private V decodeNullable(S stored) {
		return (stored != null) ? decode(stored) : null;
	}

	/**
	 * Encodes {@code updated} for storage, but returns the existing stored value
	 * {@code current} unchanged when the view value did not change — so an expensive
	 * {@link #encode} (e.g. re-signing, re-stamping) is skipped when nothing actually
	 * changed, even if the updated object's identity differs from the current one.
	 */
	private S encodeIfChanged(S current, V updated) {
		return Utils.equals(updated, decodeNullable(current)) ? current : encodeNullable(updated);
	}

	@Override
	public V sync() {
		if (base instanceof ALatticeCursor<?> lc) {
			lc.sync();
		} else {
			throw new IllegalStateException(
				getClass().getSimpleName() + ".sync(): base cursor is not an ALatticeCursor (got " +
				base.getClass().getSimpleName() + "). Sync cannot propagate.");
		}
		return get();
	}

	@Override
	public V get() {
		return decodeNullable(base.get());
	}

	@Override
	public void set(V newValue) {
		// getAndUpdate (not set) so an unchanged write skips re-encoding
		base.getAndUpdate(s -> encodeIfChanged(s, newValue));
	}

	@Override
	public V getAndSet(V newValue) {
		return decodeNullable(base.getAndUpdate(s -> encodeIfChanged(s, newValue)));
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		S old = base.getAndUpdate(s -> encodeIfChanged(s, updateFunction.apply(decodeNullable(s))));
		return decodeNullable(old);
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return decodeNullable(base.updateAndGet(s -> encodeIfChanged(s, updateFunction.apply(decodeNullable(s)))));
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		S old = base.getAndUpdate(s -> encodeIfChanged(s, accumulatorFunction.apply(decodeNullable(s), x)));
		return decodeNullable(old);
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return decodeNullable(base.updateAndGet(s -> encodeIfChanged(s, accumulatorFunction.apply(decodeNullable(s), x))));
	}

	/**
	 * Compare-and-set on the <em>view</em> value.
	 *
	 * <p>Unlike a reference-identity CAS, this compares {@code expected} against the
	 * current value by {@link Utils#equals value equality}. A reference CAS is not
	 * possible at a boundary: {@code expected} is a view value {@code V} while the
	 * cell held by {@code base} is the stored type {@code S}, so the two cannot be
	 * identity-compared, and {@link #decode} mints a fresh view value on each read.</p>
	 *
	 * <p>On a value match, {@code newValue} is {@link #encode encoded} and written
	 * via a single compare-and-set against the stored cell that was read (skipping the
	 * encode if the value is unchanged). Like any CAS this is single-shot: it returns
	 * {@code false} without retrying if {@code base} changed concurrently.</p>
	 */
	@Override
	public boolean compareAndSet(V expected, V newValue) {
		S cur = base.get();
		if (!Utils.equals(expected, decodeNullable(cur))) return false;
		return base.compareAndSet(cur, encodeIfChanged(cur, newValue));
	}
}
