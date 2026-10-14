package convex.lattice.cursor;

import java.util.Objects;

import convex.core.data.ACell;
import convex.lattice.LatticeContext;
import convex.lattice.Merge;

/**
 * Abstract base class for cursors that support working copies.
 *
 * <h2>Fork points</h2>
 * <p>A forkable cursor records the value it was created with as its
 * {@linkplain #getInitialValue() initial value}. A detached copy uses that as the
 * baseline for a compare-and-set {@link #merge(AForkableCursor)}; a {@link #fork}
 * uses it as the point from which {@link #sync()} converges with the parent.</p>
 *
 * <h2>Two ways back to the parent</h2>
 * <ul>
 *   <li><b>{@link #merge(AForkableCursor)}</b> (compare-and-set) succeeds only if the
 *       parent still holds the value it had at detachment; the caller retries on
 *       failure.</li>
 *   <li><b>{@link #sync()}</b> on a fork created with a {@link Merge} converges the
 *       working copy into the parent with that function, so it always succeeds. The
 *       component that owns the level supplies the merge, usually its lattice:
 *       {@code cursor.fork(FEED_LATTICE)}.</li>
 * </ul>
 *
 * <p>{@link #sync()} on a live (non-forked) cursor propagates to the root, where the
 * host's publication policy runs. Cursors know nothing about lattices: the merge
 * function is a parameter, supplied where the fork is made.</p>
 *
 * @param <V> Type of cursor values
 */
public abstract class AForkableCursor<V extends ACell> extends ACursor<V> {

	private final V initialValue;

	/**
	 * Creates a forkable cursor with the specified initial value.
	 *
	 * @param value Initial value (may be null)
	 */
	protected AForkableCursor(V value) {
		super();
		this.initialValue = value;
	}

	/**
	 * Creates a forkable cursor with the specified initial value and local context.
	 *
	 * @param value Initial value (may be null)
	 * @param context Local context override, or null to inherit
	 */
	protected AForkableCursor(V value, LatticeContext context) {
		super(context);
		this.initialValue = value;
	}

	@Override
	public AForkableCursor<V> setContext(LatticeContext context) {
		super.setContext(context);
		return this;
	}

	/**
	 * Gets the initial value of this cursor when it was created or forked: the
	 * baseline a compare-and-set merge checks against.
	 *
	 * @return Value of cursor when initialised (possibly null)
	 */
	public V getInitialValue() {
		return initialValue;
	}

	/**
	 * Merges a detached cursor back into this cursor using compare-and-set: succeeds
	 * only if this cursor still holds the detached cursor's initial value.
	 *
	 * @param detached The detached cursor to merge back (must have been detached from this cursor)
	 * @return true if merged, false if this cursor changed since detachment
	 * @see #detach()
	 * @see #sync() for the always-succeeding alternative on forks with a merge function
	 */
	public boolean merge(AForkableCursor<V> detached) {
		V newValue = detached.get();
		V detachedValue = detached.getInitialValue();
		return compareAndSet(detachedValue, newValue);
	}

	/**
	 * Merges an external value into this location with the supplied merge function
	 * and this cursor's effective context, retrying atomically against concurrent
	 * writes. The current value is the {@code own} operand.
	 *
	 * @param merge Merge function for this position, usually its lattice
	 * @param other Incoming value
	 * @return Value installed by this merge
	 */
	public V merge(Merge<V> merge, V other) {
		Objects.requireNonNull(merge, "Merge function must not be null");
		return updateAndGet(current -> merge.merge(getContext(), current, other));
	}

	/**
	 * Creates a path cursor navigating into a nested location within this cursor.
	 *
	 * @param <T> Type of value at the path
	 * @param path Keys to navigate through nested data structures
	 * @return A path cursor positioned at the nested path (returns this if path is empty)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> AForkableCursor<T> path(ACell... path) {
		if (path.length == 0) return (AForkableCursor<T>) this;
		return PathCursor.create(this, path);
	}

	/**
	 * Creates an independent copy of this cursor's value for isolated modifications,
	 * with no link back. Use {@link #merge(AForkableCursor)} to attempt a
	 * compare-and-set merge of the result.
	 *
	 * @param <T> Type of the detached cursor value
	 * @return A new independent Root cursor with the current value
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> AForkableCursor<T> detach() {
		return (AForkableCursor<T>) Root.create(get());
	}

	/**
	 * Creates a working copy whose {@link #sync()} converges into this cursor with
	 * the supplied merge function. The fork captures this cursor's effective context
	 * for its own writes; a dynamic policy remains dynamic. If this cursor has
	 * advanced by sync time, the two values are merged using this cursor's current
	 * effective context.
	 *
	 * @param merge Merge function for this position, usually the owning component's lattice
	 * @return A new forked cursor with local storage
	 */
	public ForkedCursor<V> fork(Merge<V> merge) {
		return new ForkedCursor<>(this, get(), getContext(), merge);
	}

	/**
	 * Creates a working copy with write-back semantics: {@link #sync()} overwrites
	 * this cursor's value with the fork's, whatever happened here in between. Use
	 * {@link #fork(Merge)} wherever concurrent writers are possible.
	 *
	 * @return A new forked cursor with local storage
	 */
	public ForkedCursor<V> fork() {
		return fork(null);
	}

	/**
	 * Synchronises this cursor towards its parent. On a fork, converges the working
	 * copy into the cursor it was forked from. On a live cursor, propagates to the
	 * root, where the host's publication policy runs.
	 *
	 * @return The value at this cursor after synchronisation
	 */
	public abstract V sync();
}
