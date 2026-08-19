package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Abstract base for cursors that override <em>update semantics</em>: every value
 * written through the cursor is funnelled through a single
 * {@link #prepareWrite(ACell) prepare-on-write} function before it is stored.
 *
 * <p>This is the shared machinery behind two quite different cursors:</p>
 * <ul>
 *   <li>{@link StampedCursor} — a same-type ({@code S == V}) <b>update override</b>:
 *       identity read, stamp on write. It never changes the view.</li>
 *   <li>{@link SignedCursor} — a type-changing ({@code S = SignedData<V>})
 *       <b>view boundary</b>: the stored cell is a different, envelope type, so it
 *       overrides {@link #view} to project the {@code :value} child on read and
 *       re-signs on write.</li>
 * </ul>
 *
 * <p>All eight atomic operations, {@code compareAndSet} and {@code sync} are
 * implemented here in terms of two hooks:</p>
 * <ul>
 *   <li>{@link #prepareWrite(ACell)} — <b>the</b> point every write funnels through:
 *       it authors the cell to store for a new view value. This is where write-time
 *       preconditions are enforced (it may consult {@link #getContext()} and may
 *       throw).</li>
 *   <li>{@link #view(ACell)} — how a stored cell reads back as a view value. Identity
 *       by default (the stored type <em>is</em> the view type); only a type-changing
 *       boundary overrides it.</li>
 * </ul>
 *
 * <p><b>Preparing is done once, not once per CAS attempt.</b> The atomic operations
 * are implemented over an {@code AtomicReference}-style retry loop, whose update
 * function is re-invoked on every contended CAS failure. Authoring a cell can be
 * expensive and can reach outside the JVM — signing may consult a wallet, key store
 * or remote signer — so this class never calls {@link #prepareWrite} from inside the
 * loop more than once per distinct value: a write of a fixed value prepares exactly
 * once however often it retries. A write whose value is computed from the current
 * one re-prepares only when a retry actually computes a different value.</p>
 *
 * <p>A write that does not change the view is not a write at all: the stored cell is
 * kept as it is, so an unchanged value keeps its existing signature or timestamp and
 * {@link #prepareWrite} is never called. That rule is uniform across subclasses.</p>
 *
 * <p><b>Update vs merge.</b> {@code prepareWrite} governs <em>writes</em>, not
 * convergence. {@link #merge} is deliberately abstract: a merge either <em>selects</em>
 * an existing value (LWW/stamping — store the winner as-is, no re-stamp) or
 * <em>synthesises</em> a new one (signing — merge the unsigned values, then re-sign).
 * Each subclass states which, so the choice is explicit rather than an inherited
 * default that silently does the wrong thing.</p>
 *
 * @param <V> Type of the view value presented by this cursor
 * @param <S> Type of the stored value held by the base cursor
 */
public abstract class AUpdateCursor<V extends ACell, S extends ACell> extends ALatticeCursor<V> {

	protected final ACursor<S> base;

	protected AUpdateCursor(ACursor<S> base, ALattice<V> lattice, LatticeContext context) {
		super(lattice, context, null);
		this.base = base;
	}

	@Override
	protected LatticeContext getInheritedContext() {
		if (base instanceof ALatticeCursor<?> latticeCursor) return latticeCursor.getContext();
		return LatticeContext.EMPTY;
	}

	/**
	 * The prepare-on-write function: authors the cell to store for a new, non-null
	 * view value. This is the single point every write funnels through — stamp here,
	 * sign here, enforce write preconditions here.
	 *
	 * <p>Never called for a null value, nor for a write that leaves the view
	 * unchanged; both are handled before it. It is called at most once per distinct
	 * value written, so it may safely be expensive, but it must be free of side
	 * effects beyond authoring the returned cell.</p>
	 *
	 * @param value New view value being written (never null)
	 * @return Stored cell to write
	 */
	protected abstract S prepareWrite(V value);

	/**
	 * Reads the view value from a stored cell. Identity by default — the stored type
	 * <em>is</em> the view type. A type-changing boundary (e.g. signing, whose stored
	 * cell is an envelope) overrides this to project to the inner value.
	 *
	 * @param stored Stored cell (may be null)
	 * @return View value (null maps to null)
	 */
	@SuppressWarnings("unchecked")
	protected V view(S stored) {
		return (V)(ACell) stored;
	}

	/**
	 * One prepared write, reused across CAS retries. Keeps the cost of authoring a
	 * cell proportional to the number of distinct values written, not to contention.
	 */
	private final class PreparedWrite {
		private V value;
		private S stored;
		private boolean present;

		/** Applies a write of {@code newValue} over the current stored cell. */
		S apply(S current, V newValue) {
			// Unchanged write: keep the current cell, so its signature or stamp stands
			if (Utils.equals(newValue, view(current))) return current;
			if (present && Utils.equals(newValue, value)) return stored;
			value = newValue;
			stored = (newValue == null) ? null : prepareWrite(newValue);
			present = true;
			return stored;
		}
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
		return view(base.get());
	}

	@Override
	public void set(V newValue) {
		PreparedWrite write = new PreparedWrite();
		base.getAndUpdate(s -> write.apply(s, newValue));
	}

	@Override
	public V getAndSet(V newValue) {
		PreparedWrite write = new PreparedWrite();
		return view(base.getAndUpdate(s -> write.apply(s, newValue)));
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		PreparedWrite write = new PreparedWrite();
		return view(base.getAndUpdate(s -> write.apply(s, updateFunction.apply(view(s)))));
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		PreparedWrite write = new PreparedWrite();
		return view(base.updateAndGet(s -> write.apply(s, updateFunction.apply(view(s)))));
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		PreparedWrite write = new PreparedWrite();
		return view(base.getAndUpdate(s -> write.apply(s, accumulatorFunction.apply(view(s), x))));
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		PreparedWrite write = new PreparedWrite();
		return view(base.updateAndGet(s -> write.apply(s, accumulatorFunction.apply(view(s), x))));
	}

	/**
	 * Compare-and-set on the view value.
	 *
	 * <p>Compares {@code expected} against the current view by {@link Utils#equals
	 * value equality} (at a type-changing boundary {@code expected} is a view value
	 * while the stored cell has a different type, and {@link #view} may mint a fresh
	 * value on each read, so a reference CAS is not possible). On a match, writes
	 * {@code newValue} via a single compare-and-set of the observed stored cell —
	 * single-shot: returns {@code false} without retrying if {@code base} changed
	 * concurrently.</p>
	 */
	@Override
	public boolean compareAndSet(V expected, V newValue) {
		S cur = base.get();
		if (!Utils.equals(expected, view(cur))) return false;
		return base.compareAndSet(cur, new PreparedWrite().apply(cur, newValue));
	}

	/**
	 * Converge an external value into the stored cell. Abstract: a subclass either
	 * <em>selects</em> a winner (store as-is, no re-application of
	 * {@link #prepareWrite}) or <em>synthesises</em> a new value (re-apply it).
	 *
	 * @param other Value to merge
	 * @return The merged view value
	 */
	@Override
	public abstract V merge(V other);
}
