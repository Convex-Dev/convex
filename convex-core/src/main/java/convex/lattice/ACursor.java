package convex.lattice;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.lang.RT;

/**
 * A Lattice cursor is a mutable pointer into a CVM data structure.
 * 
 * Methods are modelled after java.util.concurrent.atomic.AtomicReference for consistency and logic
 * 
 * @param <V>
 */
public abstract class ACursor<V extends ACell> {

	private final V initialValue;
	
	protected ACursor(V value) {
		this.initialValue=value;
	}
	
	/**
	 * Gets the value of this cursor
	 * @return Value at cursor
	 */
	public abstract V get();
	
	/**
	 * Gets the value at a specific lattice path within this cursor
	 * @param path Path into this cursor
	 * @return Value at cursor
	 */
	public V get(ACell... path) {
		return RT.getIn(get(), path);
	}
	
	/**
	 * Gets the value at a specific lattice path within this cursor
	 * @param path Path into this cursor
	 * @return Value at cursor
	 */
	public V get(Object... path) {
		return RT.getIn(get(), path);
	}
	
	/**
	 * Sets the value at a specific lattice path within this cursor
	 * @param newValue New value to set at the given path
	 * @param path Path into this cursor
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> void set(T newValue, ACell... path) {
		getAndUpdate(bv->{
			return (V) RT.assocIn(bv,newValue,path);
		});
	}
	
	/**
	 * Sets the value at a specific lattice path within this cursor
	 * @param newValue New value to set at the given path
	 * @param path Path into this cursor
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> void set(T newValue, Object... path) {
		getAndUpdate(bv->{
			return (V) RT.assocIn(bv,newValue,path);
		});
	}
	
	/**
	 * Gets the initial value of this cursor
	 * @return Value of cursor when initialized (possibly null);
	 */
	public V getInitialValue() {
		return initialValue;
	}

	/**
	 * Gets the cursor value, and sets it to the new value atomically.
	 * @param newValue New value to set
	 * @return Old value
	 */
	public abstract V getAndSet(V newValue);
	
	/**
	 * Compares to the old value and atomically sets if correct. 
	 * @return true if value updated, false otherwise
	 */
	public abstract boolean compareAndSet(V expected, V newValue);
	
	/**
	 * Sets the value of the cursor atomically
	 */
	public abstract void set(V newValue);
	
	/**
	 * Convenience method to set after converting to CVM value
	 * @param o
	 */
	@SuppressWarnings("unchecked")
	public void set(Object o) {
		if (o instanceof ACell cell) {
			set((V)cell);
		} else {
			set(RT.cvm(o));
		}
	}
	
	public abstract V getAndUpdate(UnaryOperator<V> updateFunction);
	
	public abstract V updateAndGet(UnaryOperator<V> updateFunction);
	
	public void update(UnaryOperator<V> updateFunction) {
		getAndUpdate(updateFunction);
	}
	
	public abstract V getAndAccumulate(V x, BinaryOperator<V> accumulatorFunction);
	
	public abstract V accumulateAndGet(V x, BinaryOperator<V> accumulatorFunction);
	
	public String toString() {
		V v=get();
		if (v==null) return "nil";
		return v.toString();
	}
	
	@SuppressWarnings("unchecked")
	public <T extends ACell> ACursor<T> path(ACell... path) {
		if (path.length==0) return (ACursor<T>) this;
		return PathCursor.create(this,path);
	}
	
	public ACursor<V> detach() {
		return Root.create(this);
	}
 	
	public boolean sync(ACursor<V> detached) {
		V newValue=detached.get();
		V detachedValue=detached.getInitialValue();
		
		boolean updated = compareAndSet(detachedValue,newValue);
		return updated;
	}


}
