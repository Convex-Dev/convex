package convex.lattice.cursor;

import java.util.Arrays;

import convex.core.data.ACell;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeOps;

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
 *   <li><b>{@link #sync()}</b> - Sync changes back to parent using lattice merge</li>
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
	/**
	 * Local context override. A {@code null} value means this cursor inherits its
	 * context from its parent (or {@link LatticeContext#EMPTY} at the root).
	 */
	protected volatile LatticeContext context;

	/**
	 * Creates a lattice cursor with the given lattice and context.
	 *
	 * @param lattice The lattice defining merge semantics (may be null)
	 * @param context Local merge-context override, or null to inherit
	 * @param initialValue Initial value for the cursor (may be null)
	 */
	protected ALatticeCursor(ALattice<V> lattice, LatticeContext context, V initialValue) {
		super(initialValue);
		this.lattice = lattice;
		this.context = context;
	}

	/**
	 * Gets the lattice that defines merge semantics for this cursor.
	 * @return The lattice for this cursor level (may be null)
	 */
	public ALattice<V> getLattice() {
		return lattice;
	}

	/**
	 * Gets the effective merge context for this cursor.
	 * @return The current lattice context (never null)
	 */
	public LatticeContext getContext() {
		LatticeContext local = context;
		return (local != null) ? local : getInheritedContext();
	}

	/**
	 * Gets the context inherited when this cursor has no local override.
	 * Child cursors override this to resolve their parent's current context.
	 *
	 * @return Inherited context, never null
	 */
	protected LatticeContext getInheritedContext() {
		return LatticeContext.EMPTY;
	}

	/**
	 * Sets this cursor's local context override and returns this cursor.
	 * Passing null clears the override, restoring inheritance from the parent.
	 * A non-null context is a complete policy replacement. To override one
	 * capability while delegating all others to the current effective policy,
	 * use methods such as
	 * {@link LatticeContext#withTimestamp(convex.core.data.prim.CVMLong)}.
	 * @param context New local context override, or null to inherit
	 * @return This cursor with updated context
	 */
	public ALatticeCursor<V> setContext(LatticeContext context) {
		this.context = context;
		return this;
	}

	/**
	 * @deprecated This method mutates the cursor. Use {@link #setContext(LatticeContext)}.
	 */
	@Deprecated
	public ALatticeCursor<V> withContext(LatticeContext context) {
		return setContext(context);
	}

	/**
	 * Creates an independent fork for isolated modifications.
	 * Changes don't affect the parent until {@link #sync()}. The fork captures this
	 * cursor's effective context policy for its own writes; a dynamic policy remains
	 * dynamic. If the parent has advanced,
	 * sync merges using the parent's current effective context.
	 *
	 * @return A new forked cursor with local storage
	 */
	public ALatticeCursor<V> fork() {
		return new ForkedLatticeCursor<>(this, lattice, get(), getContext());
	}

	/**
	 * Syncs local changes back to the parent using lattice merge. A conflicting fork
	 * sync uses the parent's current effective context; for root cursors, returns the
	 * current value.
	 *
	 * @return The synced value from the underlying parent
	 */
	public abstract V sync();

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
		return updateAndGet(current -> lattice.merge(getContext(), current, other));
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

	/**
	 * Resolves external / logical keys (e.g. JSON strings, hex, decimal) to
	 * canonical keys via this cursor's lattice, then navigates. This is the
	 * user-facing counterpart to the canonical-key primitive {@link #path}.
	 *
	 * <p>Resolution runs against <em>this cursor's own lattice</em>, so
	 * {@code resolve} composes associatively: {@code c.resolve(a).resolve(b)}
	 * reaches the same position as {@code c.resolve(a, b)} (each segment is
	 * resolved in the context reached by the previous one). {@code resolve()} with
	 * no keys is the identity ({@code == this}); with an identity resolver (or a
	 * null lattice) {@code resolve} reduces exactly to {@code path}.</p>
	 *
	 * @param <T> Type of the navigated cursor value
	 * @param keys External keys to resolve and navigate
	 * @return Cursor at the resolved path
	 * @throws IllegalArgumentException if a key cannot be resolved to a valid
	 *         canonical key at its level
	 */
	public <T extends ACell> ALatticeCursor<T> resolve(ACell... keys) {
		if (lattice == null) return path(keys); // nothing to resolve against — keys are already canonical
		ACell[] resolved = lattice.resolvePath(keys);
		if (resolved == null) throw new IllegalArgumentException(
			"Cannot resolve path against " + lattice.getClass().getSimpleName() + ": " + Arrays.toString(keys));
		return path(resolved);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected <T extends ACell> ALatticeCursor<T> path(ACell[] keys, int start, int end) {
		if (start > end) throw new IllegalArgumentException("start > end");
		if (start == end) return (ALatticeCursor<T>) this;

		ALatticeCursor<?> cursor = this;
		ALattice<?> lat = this.lattice;
		int segStart = start;

		for (int i = start; i < end; i++) {
			// A lattice may intercept writes at its boundary (e.g. a signing
			// boundary, or a stamp-on-write LWW leaf). The cursor stays dumb: it
			// just asks the lattice for a boundary cursor and how to treat the key.
			// Cheap, allocation-free gate on every key; only build a boundary cursor
			// when the lattice actually intercepts writes here. All interception
			// logic is a lattice property — the cursor just asks.
			if (lat != null && lat.isWriteBoundary(keys[i])) {
				// Close off accumulated keys first, so the boundary cursor wraps the
				// correct base. Built exactly once — no re-wrap on the write path.
				if (i > segStart) {
					cursor = new DescendedCursor<>(cursor, keys, segStart, i, (ALattice) lat, null);
				}
				cursor = lat.createPathCursor(cursor, keys[i], null);
				// A virtual boundary key (e.g. :value) is consumed; a transparent
				// boundary leaves the key to navigate below the wrapper.
				segStart = lat.consumesPathKey(keys[i]) ? i + 1 : i;
			}
			// Walk sub-lattice. Once null, stays null: lattice hierarchies are
			// continuous trees, so no child lattice can exist beyond a gap.
			// Remaining keys navigate plain data with null-lattice semantics.
			lat = (lat != null) ? lat.path(keys[i]) : null;
		}

		// Flush remaining collapsed keys
		if (segStart < end) {
			cursor = new DescendedCursor<>(cursor, keys, segStart, end, (ALattice) lat, null);
		}

		return (ALatticeCursor<T>) cursor;
	}
}
