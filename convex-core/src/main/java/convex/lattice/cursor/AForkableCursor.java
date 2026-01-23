package convex.lattice.cursor;

import convex.core.data.ACell;

/**
 * Abstract base class for cursors that support forking (detachment) operations.
 *
 * <h2>Semantic Model</h2>
 * <p>A forkable cursor extends {@link ACursor} with optimistic concurrency control.
 * The key concept is <b>fork points</b>: when a cursor is detached, it records
 * the current value as its "initial value". This initial value serves as the
 * expected baseline for merge operations.</p>
 *
 * <h2>Optimistic Concurrency Pattern</h2>
 * <p>The typical workflow follows the read-modify-write pattern:</p>
 * <ol>
 *   <li><b>Fork:</b> {@code AForkableCursor<V> work = parent.detach()}</li>
 *   <li><b>Modify:</b> Make changes to the forked cursor</li>
 *   <li><b>Merge:</b> {@code parent.merge(work)} - succeeds only if parent unchanged</li>
 *   <li><b>Retry:</b> If merge fails, read new value and repeat</li>
 * </ol>
 *
 * <h2>CAS-Based Merge Semantics</h2>
 * <p>The {@link #merge(AForkableCursor)} method uses Compare-And-Set (CAS) semantics:</p>
 * <pre>{@code
 * // Semantically equivalent to:
 * if (parent.get() == fork.getInitialValue()) {
 *     parent.set(fork.get());
 *     return true;
 * }
 * return false;
 * }</pre>
 *
 * <p>This means concurrent modifications are <b>detected and rejected</b> rather than
 * combined. The caller must handle conflicts explicitly (typically by retrying).</p>
 *
 * <h2>Comparison with Lattice Merge</h2>
 * <table border="1">
 *   <tr><th>Aspect</th><th>AForkableCursor (CAS)</th><th>ALatticeCursor (Lattice)</th></tr>
 *   <tr><td>Conflict handling</td><td>Fails on conflict</td><td>Merges values</td></tr>
 *   <tr><td>Return type</td><td>boolean (success/fail)</td><td>merged value</td></tr>
 *   <tr><td>Use case</td><td>Precise control needed</td><td>Commutative updates</td></tr>
 *   <tr><td>Example</td><td>Counter increment</td><td>Set union</td></tr>
 * </table>
 *
 * @param <V> Type of cursor values
 * @see ACursor for base cursor operations
 * @see ALatticeCursor for lattice-aware cursors with guaranteed merge success
 */
public abstract class AForkableCursor<V extends ACell> extends ACursor<V> {

	private final V initialValue;

	/**
	 * Creates a forkable cursor with the specified initial value.
	 *
	 * <p>The initial value is recorded as the fork point for CAS-based merge.
	 * When this cursor is merged back to a parent, the merge succeeds only if
	 * the parent still holds this initial value.</p>
	 *
	 * @param value Initial value (may be null)
	 */
	protected AForkableCursor(V value) {
		super();
		this.initialValue = value;
	}

	/**
	 * Gets the initial value of this cursor when it was created/forked.
	 *
	 * <p>This value represents the "fork point" - the snapshot of the parent's
	 * value at the time this cursor was detached. It is used by {@link #merge}
	 * to detect concurrent modifications.</p>
	 *
	 * <p>For root cursors, this is the value passed at construction time.
	 * For detached cursors, this is the parent's value at detachment time.</p>
	 *
	 * @return Value of cursor when initialized (possibly null)
	 */
	public V getInitialValue() {
		return initialValue;
	}

	/**
	 * Merge a detached cursor back into this cursor using CAS semantics.
	 *
	 * <p>This operation attempts to atomically update this cursor's value to the
	 * detached cursor's current value, but only if this cursor's value still
	 * equals the detached cursor's initial value (fork point).</p>
	 *
	 * <h3>Semantic Equivalent</h3>
	 * <pre>{@code
	 * V forkPoint = detached.getInitialValue();
	 * V newValue = detached.get();
	 * return this.compareAndSet(forkPoint, newValue);
	 * }</pre>
	 *
	 * <h3>Success Conditions</h3>
	 * <ul>
	 *   <li>Returns {@code true} if this cursor still held the fork point value</li>
	 *   <li>Returns {@code false} if this cursor was modified since the fork</li>
	 * </ul>
	 *
	 * <h3>Failure Handling</h3>
	 * <p>On failure, the caller must decide how to handle the conflict:</p>
	 * <pre>{@code
	 * AForkableCursor<V> work = parent.detach();
	 * work.updateAndGet(v -> computeNewValue(v));
	 *
	 * while (!parent.merge(work)) {
	 *     // Conflict: re-read and recompute
	 *     work = parent.detach();
	 *     work.updateAndGet(v -> computeNewValue(v));
	 * }
	 * }</pre>
	 *
	 * <h3>Comparison with Lattice Sync</h3>
	 * <p>Unlike {@link ALatticeCursor#sync()}, this method can fail. Use lattice
	 * cursors when operations are commutative and can be safely combined.</p>
	 *
	 * @param detached The detached cursor to merge back (must have been forked from this cursor)
	 * @return true if merge was successful, false if this cursor changed since fork
	 * @see #detach() for creating a detached cursor
	 * @see ALatticeCursor#sync() for lattice merge that always succeeds
	 */
	public boolean merge(AForkableCursor<V> detached) {
		V newValue = detached.get();
		V detachedValue = detached.getInitialValue();

		boolean updated = compareAndSet(detachedValue, newValue);
		return updated;
	}

	/**
	 * Creates a path cursor navigating into a nested location within this cursor.
	 *
	 * <p>Returns an {@link AForkableCursor} that supports the full forkable API
	 * including {@link #merge}. The path cursor's initial value is the nested
	 * value at the time of creation.</p>
	 *
	 * <p>Operations on the path cursor atomically update the parent's entire value.
	 * The path cursor inherits the forking capability, so it can be detached and
	 * merged independently.</p>
	 *
	 * <h3>Semantic Behavior</h3>
	 * <pre>{@code
	 * // Creating a path cursor
	 * AForkableCursor<AMap> root = ...;
	 * AForkableCursor<AInteger> count = root.path(key);
	 *
	 * // Reading: equivalent to RT.getIn(root.get(), key)
	 * count.get();
	 *
	 * // Writing: atomically updates root with new nested value
	 * count.set(newValue);  // root now has updated map
	 * }</pre>
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
}
