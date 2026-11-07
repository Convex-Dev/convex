package convex.lattice.cursor;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;

/**
 * Read-only view over another cursor, such as a transformation or cache. Every write
 * throws {@link ReadOnlyCursorException}. For a read-only view that components can
 * bind and fork, use {@link AForkableCursor#readOnly()}.
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
		throw new ReadOnlyCursorException();
	}

	@Override
	public boolean compareAndSet(ACell expected, ACell newValue) {
		throw new ReadOnlyCursorException();
	}

	@Override
	public void set(ACell newValue) {
		throw new ReadOnlyCursorException();
	}

	@Override
	public T getAndUpdate(UnaryOperator<T> updateFunction) {
		throw new ReadOnlyCursorException();
	}

	@Override
	public T updateAndGet(UnaryOperator<T> updateFunction) {
		throw new ReadOnlyCursorException();
	}

	@Override
	public T getAndAccumulate(T x, BinaryOperator<T> accumulatorFunction) {
		throw new ReadOnlyCursorException();
	}

	@Override
	public T accumulateAndGet(T x, BinaryOperator<T> accumulatorFunction) {
		throw new ReadOnlyCursorException();
	}


}
