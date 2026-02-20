package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeOps;
import convex.lattice.generic.SignedLattice;

/**
 * Abstract base class for lattice-aware cursors that support fork/sync patterns.
 *
 * <p>A lattice cursor extends {@link AForkableCursor} with <b>lattice merge semantics</b>.
 * Instead of CAS-based merge that can fail, lattice cursors use algebraic merge
 * operations that always succeed by combining values (CRDT semantics).</p>
 *
 * <h2>Capabilities</h2>
 * <ul>
 *   <li><b>{@link #fork()}</b> - Create independent working copy</li>
 *   <li><b>{@link #sync()}</b> - Sync changes back to parent (always succeeds with lattice)</li>
 *   <li><b>{@link #merge(ACell)}</b> - Merge external value using lattice merge</li>
 *   <li><b>{@link #path(ACell...)}</b> - Navigate into sub-lattices</li>
 * </ul>
 *
 * <h2>Null Lattice</h2>
 * <p>A cursor may have a null lattice (e.g. when navigating beyond the lattice hierarchy).
 * With null lattice:</p>
 * <ul>
 *   <li>{@code get()}, {@code set()} — work normally</li>
 *   <li>{@code merge(v)} — bubbles up via parent</li>
 *   <li>{@code fork()/sync()} — work with write-back semantics (overwrite on sync)</li>
 * </ul>
 *
 * @param <V> Type of cursor values
 * @see ALattice for the lattice interface defining merge semantics
 */
public abstract class ALatticeCursor<V extends ACell> extends AForkableCursor<V> {

	protected final ALattice<V> lattice;
	protected volatile LatticeContext context;

	/**
	 * Creates a lattice cursor with the given lattice and context.
	 *
	 * @param lattice The lattice defining merge semantics (may be null)
	 * @param context The merge context (null defaults to EMPTY)
	 * @param initialValue Initial value for the cursor (may be null)
	 */
	protected ALatticeCursor(ALattice<V> lattice, LatticeContext context, V initialValue) {
		super(initialValue);
		this.lattice = lattice;
		this.context = (context != null) ? context : LatticeContext.EMPTY;
	}

	/**
	 * Gets the lattice that defines merge semantics for this cursor.
	 * @return The lattice for this cursor level (may be null)
	 */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Gets the merge context for this cursor.
	 * @return The current lattice context (never null)
	 */
	public LatticeContext getContext() {
		return context;
	}

	/**
	 * Sets a new context for this cursor and returns this cursor.
	 * @param context New context to use (must not be null)
	 * @return This cursor with updated context
	 */
	public ALatticeCursor<V> withContext(LatticeContext context) {
		this.context = context;
		return this;
	}

	/**
	 * Creates an independent fork for isolated modifications.
	 * Changes don't affect the parent until {@link #sync()}.
	 *
	 * @return A new forked cursor with local storage
	 */
	public ALatticeCursor<V> fork() {
		return new ForkedLatticeCursor<>(this, lattice, get(), context);
	}

	/**
	 * Syncs local changes back to the parent using lattice merge.
	 * Always succeeds when a lattice is present. For root cursors, returns current value.
	 *
	 * @return The synced value
	 */
	public V sync() {
		return get();
	}

	/**
	 * Merges an external value using lattice merge semantics.
	 *
	 * <p>With a non-null lattice, performs {@code lattice.merge(current, other)}.
	 * With a null lattice, this operation is not supported at this level and
	 * throws — subclasses (e.g. DescendedCursor) override to bubble up.</p>
	 *
	 * @param other Value to merge
	 * @return The merged value
	 */
	public V merge(V other) {
		if (lattice == null) {
			throw new UnsupportedOperationException("Cannot merge without a lattice");
		}
		return updateAndGet(current -> lattice.merge(context, current, other));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void assoc(ACell key, ACell value) {
		getAndUpdate(bv -> (V) LatticeOps.assocIn(bv, value, lattice, key));
	}

	@SuppressWarnings("unchecked")
	@Override
	public void assocIn(ACell value, ACell... keys) {
		getAndUpdate(bv -> (V) LatticeOps.assocIn(bv, value, lattice, keys));
	}

	/**
	 * Navigates to a cursor at the specified path, parallel to
	 * {@code ALattice.path()}.
	 *
	 * <p>Walks the lattice hierarchy via {@code lattice.path(key)} at each step.
	 * Consecutive steps that don't require a specialised cursor are collapsed
	 * into a single {@code DescendedCursor} with a multi-key path. The chain
	 * breaks only at signing boundaries ({@code SignedLattice}), where a
	 * {@code SignedCursor} is inserted to handle sign/verify transparently.</p>
	 *
	 * @param <T> Type of the navigated cursor value
	 * @param keys Path keys to navigate through
	 * @return Cursor at the path with appropriate sub-lattice
	 */
	@Override
	public <T extends ACell> ALatticeCursor<T> path(ACell... keys) {
		return path(keys, 0, keys.length);
	}

	@SuppressWarnings("unchecked")
	protected <T extends ACell> ALatticeCursor<T> path(ACell[] keys, int start, int end) {
		if (start > end) throw new IllegalArgumentException("start > end");
		if (start == end) return (ALatticeCursor<T>) this;

		ALatticeCursor<?> cursor = this;
		ALattice<?> lat = this.lattice;
		int segStart = start;

		for (int i = start; i < end; i++) {
			// SignedLattice: RT.assocIn can't write through SignedData,
			// so we must insert a SignedCursor at this boundary
			if (lat instanceof SignedLattice<?> sl) {
				ALattice<?> inner = sl.path(keys[i]);
				if (inner != null) {
					// Flush accumulated keys as DescendedCursor
					if (i > segStart) {
						cursor = new DescendedCursor<>(cursor, keys, segStart, i, (ALattice) lat, context);
					}
					// Insert SignedCursor at the boundary
					cursor = new SignedCursor<>((ACursor) cursor, (ALattice) inner, context);
					lat = inner;
					segStart = i + 1;
					continue;
				}
			}
			// Walk sub-lattice. Once null, stays null: lattice hierarchies are
			// continuous trees, so no child lattice can exist beyond a gap.
			// Remaining keys navigate plain data with null-lattice semantics.
			lat = (lat != null) ? lat.path(keys[i]) : null;
		}

		// Flush remaining collapsed keys
		if (segStart < end) {
			cursor = new DescendedCursor<>(cursor, keys, segStart, end, (ALattice) lat, context);
		}

		return (ALatticeCursor<T>) cursor;
	}
}
