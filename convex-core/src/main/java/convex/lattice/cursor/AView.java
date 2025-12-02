package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Abstract base class for cursors that represent a view over another cursor
 * 
 * Views are generally not mutable
 * 
 * @param <T> Type of viewed value
 */
public abstract class AView<T extends ACell> extends ACursor<T> {

	protected final ACursor<T> source;

	protected AView(ACursor<T> source) {
		super();
		this.source=source;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <V extends ACell> ACursor<V> path(ACell... path) {
		if (path.length==0) return (ACursor<V>) this;
		return PathCursor.create(this,path);
	}
	
	@Override
	public T getAndSet(ACell newValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean compareAndSet(ACell expected, ACell newValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void set(ACell newValue) {
		throw new UnsupportedOperationException();
	}

	@Override
	public T getAndUpdate(UnaryOperator<T> updateFunction) {
		throw new UnsupportedOperationException();
	}

	@Override
	public T updateAndGet(UnaryOperator<T> updateFunction) {
		throw new UnsupportedOperationException();
	}

	@Override
	public T getAndAccumulate(T x, BinaryOperator<T> accumulatorFunction) {
		throw new UnsupportedOperationException();
	}

	@Override
	public T accumulateAndGet(T x, BinaryOperator<T> accumulatorFunction) {
		throw new UnsupportedOperationException();
	}


}
