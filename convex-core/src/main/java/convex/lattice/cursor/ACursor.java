package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeOps;
import convex.lattice.Shape;

/**
 * A cursor is a mutable reference to a CVM value with atomic update operations.
 *
 * <h2>Semantic Model</h2>
 * <p>A cursor represents a "location" that holds a value. The value can be read,
 * written, and atomically updated. Cursors provide the same atomic guarantees as
 * {@link java.util.concurrent.atomic.AtomicReference}:</p>
 * <ul>
 *   <li><b>Visibility:</b> Writes are visible to subsequent reads</li>
 *   <li><b>Atomicity:</b> Update operations (CAS, updateAndGet, etc.) are atomic</li>
 *   <li><b>Ordering:</b> Operations on a single cursor are sequentially consistent</li>
 * </ul>
 *
 * <h2>Navigation</h2>
 * <p>{@link #path(ACell...)} descends into nested data; writes through the result
 * atomically update the whole value above. {@link #child(ACell, Shape)} does the same
 * while declaring the empty value of the child position, so a write can create it
 * when absent. The component that owns a level declares that shape, usually by
 * passing its lattice.</p>
 *
 * <h2>What a cursor knows</h2>
 * <p>Nothing about lattices. A location may carry a {@link Shape} (its empty value),
 * an inherited write-policy {@link LatticeContext}, and, for forks, a merge
 * function; all are supplied by whoever creates the cursor. Storage, publication
 * and replication are host concerns attached at the root, not cursor operations.</p>
 *
 * <h2>Cursor Hierarchy</h2>
 * <ul>
 *   <li>{@link ACursor} - atomic read/write at one location, navigation, write policy</li>
 *   <li>{@link AForkableCursor} - fork/sync and CAS merge of working copies</li>
 *   <li>{@link AView} - read-only views over another cursor</li>
 * </ul>
 *
 * @param <V> Type of value held by this cursor
 */
public abstract class ACursor<V extends ACell> {

	/**
	 * Local write-policy override. A {@code null} value means this cursor inherits
	 * its context from the cursor it was navigated or forked from.
	 */
	protected volatile LatticeContext context;

	protected ACursor() {
	}

	protected ACursor(LatticeContext context) {
		this.context = context;
	}

	// ===== Shape =====

	/**
	 * Returns the empty value of this location, used to create it when a write
	 * arrives through an absent value and supplied to update functions in place of
	 * null. Untyped locations have no empty value.
	 *
	 * @return Shape of this location, never null
	 */
	public Shape<V> shape() {
		return Shape.none();
	}

	// ===== Write policy =====

	/**
	 * Gets the effective write policy for this cursor.
	 * @return The current context (never null)
	 */
	public LatticeContext getContext() {
		LatticeContext local = context;
		return (local != null) ? local : inheritedContext();
	}

	/**
	 * Gets the context inherited when this cursor has no local override. Derived
	 * cursors resolve their parent's current context live.
	 *
	 * @return Inherited context, never null
	 */
	protected LatticeContext inheritedContext() {
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
	public ACursor<V> setContext(LatticeContext context) {
		this.context = context;
		return this;
	}

	// ===== Exclusive transactions =====

	/**
	 * Returns the lock which serialises writes to this cursor's shared state, or null
	 * when that state lives in an ordinary optimistic root. Derived cursors resolve
	 * this through their existing links; {@link TransactionalRootCursor} supplies its
	 * write lock. This is the only root-owned capability a cursor exposes, and it
	 * exists solely to support {@link Transactions}.
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
	 * @return true if {@link Transactions#transactLocked} is available
	 */
	public final boolean supportsExclusiveTransactions() {
		return exclusiveLock() != null;
	}

	// ===== Reads =====

	/**
	 * Returns the current value at this cursor location.
	 *
	 * <p>The returned value reflects all prior writes to this cursor.
	 * For path cursors, returns the value at the path within the parent.</p>
	 *
	 * @return Current value (may be null)
	 */
	public abstract V get();

	/**
	 * Returns a value at a nested path within this cursor's value.
	 *
	 * <p>Equivalent to {@code RT.getIn(get(), path)}. Does not create a path cursor;
	 * use {@link #path(ACell...)} for that.</p>
	 *
	 * @param path Keys to navigate through nested data structures
	 * @return Value at the path, or null if path doesn't exist
	 */
	public V get(ACell... path) {
		return RT.getIn(get(), path);
	}

	/**
	 * Returns a value at a nested path within this cursor's value.
	 *
	 * <p>Convenience overload that accepts Java objects (converted via {@link RT#cvm}).</p>
	 *
	 * @param path Keys to navigate through nested data structures
	 * @return Value at the path, or null if path doesn't exist
	 */
	public V get(Object... path) {
		return RT.getIn(get(), path);
	}

	// ===== Writes =====

	/**
	 * Atomically associates a value at a single key within this cursor's value.
	 *
	 * <p>An absent current value is created from this location's {@link #shape()};
	 * with no shape the write throws rather than inventing a container.</p>
	 *
	 * @param key Key to write at
	 * @param value Value to associate
	 */
	@SuppressWarnings("unchecked")
	public void assoc(ACell key, ACell value) {
		getAndUpdate(bv -> (V) LatticeOps.assocIn(bv, value, shape(), key));
	}

	/**
	 * Atomically associates a value at a nested path within this cursor's value.
	 *
	 * <p>An absent current value is created from this location's {@link #shape()}.
	 * Missing intermediates below it are created only for a structural shape;
	 * otherwise navigate with {@link #child(ACell, Shape)} so each level declares
	 * its own empty value.</p>
	 *
	 * @param value Value to set at the end of the path
	 * @param keys Path of keys to navigate
	 */
	@SuppressWarnings("unchecked")
	public void assocIn(ACell value, ACell... keys) {
		getAndUpdate(bv -> (V) LatticeOps.assocIn(bv, value, shape(), keys));
	}

	/**
	 * Atomically sets the cursor to the new value and returns the old value.
	 *
	 * @param newValue New value to set
	 * @return Previous value before the update
	 */
	public abstract V getAndSet(V newValue);

	/**
	 * Atomically sets the value if it currently equals the expected value.
	 *
	 * <p>This is the fundamental Compare-And-Set (CAS) operation. It succeeds only if
	 * the current value is identical (==) to the expected value. This enables optimistic
	 * concurrency: read a value, compute a new value, and attempt to write it back,
	 * failing if another thread modified it in between.</p>
	 *
	 * @param expected The value expected to be present
	 * @param newValue The new value to set if expectation holds
	 * @return true if update succeeded, false if current value wasn't expected
	 */
	public abstract boolean compareAndSet(V expected, V newValue);

	/**
	 * Sets the cursor to the new value.
	 *
	 * <p>The write is immediately visible to subsequent reads. For concurrent
	 * updates where you need to read-modify-write, use {@link #updateAndGet}
	 * or {@link #compareAndSet} instead.</p>
	 *
	 * @param newValue New value to set (may be null)
	 */
	public abstract void set(V newValue);

	/**
	 * Sets the cursor value after converting from a Java object.
	 *
	 * <p>If the object is already an {@link ACell}, it's used directly.
	 * Otherwise, it's converted via {@link RT#cvm(Object)}.</p>
	 *
	 * @param o Value to convert and set
	 */
	@SuppressWarnings("unchecked")
	public void set(Object o) {
		if (o instanceof ACell cell) {
			set((V)cell);
		} else {
			set(RT.cvm(o));
		}
	}

	/**
	 * Atomically updates the value and returns the old value.
	 *
	 * <p>The update function is applied to the current value to produce the new value.
	 * The function may be called multiple times if concurrent updates cause CAS failures,
	 * so it should be side-effect free. A cursor with a shape passes that shape's empty
	 * value instead of null for an absent value.</p>
	 *
	 * @param updateFunction Function to compute new value from current value
	 * @return Value before the update was applied
	 */
	public abstract V getAndUpdate(UnaryOperator<V> updateFunction);

	/**
	 * Atomically updates the value and returns the new value.
	 *
	 * <p>The update function is applied to the current value to produce the new value.
	 * The function may be called multiple times if concurrent updates cause CAS failures,
	 * so it should be side-effect free. A cursor with a shape passes that shape's empty
	 * value instead of null for an absent value.</p>
	 *
	 * @param updateFunction Function to compute new value from current value
	 * @return Value after the update was applied
	 */
	public abstract V updateAndGet(UnaryOperator<V> updateFunction);

	/**
	 * Atomically updates the value using the given function.
	 *
	 * <p>Equivalent to {@link #getAndUpdate} but discards the return value.</p>
	 *
	 * @param updateFunction Function to compute new value from current value
	 */
	public void update(UnaryOperator<V> updateFunction) {
		getAndUpdate(updateFunction);
	}

	/**
	 * Atomically accumulates a value and returns the old value.
	 *
	 * @param x Value to accumulate
	 * @param accumulatorFunction Function combining current value with x
	 * @return Value before accumulation
	 */
	public abstract V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction);

	/**
	 * Atomically accumulates a value and returns the new value.
	 *
	 * @param x Value to accumulate
	 * @param accumulatorFunction Function combining current value with x
	 * @return Value after accumulation
	 */
	public abstract V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction);

	@Override
	public String toString() {
		V v=get();
		if (v==null) return "nil";
		return v.toString();
	}

	// ===== Navigation =====

	/**
	 * Creates a cursor navigating into a nested location within this cursor, with no
	 * declared empty value.
	 *
	 * <p>Reading from the path cursor returns the nested value; writing to it
	 * atomically updates this cursor's entire value with the nested change. A write
	 * through an absent intermediate fails unless the intermediate's shape is known,
	 * so components navigate one declared level at a time with {@link #child}.</p>
	 *
	 * @param <T> Type of value at the path
	 * @param path Keys to navigate through nested data structures
	 * @return A cursor positioned at the nested path
	 */
	public abstract <T extends ACell> ACursor<T> path(ACell... path);

	/**
	 * Creates a cursor at a child key, declaring the child's empty value.
	 *
	 * <p>This is how a component that owns a level hands out the level below it:
	 * {@code cursor.child(KEY_FEED, FEED_LATTICE)}. The child can be created when
	 * absent, and update functions on it receive the empty value instead of null.
	 * This cursor's own shape is used to create this level if it is absent.</p>
	 *
	 * @param <T> Type of the child value
	 * @param key Child key
	 * @param shape Shape of the child position
	 * @return Cursor at the child
	 */
	public <T extends ACell> AForkableCursor<T> child(ACell key, Shape<T> shape) {
		return new PathCursor<>(this, new ACell[] {key}, shape);
	}

	/**
	 * Creates a cursor at a nested path, declaring the empty value at its end. Missing
	 * intermediates are created only for a structural shape at this cursor.
	 *
	 * @param <T> Type of the value at the path
	 * @param keys Path keys
	 * @param shape Shape of the end position
	 * @return Cursor at the path
	 */
	public <T extends ACell> AForkableCursor<T> child(ACell[] keys, Shape<T> shape) {
		return new PathCursor<>(this, keys, shape);
	}

}
