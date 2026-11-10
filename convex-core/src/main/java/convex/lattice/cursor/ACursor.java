package convex.lattice.cursor;

import java.io.IOException;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.exceptions.StoreException;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.LatticeOps;

/**
 * Atomic read/write access to one immutable CVM value.
 * Paths update existing nested containers atomically through their base cursor.
 * Components construct missing containers. Cursors carry no lattice policy,
 * signing context, clock or store; components own those concerns.
 *
 * @param <V> Value type
 */
public abstract class ACursor<V extends ACell> {

	/** Internal absence marker. Never a CVM value and never written to storage. */
	protected static final Object MISSING=new Object();

	/**
	 * Reads one snapshot, returning a cell (including nil) or {@link #MISSING}.
	 * Roots and total views always hold a value. Partial views override this hook
	 * and preserve source absence without throwing or allocating a result wrapper.
	 */
	protected Object readValue() { return get(); }

	/** Whether this location holds a value, including nil. Unresolvable paths are absent. */
	public boolean exists() { return readValue()!=MISSING; }

	/**
	 * Returns the value, or the supplied fallback for an absent location. A stored
	 * nil stays nil. Unresolvable paths use the fallback. Presence and value come from
	 * one snapshot; expected absence does not construct an exception.
	 */
	@SuppressWarnings("unchecked")
	public V getOrDefault(V notFound) {
		Object value=readValue();
		return value==MISSING?notFound:(V)value;
	}

	/** Reads a nested path from one snapshot, with an explicit fallback for absence. */
	public ACell getOrDefault(ACell notFound,ACell... path) {
		Object value=CursorValues.at(readValue(),path);
		return value==MISSING?notFound:(ACell)value;
	}

	// ===== Exclusive transactions =====

	/**
	 * Takes the {@link Claim} of the root holding this cursor's state, covering what
	 * the reader finds in this cursor's value, and waiting for any earlier claim on
	 * that root. Derived cursors compose their own read into the reader through their
	 * existing links, so a root needs to know nothing about paths or views. This is
	 * the only root-owned capability a cursor exposes, and it supports
	 * {@link Transactions} and atomic fork publication.
	 *
	 * @param reader Reads the claimed part from this cursor's value. Side-effect free;
	 *        runs on writers' threads while the claim is held, and a value it refuses
	 *        counts as a change to the claimed part
	 * @return The claim, to be closed by the calling thread
	 * @throws IllegalStateException if the calling thread already holds this root's claim
	 */
	protected Claim claim(Function<? super V,? extends ACell> reader) {
		throw new UnsupportedOperationException(getClass().getSimpleName()+" has no root to claim");
	}

	// ===== Reads =====

	/**
	 * Returns the current value at this cursor location.
	 *
	 * <p>The returned value reflects all prior writes to this cursor.
	 * For path cursors, returns the value at the path within the parent.</p>
	 *
	 * @return Current value (may be null when nil is stored)
	 * @throws MissingValueException If the location is absent
	 */
	public abstract V get();

	/**
	 * Returns a value at a nested path within this cursor's value.
	 *
	 * <p>Does not create a path cursor;
	 * use {@link #path(ACell...)} for that.</p>
	 * <p>The nested value may have a different type from this cursor's value.
	 * Callers must explicitly cast or validate it when a narrower type is required.</p>
	 *
	 * @param path Keys to navigate through nested data structures
	 * @return Value at the path, including a stored nil
	 * @throws MissingValueException If the path is absent
	 */
	public ACell get(ACell... path) {
		return CursorValues.require(CursorValues.at(readValue(),path));
	}

	/**
	 * Returns a value at a nested path within this cursor's value.
	 *
	 * <p>Convenience overload that accepts Java objects (converted via {@link RT#cvm}).</p>
	 * <p>The nested value may have a different type from this cursor's value.
	 * Callers must explicitly cast or validate it when a narrower type is required.</p>
	 *
	 * @param path Keys to navigate through nested data structures
	 * @return Value at the path, including a stored nil
	 * @throws MissingValueException If the path is absent
	 */
	public ACell get(Object... path) {
		ACell[] keys=new ACell[path.length];
		for (int i=0; i<path.length; i++) keys[i]=RT.cvm(path[i]);
		return get(keys);
	}

	// ===== Writes =====

	/**
	 * Atomically associates a value at a single key within this cursor's value.
	 *
	 * <p>The current value must be an existing container.</p>
	 *
	 * @param key Key to write at
	 * @param value Value to associate
	 * @throws convex.lattice.PathWriteException If the container is absent or cannot hold the key
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
	 * @throws convex.lattice.PathWriteException If a container is absent or cannot hold its key
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
	 * Atomically writes {@code newValue} if the current value equals {@code expected}, by value.
	 *
	 * @param expected Value expected
	 * @param newValue Value to write
	 * @return true if the value equalled {@code expected}; false if it differed, leaving it unchanged
	 * @throws ReadOnlyCursorException If this cursor is read-only
	 * @throws convex.lattice.PathWriteException If this cursor has nowhere to write
	 */
	public boolean compareAndSet(V expected, V newValue) {
		V before=getAndUpdate(current -> Utils.equals(current,expected)?newValue:current);
		return Utils.equals(before,expected);
	}

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

	// ===== Persistence =====

	/** Persisting is worth repeating for an edit that lands, but must not chase writes that never pause. */
	static final int PERSIST_ATTEMPTS=3;

	/**
	 * Replaces the value this cursor holds with the equal value the persister returns,
	 * in one atomic update, so the cells it has stored need not stay in memory. An
	 * edit landing meanwhile makes the update repeat on the edited value, which is
	 * harmless: cells already stored are found, not written again. After three attempts
	 * the cursor keeps the value as it stands, so writes that never pause cannot hold
	 * this up, and a later persist switches what is then held. The cursor knows no
	 * store. Only a cursor that holds a value switches: a {@link Root} and a fork do.
	 * A view holds no value of its own and, as with any write of an equal value, leaves
	 * its source as it is, so the cells are stored but the source keeps what it holds.
	 *
	 * @param persister Writes cells and returns the equal, persisted value
	 * @return Value now held: persisted unless the attempts ran out; null if the cursor holds none
	 * @throws IOException If the persister fails
	 */
	public V persist(Persister<V> persister) throws IOException {
		int[] attempts={0};
		try {
			return updateAndGet(value -> (attempts[0]++<PERSIST_ATTEMPTS)?persistValue(persister,value):value);
		} catch (StoreException e) {
			if (e.getCause() instanceof IOException cause) throw cause;
			throw e;
		}
	}

	private static <V extends ACell> V persistValue(Persister<V> persister,V value) {
		if (value==null) return null;
		try {
			return persister.persist(value);
		} catch (IOException e) {
			throw new StoreException("Unable to persist cursor value",e);
		}
	}

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
