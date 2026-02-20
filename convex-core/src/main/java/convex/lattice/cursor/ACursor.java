package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.LatticeOps;

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
 * <h2>Path Navigation</h2>
 * <p>Cursors support navigating into nested data structures via {@link #path(ACell...)}.
 * A path cursor provides a view into a specific location within the parent's value.
 * Modifications through a path cursor atomically update the parent's entire value.</p>
 *
 * <h2>Cursor Hierarchy</h2>
 * <ul>
 *   <li>{@link ACursor} - Base class with atomic read/write operations</li>
 *   <li>{@link AForkableCursor} - Adds detach/merge for optimistic concurrency (CAS-based)</li>
 *   <li>{@link ALatticeCursor} - Adds fork/sync with lattice merge (always succeeds)</li>
 * </ul>
 *
 * @param <V> Type of value held by this cursor
 * @see AForkableCursor for optimistic concurrency with CAS-based merge
 * @see ALatticeCursor for CRDT-style merge that always succeeds
 */
public abstract class ACursor<V extends ACell> {

	protected ACursor() {
	}

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

	/**
	 * Atomically associates a value at a single key within this cursor's value.
	 *
	 * <p>Throws if the current value is null — callers must initialise the
	 * structure first. {@link ALatticeCursor} overrides this to auto-initialise
	 * from the lattice's zero value.</p>
	 *
	 * @param key Key to write at
	 * @param value Value to associate
	 */
	@SuppressWarnings("unchecked")
	public void assoc(ACell key, ACell value) {
		getAndUpdate(bv -> (V) LatticeOps.assocIn(bv, value, null, key));
	}

	/**
	 * Atomically associates a value at a nested path within this cursor's value.
	 *
	 * <p>Throws if any intermediate value is null — callers must initialise the
	 * structure first. {@link ALatticeCursor} overrides this to auto-initialise
	 * from the lattice hierarchy.</p>
	 *
	 * @param value Value to set at the end of the path
	 * @param keys Path of keys to navigate
	 */
	@SuppressWarnings("unchecked")
	public void assocIn(ACell value, ACell... keys) {
		getAndUpdate(bv -> (V) LatticeOps.assocIn(bv, value, null, keys));
	}

	/**
	 * Atomically sets the cursor to the new value and returns the old value.
	 *
	 * <p>Semantically equivalent to:
	 * <pre>{@code
	 * V old = get();
	 * set(newValue);
	 * return old;
	 * }</pre>
	 * but executed atomically.</p>
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
	 * <p>Semantically equivalent to:
	 * <pre>{@code
	 * if (get() == expected) {
	 *     set(newValue);
	 *     return true;
	 * }
	 * return false;
	 * }</pre>
	 * but executed atomically.</p>
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
	 * so it should be side-effect free.</p>
	 *
	 * <p>Semantically equivalent to:
	 * <pre>{@code
	 * V old = get();
	 * set(updateFunction.apply(old));
	 * return old;
	 * }</pre>
	 * but executed atomically.</p>
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
	 * so it should be side-effect free.</p>
	 *
	 * <p>Semantically equivalent to:
	 * <pre>{@code
	 * V newVal = updateFunction.apply(get());
	 * set(newVal);
	 * return newVal;
	 * }</pre>
	 * but executed atomically.</p>
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
	 * <p>The accumulator function combines the current value with x to produce
	 * the new value. Useful for reduction operations like adding to a counter
	 * or appending to a collection.</p>
	 *
	 * <p>Semantically equivalent to:
	 * <pre>{@code
	 * V old = get();
	 * set(accumulatorFunction.apply(old, x));
	 * return old;
	 * }</pre>
	 * but executed atomically.</p>
	 *
	 * @param x Value to accumulate
	 * @param accumulatorFunction Function combining current value with x
	 * @return Value before accumulation
	 */
	public abstract V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction);

	/**
	 * Atomically accumulates a value and returns the new value.
	 *
	 * <p>The accumulator function combines the current value with x to produce
	 * the new value.</p>
	 *
	 * <p>Semantically equivalent to:
	 * <pre>{@code
	 * V newVal = accumulatorFunction.apply(get(), x);
	 * set(newVal);
	 * return newVal;
	 * }</pre>
	 * but executed atomically.</p>
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

	/**
	 * Creates a path cursor navigating into a nested location within this cursor.
	 *
	 * <p>A path cursor provides a view into a specific key path within the parent's
	 * value. Reading from the path cursor returns the nested value; writing to it
	 * atomically updates the parent's entire value with the nested change.</p>
	 *
	 * <p>Path cursors are useful for working with nested data structures without
	 * manually reading, modifying, and writing back the entire structure.</p>
	 *
	 * <pre>{@code
	 * Root<AMap> root = Root.create(Maps.of("user", Maps.of("name", "Alice")));
	 * ACursor<AString> nameCursor = root.path(Strings.create("user"), Strings.create("name"));
	 * nameCursor.set(Strings.create("Bob"));  // Atomically updates root
	 * }</pre>
	 *
	 * @param <T> Type of value at the path
	 * @param path Keys to navigate through nested data structures
	 * @return A cursor positioned at the nested path
	 */
	public abstract <T extends ACell> ACursor<T> path(ACell... path);

	/**
	 * Creates an independent copy of this cursor for isolated modifications.
	 *
	 * <p>The detached cursor starts with a snapshot of the current value and can be
	 * modified without affecting this cursor. Use {@link AForkableCursor#merge(AForkableCursor)}
	 * to attempt merging changes back (will fail if this cursor changed).</p>
	 *
	 * <p>For lattice-aware forking where merge always succeeds, use
	 * {@link ALatticeCursor#fork()} instead.</p>
	 *
	 * @param <T> Type of the detached cursor value
	 * @return A new independent Root cursor with the current value
	 * @see AForkableCursor#merge(AForkableCursor) for CAS-based merge (can fail)
	 * @see ALatticeCursor#fork() for lattice-aware fork (merge always succeeds)
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> AForkableCursor<T> detach() {
		return (AForkableCursor<T>) Root.create(get());
	}

}
