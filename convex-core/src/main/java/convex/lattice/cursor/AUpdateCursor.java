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
 * {@link #updateOnWrite(ACell, ACell) update-on-write} function before it is stored.
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
 *   <li>{@link #updateOnWrite(ACell, ACell)} — <b>the</b> point every write funnels
 *       through. Sees the current stored cell, so an unchanged write can be a no-op
 *       (skipping an expensive re-sign / re-stamp). This is where write-time
 *       preconditions are enforced (it may consult {@link #getContext()} and may throw).</li>
 *   <li>{@link #view(ACell)} — how a stored cell reads back as a view value. Identity
 *       by default (the stored type <em>is</em> the view type); only a type-changing
 *       boundary overrides it.</li>
 * </ul>
 *
 * <p><b>Update vs merge.</b> {@code updateOnWrite} governs <em>writes</em>, not
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
	 * The update-on-write function: given the current stored cell and the new view
	 * value, return the cell to store. This is the single point every write funnels
	 * through — stamp here, sign here, enforce write preconditions here.
	 *
	 * <p>Implementations should return {@code current} unchanged when the value did
	 * not change, so an unchanged write skips expensive re-encoding even if object
	 * identity differs. Both {@code current} and {@code value} may be null.</p>
	 *
	 * @param current Current stored cell (may be null)
	 * @param value New view value being written (may be null)
	 * @return Stored cell to write
	 */
	protected abstract S updateOnWrite(S current, V value);

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
		base.getAndUpdate(s -> updateOnWrite(s, newValue));
	}

	@Override
	public V getAndSet(V newValue) {
		return view(base.getAndUpdate(s -> updateOnWrite(s, newValue)));
	}

	@Override
	public V getAndUpdate(UnaryOperator<V> updateFunction) {
		return view(base.getAndUpdate(s -> updateOnWrite(s, updateFunction.apply(view(s)))));
	}

	@Override
	public V updateAndGet(UnaryOperator<V> updateFunction) {
		return view(base.updateAndGet(s -> updateOnWrite(s, updateFunction.apply(view(s)))));
	}

	@Override
	public V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction) {
		return view(base.getAndUpdate(s -> updateOnWrite(s, accumulatorFunction.apply(view(s), x))));
	}

	@Override
	public V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction) {
		return view(base.updateAndGet(s -> updateOnWrite(s, accumulatorFunction.apply(view(s), x))));
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
		return base.compareAndSet(cur, updateOnWrite(cur, newValue));
	}

	/**
	 * Converge an external value into the stored cell. Abstract: a subclass either
	 * <em>selects</em> a winner (store as-is, no re-application of
	 * {@link #updateOnWrite}) or <em>synthesises</em> a new value (re-apply it).
	 *
	 * @param other Value to merge
	 * @return The merged view value
	 */
	@Override
	public abstract V merge(V other);
}
