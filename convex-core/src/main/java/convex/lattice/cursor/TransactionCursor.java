package convex.lattice.cursor;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.lattice.Shape;

/** A private working root, confined to one synchronous callback. */
final class TransactionCursor<V extends ACell> extends AForkableCursor<V> {

	private final Thread owner=Thread.currentThread();
	private boolean open=true;
	private V value;
	private final Shape<V> shape;
	/** Private state is single-threaded; nested locked transactions trivially exclude nothing. */
	private final Lock lock=new ReentrantLock();

	TransactionCursor(V initial,Shape<V> shape) {
		super(initial);
		this.value=initial;
		this.shape=(shape==null)?Shape.none():shape;
	}

	private void checkAccess() {
		if (Thread.currentThread()!=owner || !open) {
			throw new IllegalStateException("Transaction view is only valid within its callback");
		}
	}

	void close() { open=false; }

	@Override public Shape<V> shape() { return shape; }

	@Override public V get() { checkAccess(); return value; }
	@Override public void set(V value) { checkAccess(); this.value=value; }
	@Override public V getAndSet(V value) {
		V previous=get();
		set(value);
		return previous;
	}
	@Override public boolean compareAndSet(V expected,V value) {
		checkAccess();
		if (this.value!=expected) return false;
		this.value=value;
		return true;
	}
	@Override public V getAndUpdate(UnaryOperator<V> action) {
		V previous=get();
		set(action.apply(previous));
		return previous;
	}
	@Override public V updateAndGet(UnaryOperator<V> action) {
		V next=action.apply(get());
		set(next);
		return next;
	}
	@Override public V getAndAccumulate(V other,BinaryOperator<V> action) {
		return getAndUpdate(current -> action.apply(current,other));
	}
	@Override public V accumulateAndGet(V other,BinaryOperator<V> action) {
		return updateAndGet(current -> action.apply(current,other));
	}
	@Override protected Lock exclusiveLock() {
		checkAccess();
		return lock;
	}

	@Override public V sync() {
		throw new IllegalStateException("Transaction views cannot publish or sync");
	}
}
