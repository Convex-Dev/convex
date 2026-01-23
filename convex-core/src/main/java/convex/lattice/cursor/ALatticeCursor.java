package convex.lattice.cursor;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;

/**
 * Abstract base class for lattice-aware cursors that support fork/sync patterns.
 *
 * <p>A lattice cursor knows its lattice (defining merge semantics) and can:</p>
 * <ul>
 *   <li><b>fork()</b> - Create independent working copies</li>
 *   <li><b>sync()</b> - Sync changes back to parent using lattice merge (always succeeds)</li>
 *   <li><b>merge(V)</b> - Merge an external value using lattice merge</li>
 *   <li><b>descend(keys...)</b> - Navigate through the lattice hierarchy to sub-lattices</li>
 * </ul>
 *
 * <p>Unlike {@link AForkableCursor#merge(AForkableCursor)} which uses CAS and can fail,
 * lattice cursor operations use lattice merge semantics which always succeed by
 * combining values according to the lattice's merge function.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create root lattice cursor
 * ALatticeCursor<ASet<ACell>> root = Cursors.createLattice(SetLattice.create(), Sets.empty());
 *
 * // Fork for modifications
 * ALatticeCursor<ASet<ACell>> tx = root.fork();
 * tx.updateAndGet(set -> set.include(item));
 *
 * // Sync back to root (always succeeds)
 * tx.sync();
 * }</pre>
 *
 * @param <V> Type of cursor values
 * @see AForkableCursor for CAS-based merge that can fail
 */
public abstract class ALatticeCursor<V extends ACell> extends AForkableCursor<V> {

	protected final ALattice<V> lattice;
	protected volatile LatticeContext context;

	/**
	 * Creates a lattice cursor with the given lattice and context.
	 *
	 * @param lattice The lattice defining merge semantics
	 * @param context The merge context (null defaults to EMPTY)
	 * @param initialValue Initial value for the cursor
	 */
	protected ALatticeCursor(ALattice<V> lattice, LatticeContext context, V initialValue) {
		super(initialValue);
		this.lattice = lattice;
		this.context = (context != null) ? context : LatticeContext.EMPTY;
	}

	/**
	 * Gets the lattice that defines merge semantics for this cursor.
	 *
	 * @return The lattice for this cursor level
	 */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Gets the merge context for this cursor.
	 *
	 * @return The current lattice context
	 */
	public LatticeContext getContext() {
		return context;
	}

	/**
	 * Returns a cursor with the specified context.
	 *
	 * <p>Default implementation mutates this cursor's context and returns this.
	 * Subclasses may override to return a view with different context.</p>
	 *
	 * @param context New context to use
	 * @return This cursor with updated context
	 */
	public ALatticeCursor<V> withContext(LatticeContext context) {
		this.context = context;
		return this;
	}

	/**
	 * Creates an independent fork of this cursor for isolated modifications.
	 *
	 * <p>The forked cursor starts with the current value and can be modified
	 * independently. Changes don't affect the parent until {@link #sync()} is called.</p>
	 *
	 * <p>Multiple forks can exist simultaneously and can be nested (fork from a fork).</p>
	 *
	 * @return A new forked cursor
	 */
	public ALatticeCursor<V> fork() {
		return new ForkedLatticeCursor<>(this, lattice, get(), context);
	}

	/**
	 * Syncs local changes back to the parent cursor using lattice merge semantics.
	 *
	 * <p>This operation always succeeds (unlike CAS-based merge). If the parent has
	 * been modified since the fork, the changes are combined using the lattice's
	 * merge function.</p>
	 *
	 * <p><b>Important:</b> After sync, this cursor's value becomes the merged result,
	 * not the original local value. This enables incremental syncs where subsequent
	 * calls sync only new changes.</p>
	 *
	 * <pre>{@code
	 * // Initial: parent has value P
	 * ALatticeCursor<V> fork = parent.fork();
	 * fork.set(A);
	 * // ... meanwhile, another fork syncs B to parent ...
	 * fork.sync();  // Fork now has merge(P+B, A), NOT A!
	 * }</pre>
	 *
	 * <p>Default implementation returns current value (for root cursors with no parent).</p>
	 *
	 * @return The synced (merged) value
	 */
	public V sync() {
		return get();
	}

	/**
	 * Merges an external value into this cursor using lattice merge semantics.
	 *
	 * <p>This is useful for merging values received from the network, other cursors,
	 * or any external source. The merge always succeeds.</p>
	 *
	 * @param other Value to merge into this cursor
	 * @return The merged value
	 */
	public V merge(V other) {
		return updateAndGet(current -> lattice.merge(context, current, other));
	}

	/**
	 * Navigates to a sub-lattice at the specified path.
	 *
	 * <p>Each key navigates one level deeper into the lattice hierarchy.
	 * The returned cursor can independently fork/sync at that level.</p>
	 *
	 * <p>Empty keys returns this cursor unchanged.</p>
	 *
	 * @param <T> Type of the descended cursor value
	 * @param keys Path keys to descend through
	 * @return Cursor at the descended path
	 * @throws IllegalArgumentException if no sub-lattice exists at the path
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> ALatticeCursor<T> descend(ACell... keys) {
		if (keys.length == 0) return (ALatticeCursor<T>) this;

		ACell key = keys[0];
		ALattice<T> subLattice = lattice.path(key);
		if (subLattice == null) {
			throw new IllegalArgumentException("No sub-lattice at key: " + key);
		}

		ALatticeCursor<T> descended = new DescendedLatticeCursor<>(this, key, subLattice, context);
		if (keys.length == 1) {
			return descended;
		}

		// Recursively descend remaining keys
		ACell[] remainingKeys = new ACell[keys.length - 1];
		System.arraycopy(keys, 1, remainingKeys, 0, remainingKeys.length);
		return descended.descend(remainingKeys);
	}
}
