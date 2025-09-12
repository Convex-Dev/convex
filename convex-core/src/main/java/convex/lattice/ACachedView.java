package convex.lattice;

import convex.core.data.ACell;

/**
 * Abstract base class for cursor implementations that cache a value
 * @param <V>
 */
public abstract class ACachedView<V extends ACell> extends AView<V> {

	protected ACachedView(ACursor<V> source) {
		super(source);
		// TODO Auto-generated constructor stub
	}

	@Override
	public V get() {
		return source.get();
	}


}
