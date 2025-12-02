package convex.lattice.cursor;

import convex.core.data.ACell;

public abstract class ABranchedCursor<V extends ACell> extends ACursor<V> {

	private final V initialValue;

	protected ABranchedCursor(V value) {
		super();
		this.initialValue=value;
	}

	/**
	 * Gets the initial value of this cursor
	 * @return Value of cursor when initialised (possibly null);
	 */
	public V getInitialValue() {
		return initialValue;
	}

	/**
	 * Sync back a cursor that was detached from this cursor
	 * @param detached
	 * @return True if update was successful
	 */
	public boolean sync(ABranchedCursor<V> detached) {
		V newValue=detached.get();
		V detachedValue=detached.getInitialValue();
		
		boolean updated = compareAndSet(detachedValue,newValue);
		return updated;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> ABranchedCursor<T> path(ACell... path) {
		if (path.length==0) return (ABranchedCursor<T>) this;
		return PathCursor.create(this,path);
	}
}
