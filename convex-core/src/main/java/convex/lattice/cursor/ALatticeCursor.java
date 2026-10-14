package convex.lattice.cursor;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.util.Utils;
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
	 * Returns the lock which serialises writes to this cursor's shared state, or null
	 * when that state lives in an ordinary optimistic root. Chained cursors resolve
	 * this through their existing parent links; {@link TransactionalRootCursor}
	 * supplies its write lock. This is the only root-owned capability a lattice
	 * cursor exposes, and it exists solely to support {@link #transact}.
	 *
	 * @return Shared write lock, or null when exclusive transactions are unsupported
	 */
	protected Lock exclusiveLock() {
		return null;
	}

	/**
	 * Whether this cursor's shared state supports exclusive transactions. Ordinary
	 * roots support optimistic transactions only.
	 *
	 * @return true if {@link #transactLocked} is available
	 */
	public final boolean supportsExclusiveTransactions() {
		return exclusiveLock()!=null;
	}

	/**
	 * Runs an isolated subtree transaction. Ordinary roots retry optimistically.
	 * Transactional roots fall back to exclusive access after three failed commit
	 * attempts. An equal input reuses the
	 * computed result. A no-op completes without another write after validating its input.
	 * The callback may repeat; external side effects and writes outside the supplied
	 * view are not permitted. Exceptions discard the private state.
	 *
	 * @param action Operations on a private cursor, valid only during the callback
	 */
	public final void transact(Consumer<? super ALatticeCursor<V>> action) {
		Objects.requireNonNull(action);
		Transaction attempt=new Transaction(action);
		Lock lock=exclusiveLock();
		for (int failures=0;;failures++) {
			if (attempt.commit()) return;
			if (lock!=null && failures==2) break;
		}
		commitExclusively(attempt,lock);
	}

	/** Runs one isolated callback while excluding all other writes to the shared root. */
	public final void transactLocked(Consumer<? super ALatticeCursor<V>> action) {
		Objects.requireNonNull(action);
		Lock lock=exclusiveLock();
		if (lock==null) throw new UnsupportedOperationException("Cursor does not support exclusive transactions");
		commitExclusively(new Transaction(action),lock);
	}

	private void commitExclusively(Transaction attempt, Lock lock) {
		lock.lock();
		try {
			if (!attempt.commit()) throw new IllegalStateException("Re-entrant write during transaction commit");
		} finally {
			lock.unlock();
		}
	}

	private final class Transaction {
		private final Consumer<? super ALatticeCursor<V>> action;
		private boolean computed;
		private V input;
		private V result;

		Transaction(Consumer<? super ALatticeCursor<V>> action) { this.action=action; }

		boolean commit() {
			V observed=get();
			if (!computed || !Utils.equals(observed,input)) {
				input=observed;
				V initial=(input==null && lattice!=null)?lattice.zero():input;
				TransactionCursor<V> draft=new TransactionCursor<>(lattice,initial,getContext());
				try {
					action.accept(draft);
					V candidate=draft.get();
					// Merely reading an absent path must not materialise its zero value.
					result=Utils.equals(initial,candidate)?input:candidate;
					computed=true;
				} finally {
					draft.close();
				}
			}
			// Validate against a fresh observation, including no-ops. Its reference
			// may differ from the input while denoting exactly the same lattice value.
			observed=get();
			if (!Utils.equals(observed,input)) return false;
			if (Utils.equals(observed,result)) return true;
			return compareAndSet(observed,result);
		}
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
	 * <p>Walks the lattice hierarchy via {@code lattice.path(key)} at each step so
	 * the resulting cursor carries the sub-lattice at its position, for typed
	 * auto-initialisation and fork merge-back. All keys collapse into one
	 * {@code DescendedCursor}. Navigation never inserts write interceptors: the
	 * component which owns a signing or stamping boundary wraps the cursor it hands
	 * out, with {@code SignedCursor.forOwner} or {@code StampedCursor.create}.</p>
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

		ALattice<?> lat = this.lattice;
		for (int i = start; i < end; i++) {
			// Walk the sub-lattice. Once null, stays null: lattice hierarchies are
			// continuous trees, so no child lattice can exist beyond a gap.
			// Remaining keys navigate plain data with null-lattice semantics.
			lat = (lat != null) ? lat.path(keys[i]) : null;
		}
		return new DescendedCursor<>(this, keys, start, end, (ALattice) lat, null);
	}
}
