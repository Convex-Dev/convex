package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.LatticeOps;

/**
 * Atomic read/write access to one immutable CVM value.
 * Paths update existing nested containers atomically through their base cursor.
 * Components construct missing containers. Cursors carry no lattice policy,
 * signing context or clock; components own those concerns.
 *
 * @param <V> Value type
 */
public abstract class ACursor<V extends ACell> {

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
	 * <p>The current value must be an existing container.</p>
	 *
	 * @param key Key to write at
	 * @param value Value to associate
	 */
	@SuppressWarnings("unchecked")
	public void assoc(ACell key, ACell value) {
		getAndUpdate(bv -> (V) LatticeOps.assocExisting(bv, value, key));
	}

	/**
	 * Atomically associates a value at a nested path within this cursor's value.
	 *
	 * <p>All intermediate containers must already exist. Components which need to
	 * create them can supply an explicit update function or a {@link ProjectionCursor}.</p>
	 *
	 * @param value Value to set at the end of the path
	 * @param keys Path of keys to navigate
	 */
	@SuppressWarnings("unchecked")
	public void assocIn(ACell value, ACell... keys) {
		getAndUpdate(bv -> (V) LatticeOps.assocExisting(bv, value, keys));
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
	 * so it should be side-effect free. An absent value is passed as null.</p>
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
	 * so it should be side-effect free. An absent value is passed as null.</p>
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
	 * Creates a cursor navigating into a nested location within this cursor.
	 *
	 * <p>Reading from the path cursor returns the nested value; writing to it
	 * atomically updates this cursor's entire value with the nested change. A write
	 * through an absent intermediate fails; components supply any construction logic.</p>
	 *
	 * @param <T> Type of value at the path
	 * @param path Keys to navigate through nested data structures
	 * @return A cursor positioned at the nested path
	 */
	public abstract <T extends ACell> ACursor<T> path(ACell... path);

}
